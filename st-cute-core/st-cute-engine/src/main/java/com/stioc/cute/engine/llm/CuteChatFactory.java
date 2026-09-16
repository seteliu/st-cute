package com.stioc.cute.engine.llm;

import com.stioc.cute.engine.llm.types.Provider;
import com.stioc.cute.engine.llm.types.ProviderProtocol;
import com.stioc.cute.engine.loop.core.AgentContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CuteChat 工厂：解析供应商配置并惰性组装、缓存与按需重建客户端实例。
 * <p>
 * 供血链路只收「配置解析」（{@link ProviderResolver} 宿主实现）以及可选的日志与重试策略（{@link Optional} 声明）；
 * 客户端组装与缓存为引擎内部流转。缓存键 group:model，值含创建时的配置快照——宿主改配置后下次解析
 * 出的新快照与旧快照指纹（{@code Provider} equals 比对）不一致，自动重建客户端，无需任何缓存失效回调。
 * </p>
 */
@Slf4j
public class CuteChatFactory {

    private final ProviderResolver providerResolver;
    private final Optional<LlmHttpLogger> llmHttpLogger;
    private final Optional<RetryPolicyProvider> retryPolicyProvider;

    public CuteChatFactory(ProviderResolver providerResolver,
                           Optional<LlmHttpLogger> llmHttpLogger,
                           Optional<RetryPolicyProvider> retryPolicyProvider) {
        this.providerResolver = providerResolver;
        this.llmHttpLogger = llmHttpLogger != null ? llmHttpLogger : Optional.empty();
        this.retryPolicyProvider = retryPolicyProvider != null ? retryPolicyProvider : Optional.empty();
    }

    /**
     * 缓存条目：客户端实例 + 组装时的配置快照（指纹比对基准）
     */
    private record CachedClient(CuteChat client, Provider configSnapshot) {
    }

    private final Map<String, CachedClient> cache = new ConcurrentHashMap<>();

    /**
     * 获取指定会话对应的供应商配置快照（透传 ProviderResolver 解析结果，供引擎内组件读取
     * 协议、窗口大小、多模态开关等配置语义，不触发客户端组装）
     */
    public Provider getProviderConfigForContext(AgentContext context) {
        Provider config = providerResolver.getProviderConfigForContext(context);
        if (config == null) {
            throw new IllegalStateException("会话绑定的供应商不存在或已失效，请检查供应商配置");
        }
        return config;
    }

    /**
     * 获取指定会话的 CuteChat 客户端：解析配置 → 命中缓存且快照指纹一致则复用，
     * 否则重新组装并替换缓存（配置热更新自生效）
     */
    public CuteChat getCuteChat(AgentContext context) {
        Provider config = providerResolver.getProviderConfigForContext(context);
        if (config == null) {
            throw new IllegalStateException("会话绑定的供应商不存在或已失效，请检查供应商配置");
        }
        String group = config.getGroup();
        String modelName = StringUtils.isNotBlank(config.getModelName()) ? config.getModelName() : "";
        String cacheKey = group + ":" + modelName;

        CachedClient cached = cache.computeIfAbsent(cacheKey, key -> assemble(config));
        // 指纹自失效：宿主配置变更 → resolver 返回的新快照与创建时快照不一致 → 重建替换
        if (!config.equals(cached.configSnapshot())) {
            log.info("供应商配置发生变化，重建大模型客户端: {}", cacheKey);
            cached = new CachedClient(createClient(config), config);
            cache.put(cacheKey, cached);
        }
        return cached.client();
    }

    private CachedClient assemble(Provider config) {
        log.info("组装大模型客户端并纳入缓存: {}:{}", config.getGroup(), config.getModelName());
        return new CachedClient(createClient(config), config);
    }

    /**
     * 按配置组装客户端：严格 API Key 防御校验 → 协议 switch 实例化（OPENAI / OPENAI_RESPONSE / ANTHROPIC）→ 重试装饰收尾。
     * 未知协议快速失败。
     */
    CuteChat createClient(Provider config) {
        String modelName = StringUtils.isNotBlank(config.getModelName()) ? config.getModelName() : null;

        log.info("动态实例化大模型客户端 - Provider: {}, Protocol: {}, Model: {}",
                config.getGroup(), config.getProtocol(), modelName);

        String apiKey = resolveApiKey(config);
        Double temp = config.getTemperature();

        OkHttpLoggingInterceptor interceptor = new OkHttpLoggingInterceptor(llmHttpLogger.orElse(null));
        CuteChat client;
        ProviderProtocol protocol = config.getProtocolEnum();
        if (protocol == null) {
            throw new UnsupportedOperationException("未知的 Provider 协议类型: " + config.getProtocol());
        }
        switch (protocol) {
            case OPENAI -> {
                String openAiBaseUrl = StringUtils.isNotBlank(config.getBaseUrl())
                        ? config.getBaseUrl() : "https://api.openai.com/v1";
                client = new CuteChatForOpenAi(openAiBaseUrl, apiKey, modelName, temp, config.getUseFullUrl(), interceptor);
            }
            case OPENAI_RESPONSE -> {
                String openAiRespBaseUrl = StringUtils.isNotBlank(config.getBaseUrl())
                        ? config.getBaseUrl() : "https://api.openai.com/v1";
                client = new CuteChatForOpenAiResponse(openAiRespBaseUrl, apiKey, modelName, temp, config.getUseFullUrl(), interceptor);
            }
            case ANTHROPIC -> {
                String anthropicBaseUrl = StringUtils.isNotBlank(config.getBaseUrl())
                        ? config.getBaseUrl() : "https://api.anthropic.com/v1";
                client = new CuteChatForAnthropic(anthropicBaseUrl, apiKey, modelName, temp, config.getUseFullUrl(), interceptor);
            }
            default -> throw new UnsupportedOperationException("未知的 Provider 协议类型: " + config.getProtocol());
        }
        return new CuteChatRetryWrapper(client, retryPolicyProvider.orElse(null));
    }

    /**
     * API Key 解析防御：配置缺失 API Key 时直接拒绝执行，快速失败。
     */
    private String resolveApiKey(Provider config) {
        String apiKey = config.getApiKey();
        if (StringUtils.isBlank(apiKey)) {
            throw new IllegalArgumentException("Provider [" + config.getGroup() + "] 未配置 API Key，拒绝执行");
        }
        return apiKey;
    }
}
