package com.stioc.cute.engine.assembly;

import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.common.EngineExecutor;
import com.stioc.cute.engine.common.EngineLock;
import com.stioc.cute.engine.common.NotifyExecutor;
import com.stioc.cute.engine.event.*;
import com.stioc.cute.engine.facade.ContextFacade;
import com.stioc.cute.engine.facade.ConversationFacade;
import com.stioc.cute.engine.facade.LoopFacade;
import com.stioc.cute.engine.facade.ToolFacade;
import com.stioc.cute.engine.hook.AgentHookDispatcher;
import com.stioc.cute.engine.hook.HookListener;
import com.stioc.cute.engine.llm.*;
import com.stioc.cute.engine.loop.AgentContextInitializer;
import com.stioc.cute.engine.loop.ApprovalRuleWriter;
import com.stioc.cute.engine.loop.core.*;
import com.stioc.cute.engine.loop.message.LlmWindowManager;
import com.stioc.cute.engine.loop.message.MessageDataReporter;
import com.stioc.cute.engine.loop.message.MessageHistoryAligner;
import com.stioc.cute.engine.loop.message.MessageInterceptor;
import com.stioc.cute.engine.prompt.SystemPromptAssembler;
import com.stioc.cute.engine.prompt.SystemPromptContributor;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.support.ChatNamingHelper;
import com.stioc.cute.engine.tool.*;
import com.stioc.cute.engine.tool.builtin.InvokeSubagentTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link AgentEngine} 流式装配构建器：引擎对象图唯一的组装根（Composition Root）。
 * <p>
 * 供血项分四区：必需供血（缺一不可，build 前校验）、扩展挂点（实现引擎契约注入行为）、
 * 可选增强（缺省用引擎内置默认）、调优配置（收集期散字段，build 时冻结为 {@link EngineOptions}）。
 * 各项职责见字段分区注释，契约的完整语义在对应接口上，此处只阐述用途与默认行为。
 * </p>
 * <p>
 * <b>装配壳约束（引擎设计铁律）</b>：装配壳指 {@link EngineStores} / {@link EngineInfra} /
 * {@link EngineOptions} 三类运输聚合对象，仅用于压缩组件构造参数的传递体积：
 * <ul>
 *   <li>引擎内部组件的构造函数可接收装配壳完成初始化，但<b>禁止直接持有装配壳字段</b>——
 *       须在构造器内解包为实际使用的最小依赖字段，壳在构造器出口即失效；</li>
 *   <li>组件保持最小持有与清晰的调用链：只用消息存储就持有 MessageStore，
 *       不因运输便利而看见整个壳（防依赖宽度悄悄扩张）；</li>
 *   <li>装配壳<b>禁止升级复杂行为</b>：壳只允许是纯 getter 数据容器，不得挂业务方法、
 *       生命周期回调或状态。若某壳确需行为（如统一关停），应将其重新设计为独立的
 *       领域对象并显式注入，而不是在壳上生长。</li>
 * </ul>
 * </p>
 */
public class AgentEngineBuilder {

    // ──────────────────────────────────────────────
    // 一、必需供血契约（缺一不可，build 时校验）
    // ──────────────────────────────────────────────

    /**
     * 会话存储：会话实体的持久化读写出口
     */
    private ConversationStore conversationStore;

    /**
     * 消息存储：消息实体的持久化读写出口
     */
    private MessageStore messageStore;

    /**
     * 供应商解析器：按会话上下文解析当前可用的大模型供应商配置
     */
    private ProviderResolver providerResolver;

    /**
     * 工具守卫：工具执行前的权限裁决入口（Allow / Deny / Ask）
     */
    private ToolGuard toolGuard;

    /**
     * 引擎锁：引擎全部并发临界区的锁来源（会话数据锁、循环锁、命名锁、写工具锁）
     */
    private EngineLock lockProvider;

    /**
     * 引擎执行器：循环拉起、工具批执行等异步任务的线程来源
     */
    private EngineExecutor executorProvider;

    // ──────────────────────────────────────────────
    // 二、扩展挂点（多值注册，宿主按需实现引擎契约注入行为）
    // ──────────────────────────────────────────────

    /**
     * 事件监听器：订阅引擎事件（落库、缓存回填之后的第三层广播）
     */
    private final List<AgentEventListener> eventListeners = new ArrayList<>();

    /**
     * Hook 监听器：生命周期同步拦截（工具调用前后，抛异常即阻断）
     */
    private final List<HookListener> hookListeners = new ArrayList<>();

    /**
     * 上下文初始化器：会话创建/恢复/热重载时装载宿主专属资产（技能、规则、MCP 等）
     */
    private final List<AgentContextInitializer> contextInitializers = new ArrayList<>();

    /**
     * 提示词贡献者：向系统提示词注入分段内容（人设、规约、环境信息等）
     */
    private final List<SystemPromptContributor> promptContributors = new ArrayList<>();

    /**
     * 静态工具：引擎启动即注册的工具清单
     */
    private final List<CuteTool> staticTools = new ArrayList<>();

    /**
     * 全局动态工具提供者：按会话动态暴露工具（如 MCP 客户端）
     */
    private final List<DynamicToolProvider> globalToolProviders = new ArrayList<>();

    /**
     * 消息拦截器：消息进出上下文窗口时的变换钩子（如附件装载、时间戳注入）
     */
    private final List<MessageInterceptor> messageInterceptors = new ArrayList<>();

    // ──────────────────────────────────────────────
    // 三、可选增强（缺省时引擎按内置默认行为运行）
    // ──────────────────────────────────────────────

    /**
     * 大模型 HTTP 日志器：记录请求/响应完整载荷，用于排查与审计
     */
    private LlmHttpLogger llmHttpLogger;

    /**
     * 重试策略：大模型调用失败的重试次数与间隔
     */
    private RetryPolicyProvider retryPolicyProvider;

    /**
     * 审批规则写入器：人在回路选「总是放行」时持久化授信规则
     */
    private ApprovalRuleWriter approvalRuleWriter;

    // ──────────────────────────────────────────────
    // 四、调优配置（收集期散字段，build 时冻结为 EngineOptions 传给组件）
    // ──────────────────────────────────────────────

    /**
     * 新会话默认标题
     */
    private String defaultConversationTitle = "新对话";

    /**
     * 通知层并行车道数：同会话事件恒落同车道保序，不同会话分车道并行。默认 8
     */
    private int notifyLaneCount = NotifyExecutor.DEFAULT_LANE_COUNT;

    /**
     * 极速模式：开启（默认）时流式事件全部异步投递车道，吞吐优先；
     * 关闭时思考流/正文流改同步直调，换取端到端背压（慢客户端让产出一并变慢）
     */
    private boolean notifyFastMode = true;

    public AgentEngineBuilder conversationStore(ConversationStore store) {
        this.conversationStore = store;
        return this;
    }

    public AgentEngineBuilder messageStore(MessageStore store) {
        this.messageStore = store;
        return this;
    }

    public AgentEngineBuilder providerResolver(ProviderResolver resolver) {
        this.providerResolver = resolver;
        return this;
    }

    public AgentEngineBuilder toolGuard(ToolGuard guard) {
        this.toolGuard = guard;
        return this;
    }

    public AgentEngineBuilder lockProvider(EngineLock provider) {
        this.lockProvider = provider;
        return this;
    }

    public AgentEngineBuilder executorProvider(EngineExecutor provider) {
        this.executorProvider = provider;
        return this;
    }

    /**
     * 技术设施整包注入：解包存储锁与执行器分量（通知执行器分量由引擎内置默认接管）
     */
    public AgentEngineBuilder infra(EngineInfra infra) {
        this.lockProvider = infra.getLocks();
        this.executorProvider = infra.getExecutor();
        return this;
    }

    public AgentEngineBuilder addEventListener(AgentEventListener listener) {
        if (listener != null) {
            this.eventListeners.add(listener);
        }
        return this;
    }

    public AgentEngineBuilder eventListeners(List<AgentEventListener> listeners) {
        if (listeners != null) {
            this.eventListeners.addAll(listeners);
        }
        return this;
    }

    public AgentEngineBuilder addHookListener(HookListener listener) {
        if (listener != null) {
            this.hookListeners.add(listener);
        }
        return this;
    }

    public AgentEngineBuilder hookListeners(List<HookListener> listeners) {
        if (listeners != null) {
            this.hookListeners.addAll(listeners);
        }
        return this;
    }

    public AgentEngineBuilder addContextInitializer(AgentContextInitializer initializer) {
        if (initializer != null) {
            this.contextInitializers.add(initializer);
        }
        return this;
    }

    public AgentEngineBuilder contextInitializers(List<AgentContextInitializer> initializers) {
        if (initializers != null) {
            this.contextInitializers.addAll(initializers);
        }
        return this;
    }

    public AgentEngineBuilder addPromptContributor(SystemPromptContributor contributor) {
        if (contributor != null) {
            this.promptContributors.add(contributor);
        }
        return this;
    }

    public AgentEngineBuilder promptContributors(List<SystemPromptContributor> contributors) {
        if (contributors != null) {
            this.promptContributors.addAll(contributors);
        }
        return this;
    }

    public AgentEngineBuilder addStaticTool(CuteTool tool) {
        if (tool != null) {
            this.staticTools.add(tool);
        }
        return this;
    }

    public AgentEngineBuilder staticTools(List<CuteTool> tools) {
        if (tools != null) {
            this.staticTools.addAll(tools);
        }
        return this;
    }

    public AgentEngineBuilder addGlobalToolProvider(DynamicToolProvider provider) {
        if (provider != null) {
            this.globalToolProviders.add(provider);
        }
        return this;
    }

    public AgentEngineBuilder globalToolProviders(List<DynamicToolProvider> providers) {
        if (providers != null) {
            this.globalToolProviders.addAll(providers);
        }
        return this;
    }

    public AgentEngineBuilder addMessageInterceptor(MessageInterceptor interceptor) {
        if (interceptor != null) {
            this.messageInterceptors.add(interceptor);
        }
        return this;
    }

    public AgentEngineBuilder messageInterceptors(List<MessageInterceptor> interceptors) {
        if (interceptors != null) {
            this.messageInterceptors.addAll(interceptors);
        }
        return this;
    }

    public AgentEngineBuilder llmHttpLogger(LlmHttpLogger logger) {
        this.llmHttpLogger = logger;
        return this;
    }

    public AgentEngineBuilder retryPolicyProvider(RetryPolicyProvider provider) {
        this.retryPolicyProvider = provider;
        return this;
    }

    public AgentEngineBuilder approvalRuleWriter(ApprovalRuleWriter writer) {
        this.approvalRuleWriter = writer;
        return this;
    }

    /**
     * 新会话默认标题（空白值回退内置默认）
     */
    public AgentEngineBuilder defaultConversationTitle(String title) {
        if (title != null && !title.isBlank()) {
            this.defaultConversationTitle = title;
        }
        return this;
    }

    /**
     * 通知层并行车道数（非正值回退内置默认）
     */
    public AgentEngineBuilder notifyLaneCount(int notifyLaneCount) {
        this.notifyLaneCount = notifyLaneCount > 0 ? notifyLaneCount : NotifyExecutor.DEFAULT_LANE_COUNT;
        return this;
    }

    /**
     * 极速模式开关
     */
    public AgentEngineBuilder notifyFastMode(boolean notifyFastMode) {
        this.notifyFastMode = notifyFastMode;
        return this;
    }

    /**
     * 必需供血完整性校验，缺项时快速失败并列出全部缺失项
     */
    public void validate() {
        List<String> missing = new ArrayList<>();
        if (conversationStore == null) {
            missing.add("conversationStore");
        }
        if (messageStore == null) {
            missing.add("messageStore");
        }
        if (providerResolver == null) {
            missing.add("providerResolver");
        }
        if (toolGuard == null) {
            missing.add("toolGuard");
        }
        if (lockProvider == null) {
            missing.add("lockProvider");
        }
        if (executorProvider == null) {
            missing.add("executorProvider");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("AgentEngine 缺少必要供血接口: " + String.join(", ", missing));
        }
    }

    /**
     * 按拓扑序装配引擎对象图并产出不可变门面实例
     */
    public AgentEngine build() {
        validate();

        // 收集期配置在此冻结为不可变快照，后续组件统一经 options 消费
        EngineOptions options = new EngineOptions(
                defaultConversationTitle, notifyLaneCount, notifyFastMode);

        EngineStores stores = new EngineStores(conversationStore, messageStore);
        EngineInfra runtimeInfra = resolveRuntimeInfra(options);

        // 1. 基础设施与基础分发器
        EngineDirectListener directListener = new EngineDirectListener(stores);
        AgentEventDispatcher eventDispatcher = createEventDispatcher(directListener, stores, runtimeInfra, options);
        AgentHookDispatcher hookDispatcher = new AgentHookDispatcher(opt(hookListeners), runtimeInfra.getLocks());
        CuteChatFactory chatFactory = createChatFactory();
        MessageDataReporter messageDataReporter = new MessageDataReporter(messageStore);
        LoopDataReporter loopDataReporter = new LoopDataReporter(stores, messageDataReporter, runtimeInfra);
        ChatOptionsFactory chatOptionsFactory = new ChatOptionsFactory();
        AgentContextManager contextManager = new AgentContextManager(eventDispatcher, hookDispatcher,
                stores, opt(contextInitializers));
        directListener.bindContextManager(contextManager);
        SystemPromptAssembler promptAssembler = createPromptAssembler();

        // 2. 工具注册中心与内置工具
        InvokeSubagentTool invokeSubagentTool = new InvokeSubagentTool(contextManager, runtimeInfra.getExecutor());
        ToolRegistry toolRegistry = createToolRegistry(invokeSubagentTool);

        // 3. 核心循环编排（中游组件、执行引擎、协调器）
        ToolExecutionEngine toolExecutionEngine =
                new ToolExecutionEngine(toolRegistry, toolGuard, messageDataReporter,
                        contextManager, stores, opt(approvalRuleWriter), runtimeInfra);
        AgentLoopProcessor agentLoopProcessor = createAgentLoopProcessor(chatFactory, toolRegistry,
                loopDataReporter, promptAssembler, chatOptionsFactory, messageDataReporter,
                contextManager, toolExecutionEngine, invokeSubagentTool, stores);
        AgentLoopCoordinator agentLoopCoordinator = new AgentLoopCoordinator(agentLoopProcessor,
                contextManager, stores, loopDataReporter, messageDataReporter, runtimeInfra);

        // 两段式回填两处真环边
        toolExecutionEngine.bindAgentLoopCoordinator(agentLoopCoordinator);
        invokeSubagentTool.bindAgentLoopCoordinator(agentLoopCoordinator);

        // 4. 工具注册中心显式初始化（原 @PostConstruct 退役）
        toolRegistry.init();

        // 5. 门面收口与 AgentEngine 返回
        return assembleFacades(contextManager, agentLoopCoordinator, toolExecutionEngine,
                chatFactory, chatOptionsFactory, loopDataReporter, toolRegistry, stores, runtimeInfra, options);
    }

    /**
     * 解析运行期技术设施：锁与执行器来自宿主供血
     */
    private EngineInfra resolveRuntimeInfra(EngineOptions options) {
        return new EngineInfra(lockProvider, executorProvider);
    }

    private static <T> Optional<T> opt(T value) {
        return Optional.ofNullable(value);
    }

    private AgentEventDispatcher createEventDispatcher(EngineDirectListener directListener,
                                                       EngineStores stores, EngineInfra runtimeInfra,
                                                       EngineOptions options) {
        EngineCacheSyncListener cacheSyncListener = new EngineCacheSyncListener(stores);
        EngineNotificationListener notificationListener = new EngineNotificationListener();
        List<AgentEventListener> allEventListeners = new ArrayList<>();
        allEventListeners.add(directListener);
        allEventListeners.add(cacheSyncListener);
        allEventListeners.add(notificationListener);
        allEventListeners.addAll(eventListeners);
        NotifyExecutor notifyExecutor = new NotifyExecutor(options.getNotifyLaneCount());
        return new AgentEventDispatcher(allEventListeners, runtimeInfra, notifyExecutor, options.isNotifyFastMode());
    }

    private CuteChatFactory createChatFactory() {
        return new CuteChatFactory(providerResolver, opt(llmHttpLogger), opt(retryPolicyProvider));
    }

    private SystemPromptAssembler createPromptAssembler() {
        // 引擎零默认提示词内容：系统提示词全部由宿主经 SystemPromptContributor 注入，保持引擎纯框架
        return new SystemPromptAssembler(new ArrayList<>(promptContributors));
    }

    private ToolRegistry createToolRegistry(InvokeSubagentTool invokeSubagentTool) {
        List<CuteTool> allStaticTools = new ArrayList<>(staticTools);
        allStaticTools.add(invokeSubagentTool);
        return new ToolRegistry(allStaticTools, opt(globalToolProviders));
    }

    private AgentLoopProcessor createAgentLoopProcessor(
            CuteChatFactory chatFactory,
            ToolRegistry toolRegistry,
            LoopDataReporter loopDataReporter,
            SystemPromptAssembler promptAssembler,
            ChatOptionsFactory chatOptionsFactory,
            MessageDataReporter messageDataReporter,
            AgentContextManager contextManager,
            ToolExecutionEngine toolExecutionEngine,
            InvokeSubagentTool invokeSubagentTool,
            EngineStores stores) {
        MessageHistoryAligner messageHistoryAligner = new MessageHistoryAligner(
                stores, promptAssembler, chatFactory,
                messageInterceptors, messageDataReporter);
        LlmWindowManager llmWindowManager = new LlmWindowManager(stores, chatFactory,
                chatOptionsFactory, messageHistoryAligner,
                messageDataReporter, loopDataReporter);
        return new AgentLoopProcessor(chatFactory, toolRegistry,
                loopDataReporter, llmWindowManager, chatOptionsFactory,
                toolExecutionEngine, messageDataReporter, messageHistoryAligner);
    }

    private AgentEngine assembleFacades(
            AgentContextManager contextManager,
            AgentLoopCoordinator loopCoordinator,
            ToolExecutionEngine toolExecutionEngine,
            CuteChatFactory chatFactory,
            ChatOptionsFactory chatOptionsFactory,
            LoopDataReporter loopDataReporter,
            ToolRegistry toolRegistry,
            EngineStores stores,
            EngineInfra runtimeInfra,
            EngineOptions options) {
        ChatNamingHelper chatNamingHelper = new ChatNamingHelper(stores, chatFactory, contextManager,
                chatOptionsFactory, runtimeInfra, options.getDefaultConversationTitle());
        loopCoordinator.bindChatNamingHelper(chatNamingHelper);
        LoopRecoveryCoordinator loopRecoveryCoordinator = new LoopRecoveryCoordinator(stores,
                loopDataReporter, contextManager, loopCoordinator, runtimeInfra);

        ContextFacade contextFacade = new ContextFacade(contextManager);
        ConversationFacade conversationFacade = new ConversationFacade(loopDataReporter, contextManager,
                loopCoordinator);
        LoopFacade loopFacade = new LoopFacade(loopCoordinator, loopRecoveryCoordinator);
        ToolFacade toolFacade = new ToolFacade(toolRegistry, toolExecutionEngine);

        return new AgentEngine(contextFacade, loopFacade, conversationFacade, toolFacade,
                conversationStore, messageStore, runtimeInfra.getLocks());
    }
}
