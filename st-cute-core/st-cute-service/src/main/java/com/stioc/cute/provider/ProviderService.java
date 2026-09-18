package com.stioc.cute.provider;

import com.stioc.cute.platform.common.BusinessException;
import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.engine.llm.types.Provider;
import com.stioc.cute.websocket.WebSocketBroadcast;
import com.stioc.cute.platform.util.ConfigMergeUtils;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.stioc.cute.engine.llm.ProviderResolver;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * 大模型供应商配置管理与解析服务。
 * <p>宿主实现引擎 {@link ProviderResolver} 供血接口：
 * 引擎经本服务获取会话对应的供应商配置快照；客户端组装与缓存已内化引擎
 * （CuteChatFactory），本服务不持有任何 LLM 客户端零件。</p>
 */
@Slf4j
@Service
public class ProviderService implements ProviderResolver {

    @Resource
    private ContractProperty contractProperty;
    @Resource
    private WebSocketBroadcast webSocketBroadcast;

    @PostConstruct
    public void initDefaultProviders() {
        log.info("开始初始化 Provider 配置...");
        List<Provider> providers = contractProperty.getProviders();
        if (providers == null || providers.isEmpty()) {
            log.info("当前无大模型供应商配置加载。");
            return;
        }

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
                    .useFullUrl(p.getUseFullUrl())
                    .apiKey(p.getApiKey())
                    .modelName(p.getModelName())
                    .temperature(p.getTemperature())
                    .contextSize(p.getContextSize())
                    .maxTokens(p.getMaxTokens())
                    .reasoningEffort(p.getReasoningEffort())
                    .multimodal(p.getMultimodal())
                    .build();
            result.add(clone);
        }
        return result;
    }

    /**
     * 保存大模型供应商配置并同步持久化写回全局 JSON 文件中
     */
    public Provider saveProvider(Provider config, String originalGroup, String originalModelName) {
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

        // 查找待更新的索引 (根据"原始分组 + 原始模型名"联合匹配定位，支持编辑时修改分组名称；
        // originalGroup 未传时回退用新分组匹配，兼容旧调用行为)
        int index = -1;
        if (StringUtils.hasText(originalModelName)) {
            String matchGroup = StringUtils.hasText(originalGroup) ? originalGroup : group;
            for (int i = 0; i < providers.size(); i++) {
                Provider p = providers.get(i);
                if (originalModelName.equals(p.getModelName()) && matchGroup.equals(p.getGroup())) {
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
        webSocketBroadcast.broadcast(WebSocketBroadcast.EventType.PROVIDERS_UPDATED, getAllProviders());
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
        webSocketBroadcast.broadcast(WebSocketBroadcast.EventType.PROVIDERS_UPDATED, getAllProviders());
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
     * 保存系统基础参数（语言设置、换行热键、HTTP 日志开关、保留天数、安全密码、路径沙箱保护、极简 Skill 模式、全量用户附件装载）配置
     */
    public void saveSettings(String language, String newlineKey, boolean httpLog, int httpLogDays, String password, boolean pathSandboxEnabled, boolean minimalSkillMode, boolean loadAllUserAttachments) {
        contractProperty.setLanguage(language);
        contractProperty.setNewlineKey(newlineKey);
        contractProperty.getLlmLog().setHttpLog(httpLog);
        contractProperty.getLlmLog().setHttpLogDays(httpLogDays);
        contractProperty.setPassword(password);
        contractProperty.setPathSandboxEnabled(pathSandboxEnabled);
        contractProperty.setMinimalSkillMode(minimalSkillMode);
        contractProperty.setLoadAllUserAttachments(loadAllUserAttachments);
        writeBackGlobalConfig();

        BasicConfigDto dto = new BasicConfigDto();
        dto.setLanguage(language);
        dto.setNewlineKey(newlineKey);
        dto.setHttpLog(httpLog);
        dto.setHttpLogDays(httpLogDays);
        dto.setPassword(password);
        dto.setPathSandboxEnabled(pathSandboxEnabled);
        dto.setMinimalSkillMode(minimalSkillMode);
        dto.setLoadAllUserAttachments(loadAllUserAttachments);
        webSocketBroadcast.broadcast(WebSocketBroadcast.EventType.CONFIG_UPDATED, dto);
    }

    /**
     * 获取或惰性实例化指定会话对应的大模型 CuteChat 执行客户端（已内化引擎，本类不再持有）
     * <p>不做任何兜底：会话绑定的供应商组与模型名必须同时在配置列表中精确命中，
     * 否则返回 null，由发送链路直接报错拒绝（供应商绑定正确性由前端保证）。</p>
     */
    public Provider getProviderConfigForContext(AgentContext context) {
        String group = getProviderGroupForContext(context);
        if (!StringUtils.hasText(group)) {
            return null;
        }
        String modelName = getModelNameForContext(context, group);
        if (!StringUtils.hasText(modelName)) {
            return null;
        }
        List<Provider> providers = contractProperty.getProviders();
        if (providers == null) {
            return null;
        }
        // 严格精确匹配：组与模型名必须命中同一条记录，任何一级匹配不上都不做回退
        return providers.stream()
                .filter(p -> group.equals(p.getGroup()) && modelName.equals(p.getModelName()))
                .findFirst()
                .map(config -> Provider.builder()
                        .group(config.getGroup())
                        .protocol(config.getProtocol())
                        .baseUrl(config.getBaseUrl())
                        .useFullUrl(config.getUseFullUrl())
                        .apiKey(config.getApiKey())
                        .modelName(config.getModelName())
                        .temperature(config.getTemperature())
                        .contextSize(config.getContextSize())
                        .maxTokens(config.getMaxTokens())
                        .reasoningEffort(config.getReasoningEffort())
                        .multimodal(config.getMultimodal())
                        .build())
                .orElse(null);
    }

    /**
     * 解析该会话绑定的供应商分组名（严格匹配，不做兜底）：
     * 仅当会话绑定了分组且该分组在配置列表中存在时返回，否则返回 null
     */
    public String getProviderGroupForContext(AgentContext context) {
        List<Provider> providers = contractProperty.getProviders();
        if (providers == null || providers.isEmpty()) {
            return null;
        }

        // 会话绑定的供应商分组必须真实存在，不做"取列表第一个"兜底
        if (context != null) {
            String sGroup = context.getProviderGroup();
            if (StringUtils.hasText(sGroup)) {
                boolean exists = providers.stream().anyMatch(p -> sGroup.equals(p.getGroup()));
                if (exists) {
                    return sGroup;
                }
            }
        }
        return null;
    }

    /**
     * 解析该会话绑定的具体大模型名称（严格匹配，不做兜底）：
     * 仅返回会话内存中绑定的模型名，不再降级为组内默认模型
     */
    public String getModelNameForContext(AgentContext context, String group) {
        if (!StringUtils.hasText(group)) {
            return null;
        }

        // 仅读取内存中绑定的 providerModelName，不做"组内取第一条"降级
        if (context != null) {
            String sModel = context.getProviderModelName();
            if (StringUtils.hasText(sModel)) {
                return sModel;
            }
        }
        return null;
    }
}
