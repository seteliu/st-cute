package com.stioc.cute.provider;

import com.stioc.cute.platform.common.BusinessException;
import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.platform.contract.Provider;
import com.stioc.cute.platform.contract.ContractWsBroadcast;
import com.stioc.cute.llm.CuteChatForAnthropic;
import com.stioc.cute.llm.CuteChatForOpenAi;
import com.stioc.cute.llm.OkHttpLoggingInterceptor;
import com.stioc.cute.platform.util.ConfigMergeUtils;
import com.stioc.cute.agent.access.LlmLoggerService;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.llm.CuteChat;
import com.stioc.cute.llm.CuteChatRetryWrapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大模型供应商管理与客户端实例创建缓存服务
 */
@Slf4j
@Service
public class ProviderService {

    @Resource
    private ContractProperty contractProperty;
    @Resource
    private LlmLoggerService llmLoggerService;
    @Resource
    private ContractWsBroadcast contractWsBroadcast;

    /**
     * 实例化的大模型具体客户端的内存并发缓存，Key 格式为 "group:modelName"，Value 为 CuteChat 实例
     */
    private final ConcurrentHashMap<String, CuteChat> chatCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void initDefaultProviders() {
        log.info("开始初始化 Provider 配置...");
        List<Provider> providers = contractProperty.getProviders();
        if (providers == null || providers.isEmpty()) {
            log.info("当前无大模型供应商配置加载。");
            return;
        }

        clearChatCache();
        log.info("Provider 配置初始化完成");
    }

    /**
     * 获取当前所有已登记供应商配置列表
     */
    public List<Provider> getAllProviders() {
        List<Provider> providers = contractProperty.getProviders();
        if (providers == null) {
            return Collections.emptyList();
        }
        List<Provider> result = new ArrayList<>();
        for (Provider p : providers) {
            Provider clone = Provider.builder()
                    .group(p.getGroup())
                    .protocol(p.getProtocol())
                    .baseUrl(p.getBaseUrl())
                    .apiKey(p.getApiKey())
                    .modelName(p.getModelName())
                    .temperature(p.getTemperature())
                    .contextSize(p.getContextSize())
                    .maxTokens(p.getMaxTokens())
                    .reasoningEffort(p.getReasoningEffort())
                    .build();
            result.add(clone);
        }
        return result;
    }

    /**
     * 保存大模型供应商配置并同步持久化写回全局 JSON 文件中
     */
    public Provider saveProvider(Provider config, String originalModelName) {
        List<Provider> providers = contractProperty.getProviders();
        if (providers == null) {
            providers = new ArrayList<>();
            contractProperty.setProviders(providers);
        }

        String group = config.getGroup();
        if (!StringUtils.hasText(group)) {
            throw new IllegalArgumentException("供应商分组名称(group)不能为空");
        }
        String modelName = config.getModelName();
        if (!StringUtils.hasText(modelName)) {
            throw new IllegalArgumentException("模型名称不能为空");
        }


        // 上下文窗口大小不低于 50000 tokens
        if (config.getContextSize() != null && config.getContextSize() > 0 && config.getContextSize() < 50000) {
            throw new BusinessException("上下文窗口大小不能低于 50000 tokens");
        }

        // 查找待更新的索引 (根据 originalModelName 与 group 联合匹配定位)
        int index = -1;
        if (StringUtils.hasText(originalModelName)) {
            for (int i = 0; i < providers.size(); i++) {
                Provider p = providers.get(i);
                if (originalModelName.equals(p.getModelName()) && group.equals(p.getGroup())) {
                    index = i;
                    break;
                }
            }
        }

        // 校验同供应商下唯一模型名称（排除当前正被编辑更新的这一条）
        for (int i = 0; i < providers.size(); i++) {
            Provider p = providers.get(i);
            if (i != index && group.equalsIgnoreCase(p.getGroup()) && modelName.equalsIgnoreCase(p.getModelName())) {
                throw new BusinessException("模型 '" + modelName + "' 在供应商 '" + group + "' 中已存在，不允许重复添加");
            }
        }

        if (index >= 0) {
            providers.set(index, config);
        } else {
            providers.add(config);
        }

        log.info("保存并生效 Provider 配置: {} (模型: {})", config.getGroup(), config.getModelName());
        writeBackGlobalConfig();
        clearChatCache();
        contractWsBroadcast.broadcast(ContractWsBroadcast.EventType.PROVIDERS_UPDATED, getAllProviders());
        return config;
    }

    /**
     * 物理删除指定的供应商配置记录并同步写回全局文件
     */
    public void deleteProvider(String group, String modelName) {
        List<Provider> providers = contractProperty.getProviders();
        if (providers != null) {
            providers.removeIf(p -> group.equals(p.getGroup()) && modelName.equals(p.getModelName()));
        }

        log.info("删除 Provider 配置分组: {}, 模型: {}", group, modelName);
        writeBackGlobalConfig();
        clearChatCache();
        contractWsBroadcast.broadcast(ContractWsBroadcast.EventType.PROVIDERS_UPDATED, getAllProviders());
    }

    private void writeBackGlobalConfig() {
        try {
            File file = ContractFile.getGlobalConfigJsonFile();
            File dir = file.getParentFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // 使用反射序列化工具将 contractProperty 转换为 JSONObject
            JSONObject configObj = ConfigMergeUtils.toJsonObject(contractProperty);
            if (configObj == null) {
                configObj = new JSONObject();
            }

            String json = JSON.toJSONString(configObj, JSONWriter.Feature.PrettyFormat);
            Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
            log.info("已成功将最新的配置写回全局配置文件: {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.error("写回全局配置文件失败", e);
        }
    }

    /**
     * 保存系统基础参数（语言设置、换行热键、HTTP 日志开关、保留天数以及安全密码、消息聚合展示、路径沙箱保护）配置
     */
    public void saveSettings(String language, String newlineKey, boolean httpLog, int httpLogDays, String password, boolean messageAggregation, boolean pathSandboxEnabled) {
        contractProperty.setLanguage(language);
        contractProperty.setNewlineKey(newlineKey);
        contractProperty.getLlmLog().setHttpLog(httpLog);
        contractProperty.getLlmLog().setHttpLogDays(httpLogDays);
        contractProperty.setPassword(password);
        contractProperty.setMessageAggregation(messageAggregation);
        contractProperty.setPathSandboxEnabled(pathSandboxEnabled);
        writeBackGlobalConfig();

        BasicConfigDto dto = new BasicConfigDto();
        dto.setLanguage(language);
        dto.setNewlineKey(newlineKey);
        dto.setHttpLog(httpLog);
        dto.setHttpLogDays(httpLogDays);
        dto.setPassword(password);
        dto.setMessageAggregation(messageAggregation);
        dto.setPathSandboxEnabled(pathSandboxEnabled);
        contractWsBroadcast.broadcast(ContractWsBroadcast.EventType.CONFIG_UPDATED, dto);
    }

    /**
     * 获取或惰性实例化指定会话对应的大模型 CuteChat 执行客户端
     */
    public CuteChat getCuteChat(AgentContext context) {
        String group = getProviderGroupForContext(context);
        if (group == null) {
            throw new IllegalStateException("当前没有任何大模型供应商配置可用");
        }
        String modelName = getModelNameForContext(context, group);
        String cacheKey = group + ":" + (modelName != null ? modelName : "");
        return chatCache.computeIfAbsent(cacheKey, key -> createCuteChat(group, modelName));
    }

    /**
     * 获取指定会话对应的供应商属性配置克隆数据
     */
    public Provider getProviderConfigForContext(AgentContext context) {
        String group = getProviderGroupForContext(context);
        if (group == null) {
            return null;
        }
        List<Provider> providers = contractProperty.getProviders();
        if (providers != null) {
            String contextModel = getModelNameForContext(context, group);
            Provider config = providers.stream()
                    .filter(p -> group.equals(p.getGroup()) && (contextModel == null || contextModel.equals(p.getModelName())))
                    .findFirst()
                    .orElse(null);
            if (config == null) {
                config = providers.stream()
                        .filter(p -> group.equals(p.getGroup()))
                        .findFirst()
                        .orElse(null);
            }
            if (config != null) {
                // 返回克隆的配置，并将 modelName 覆盖为当前会话专用的 modelName
                return Provider.builder()
                        .group(config.getGroup())
                        .protocol(config.getProtocol())
                        .baseUrl(config.getBaseUrl())
                        .apiKey(config.getApiKey())
                        .modelName(contextModel != null ? contextModel : config.getModelName())
                        .temperature(config.getTemperature())
                        .contextSize(config.getContextSize())
                        .maxTokens(config.getMaxTokens())
                        .reasoningEffort(config.getReasoningEffort())
                        .build();
            }
        }
        return null;
    }

    /**
     * 自动推导并解析该会话应当选用的模型供应商分组名
     */
    public String getProviderGroupForContext(AgentContext context) {
        List<Provider> providers = contractProperty.getProviders();
        if (providers == null || providers.isEmpty()) {
            return null;
        }

        // 1. 如果指定了会话，尝试从内存获取该会话在内存中已有的供应商分组
        if (context != null) {
            String sGroup = context.getProviderGroup();
            if (StringUtils.hasText(sGroup)) {
                boolean exists = providers.stream().anyMatch(p -> sGroup.equals(p.getGroup()));
                if (exists) {
                    return sGroup;
                }
            }
        }

        // 2. 选用配置列表里的第一个供应商的分组
        return providers.get(0).getGroup();
    }

    /**
     * 自动推导并解析该会话应当选用的具体大模型名称
     */
    public String getModelNameForContext(AgentContext context, String group) {
        if (!StringUtils.hasText(group)) {
            return null;
        }

        // 1. 优先读取内存中的 providerModelName
        if (context != null) {
            String sModel = context.getProviderModelName();
            if (StringUtils.hasText(sModel)) {
                return sModel;
            }
        }

        // 2. 降级为该 group 在 Provider 中配置的默认 modelName
        List<Provider> providers = contractProperty.getProviders();
        if (providers != null) {
            return providers.stream()
                    .filter(p -> group.equals(p.getGroup()))
                    .findFirst()
                    .map(Provider::getModelName)
                    .orElse(null);
        }

        return null;
    }

    private CuteChat createCuteChat(String group, String modelName) {
        List<Provider> providers = contractProperty.getProviders();
        Provider config = null;
        if (providers != null) {
            config = providers.stream()
                    .filter(p -> group.equals(p.getGroup()) && (modelName == null || modelName.equals(p.getModelName())))
                    .findFirst()
                    .orElse(null);
            if (config == null) {
                config = providers.stream()
                        .filter(p -> group.equals(p.getGroup()))
                        .findFirst()
                        .orElse(null);
            }
        }
        if (config == null) {
            throw new IllegalArgumentException("找不到对应的 Provider 配置: " + group);
        }

        String targetModelName = StringUtils.hasText(modelName) ? modelName : config.getModelName();

        log.info("动态实例化大模型客户端 - Provider: {}, Protocol: {}, Model: {}",
                config.getGroup(), config.getProtocol(), targetModelName);

        String apiKey = config.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            apiKey = getFallbackEnvKey(config.getProtocol());
        }

        // 直接透传配置中的温度值，null 表示未设置：Anthropic 侧不传（兼容 extended thinking），OpenAI 侧内部兜底 0.7
        Double temp = config.getTemperature();

        OkHttpLoggingInterceptor interceptor = new OkHttpLoggingInterceptor(llmLoggerService);
        CuteChat client;
        switch (config.getProtocol().toUpperCase()) {
            case "OPENAI":
                String openAiBaseUrl = StringUtils.hasText(config.getBaseUrl()) ? config.getBaseUrl() : "https://api.openai.com/v1";
                client = new CuteChatForOpenAi(openAiBaseUrl, apiKey, targetModelName, temp, interceptor);
                break;

            case "ANTHROPIC":
                String anthropicBaseUrl = StringUtils.hasText(config.getBaseUrl()) ? config.getBaseUrl() : "https://api.anthropic.com/v1";
                client = new CuteChatForAnthropic(anthropicBaseUrl, apiKey, targetModelName, temp, interceptor);
                break;

            default:
                throw new UnsupportedOperationException("未知的 Provider 协议类型: " + config.getProtocol());
        }
        return new CuteChatRetryWrapper(client, contractProperty);
    }

    private String getFallbackEnvKey(String protocol) {
        String key = "";
        if ("OPENAI".equalsIgnoreCase(protocol)) {
            key = System.getenv("OPENAI_API_KEY");
            if (!StringUtils.hasText(key)) {
                key = System.getenv("DEEPSEEK_API_KEY");
            }
        } else if ("ANTHROPIC".equalsIgnoreCase(protocol)) {
            key = System.getenv("ANTHROPIC_API_KEY");
        }
        if (!StringUtils.hasText(key)) {
            log.warn("未能在配置或环境变量中找到协议 {} 对应的 API Key", protocol);
            key = "placeholder_key";
        }
        return key;
    }

    private void clearChatCache() {
        chatCache.clear();
    }
}
