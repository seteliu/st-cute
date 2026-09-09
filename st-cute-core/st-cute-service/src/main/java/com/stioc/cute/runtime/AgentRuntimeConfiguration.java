package com.stioc.cute.runtime;

import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.event.AgentEventListener;
import com.stioc.cute.engine.hook.HookListener;
import com.stioc.cute.engine.llm.AttachmentContentLoader;
import com.stioc.cute.engine.llm.LlmHttpLogger;
import com.stioc.cute.engine.llm.ProviderResolver;
import com.stioc.cute.engine.llm.RetryPolicyProvider;
import com.stioc.cute.engine.loop.AgentContextInitializer;
import com.stioc.cute.engine.loop.ApprovalRuleWriter;
import com.stioc.cute.engine.prompt.SystemPromptContributor;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.DynamicToolProvider;
import com.stioc.cute.engine.tool.ToolGuard;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Agent 运行时装配配置（宿主侧唯一聚合装配点，薄 Spring 适配层）。
 * <p>
 * 以 {@code @Resource} 字段注入收集宿主供血（「给引擎供了哪些血」一眼可见），
 * {@code @Bean} 方法将收集结果喂给 {@link AgentEngine#builder()}，build() 产出
 * {@link AgentEngine} 交容器管理——引擎组件不经容器出生（Builder 是唯一出生点），
 * 宿主其他 Bean 依旧 {@code @Resource AgentEngine} 经门面调用，形态不变。
 * </p>
 * <p>
 * 全局动态工具源（MCP 等）当前零实现，以 ObjectProvider 收集安全降级为空集
 * （@Resource 直注 List 在零候选时会导致容器启动失败，历史 T14 启动验证已实证）。
 * </p>
 */
@Slf4j
@Configuration
public class AgentRuntimeConfiguration {

    // ── 必选供血（缺任一项 build() 装配门禁快速失败）──
    @Resource
    private ConversationStore conversationStore;
    @Resource
    private MessageStore messageStore;
    @Resource
    private ProviderResolver providerResolver;
    @Resource
    private ToolGuard toolGuard;

    // ── 可选供血 ──
    @Resource
    private AttachmentContentLoader attachmentContentLoader;
    @Resource
    private LlmHttpLogger llmHttpLogger;
    @Resource
    private RetryPolicyProvider retryPolicyProvider;
    @Resource
    private ApprovalRuleWriter approvalRuleWriter;

    // ── 清单类 ──
    @Resource
    private List<CuteTool> staticTools;
    // 全局动态工具源：零候选安全降级空集（MCP 当前走会话级挂载，全局级暂无实现）
    @Resource
    private ObjectProvider<DynamicToolProvider> globalToolProviders;
    @Resource
    private List<AgentEventListener> eventListeners;
    @Resource
    private List<HookListener> hookListeners;
    @Resource
    private List<SystemPromptContributor> promptContributors;
    @Resource
    private List<AgentContextInitializer> contextInitializers;

    /**
     * 引擎装配入口：字段收集的宿主供血 → Builder → build() 产出 AgentEngine 交容器。
     * 必选供血缺失时 validate() 装配门禁快速失败（死在装配，不死在运行期）。
     */
    @Bean
    public AgentEngine agentEngine() {
        AgentEngine engine = AgentEngine.builder()
                .conversationStore(conversationStore)
                .messageStore(messageStore)
                .providerResolver(providerResolver)
                .toolGuard(toolGuard)
                .attachmentContentLoader(attachmentContentLoader)
                .llmHttpLogger(llmHttpLogger)
                .retryPolicyProvider(retryPolicyProvider)
                .approvalRuleWriter(approvalRuleWriter)
                .staticTools(staticTools)
                .globalToolProviders(globalToolProviders.stream().toList())
                .eventListeners(eventListeners)
                .hookListeners(hookListeners)
                .promptContributors(promptContributors)
                .contextInitializers(contextInitializers)
                .defaultConversationTitle("新会话")
                .build();

        log.info("AgentEngine 装配完成：宿主静态工具 {} 个（另含引擎内置 invoke_subagent），全局动态工具源 {} 个，"
                        + "宿主事件监听器 {} 个（另含引擎内置持久化/缓存回填两层并前置），Hook 监听器 {} 个，"
                        + "宿主提示词贡献者 {} 个（另含引擎内置默认环境段），装载扩展点 {} 个",
                staticTools.size(), globalToolProviders.stream().count(), eventListeners.size(),
                hookListeners.size(), promptContributors.size(), contextInitializers.size());

        return engine;
    }
}
