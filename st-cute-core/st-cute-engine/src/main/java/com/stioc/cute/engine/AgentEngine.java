package com.stioc.cute.engine;

import com.stioc.cute.engine.common.EngineExecutor;
import com.stioc.cute.engine.common.EngineLock;
import com.stioc.cute.engine.event.AgentEventDispatcher;
import com.stioc.cute.engine.event.AgentEventListener;
import com.stioc.cute.engine.event.EngineCacheSyncListener;
import com.stioc.cute.engine.event.EngineDirectListener;
import com.stioc.cute.engine.event.EngineNotificationListener;
import com.stioc.cute.engine.facade.ContextFacade;
import com.stioc.cute.engine.facade.ConversationFacade;
import com.stioc.cute.engine.facade.LoopFacade;
import com.stioc.cute.engine.facade.ToolFacade;
import com.stioc.cute.engine.hook.AgentHookDispatcher;
import com.stioc.cute.engine.hook.HookListener;
import com.stioc.cute.engine.llm.ChatOptionsFactory;
import com.stioc.cute.engine.llm.CuteChatFactory;
import com.stioc.cute.engine.llm.LlmHttpLogger;
import com.stioc.cute.engine.llm.ProviderResolver;
import com.stioc.cute.engine.llm.RetryPolicyProvider;
import com.stioc.cute.engine.loop.AgentContextInitializer;
import com.stioc.cute.engine.loop.ApprovalRuleWriter;
import com.stioc.cute.engine.loop.core.AgentContextManager;
import com.stioc.cute.engine.loop.core.AgentLoopCoordinator;
import com.stioc.cute.engine.loop.core.AgentLoopProcessor;
import com.stioc.cute.engine.loop.core.LoopDataReporter;
import com.stioc.cute.engine.loop.core.LoopRecoveryCoordinator;
import com.stioc.cute.engine.support.ChatNamingHelper;
import com.stioc.cute.engine.loop.message.LlmWindowManager;
import com.stioc.cute.engine.loop.message.MessageDataReporter;
import com.stioc.cute.engine.loop.message.MessageHistoryAligner;
import com.stioc.cute.engine.loop.message.MessageInterceptor;
import com.stioc.cute.engine.prompt.SystemPromptAssembler;
import com.stioc.cute.engine.prompt.SystemPromptContributor;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.DynamicToolProvider;
import com.stioc.cute.engine.tool.ToolExecutionEngine;
import com.stioc.cute.engine.tool.ToolGuard;
import com.stioc.cute.engine.tool.ToolRegistry;
import com.stioc.cute.engine.tool.builtin.InvokeSubagentTool;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ReAct 纯 Java 智能体引擎根对象（线程安全单例，不可变总门面）。
 * <p>
 * 纯 Java 构建，无任何 Spring/IoC 容器依赖；供血全部经由构造器或 Builder 显式显入。
 * 外部宿主统一通过本类暴露的 4 个门面领域进行业务交互：
 * <ul>
 *   <li>{@link #getContextFacade()}：会话上下文管理、取消/中断与快照</li>
 *   <li>{@link #getLoopFacade()}：ReAct 循环触发（同步/异步/强停重置）与会话智能命名</li>
 *   <li>{@link #getConversationFacade()}：循环运行态数据屏障与子代理汇报联动</li>
 *   <li>{@link #getToolFacade()}：工具注册中心查阅与人在回路审批决策</li>
 * </ul>
 * 另暴露两个存储供血契约直取出口（{@link #getConversationStore()} / {@link #getMessageStore()}），
 * 宿主业务统一经引擎获取存储，无特殊情况不要自行注入 Mapper 裸写存储路径。
 * 以及引擎锁供血契约直取出口（{@link #getEngineLock()}）：宿主存储层等需要与引擎
 * 共享同源锁的场合，统一经引擎取锁，禁止绕过引擎直接持有锁实现的静态引用。
 * </p>
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AgentEngine {

    private final ContextFacade contextFacade;
    private final LoopFacade loopFacade;
    private final ConversationFacade conversationFacade;
    private final ToolFacade toolFacade;
    private final ConversationStore conversationStore;
    private final MessageStore messageStore;
    private final EngineLock engineLock;

    public static Builder builder() {
        return new Builder();
    }

    /**
     * AgentEngine 流式装配构建器。
     */
    public static final class Builder {

        // 必需供血接口（校验不可为空）
        private ConversationStore conversationStore;
        private MessageStore messageStore;
        private ProviderResolver providerResolver;
        private ToolGuard toolGuard;
        private EngineLock lockProvider;
        private EngineExecutor executorProvider;

        // 可选供血接口 / 插件扩展（支持多值或可选单个）
        private final List<AgentEventListener> eventListeners = new ArrayList<>();
        private final List<HookListener> hookListeners = new ArrayList<>();
        private final List<AgentContextInitializer> contextInitializers = new ArrayList<>();
        private final List<SystemPromptContributor> promptContributors = new ArrayList<>();
        private final List<CuteTool> staticTools = new ArrayList<>();
        private final List<DynamicToolProvider> globalToolProviders = new ArrayList<>();
        private final List<MessageInterceptor> messageInterceptors = new ArrayList<>();

        private LlmHttpLogger llmHttpLogger;
        private RetryPolicyProvider retryPolicyProvider;
        private ApprovalRuleWriter approvalRuleWriter;
        private String defaultConversationTitle = "新对话";

        private Builder() {}

        public Builder conversationStore(ConversationStore store) {
            this.conversationStore = store;
            return this;
        }

        public Builder messageStore(MessageStore store) {
            this.messageStore = store;
            return this;
        }

        public Builder providerResolver(ProviderResolver resolver) {
            this.providerResolver = resolver;
            return this;
        }

        public Builder toolGuard(ToolGuard guard) {
            this.toolGuard = guard;
            return this;
        }

        public Builder lockProvider(EngineLock provider) {
            this.lockProvider = provider;
            return this;
        }

        public Builder executorProvider(EngineExecutor provider) {
            this.executorProvider = provider;
            return this;
        }

        public Builder addEventListener(AgentEventListener listener) {
            if (listener != null) {
                this.eventListeners.add(listener);
            }
            return this;
        }

        public Builder eventListeners(List<AgentEventListener> listeners) {
            if (listeners != null) {
                this.eventListeners.addAll(listeners);
            }
            return this;
        }

        public Builder addHookListener(HookListener listener) {
            if (listener != null) {
                this.hookListeners.add(listener);
            }
            return this;
        }

        public Builder hookListeners(List<HookListener> listeners) {
            if (listeners != null) {
                this.hookListeners.addAll(listeners);
            }
            return this;
        }

        public Builder addContextInitializer(AgentContextInitializer initializer) {
            if (initializer != null) {
                this.contextInitializers.add(initializer);
            }
            return this;
        }

        public Builder contextInitializers(List<AgentContextInitializer> initializers) {
            if (initializers != null) {
                this.contextInitializers.addAll(initializers);
            }
            return this;
        }

        public Builder addPromptContributor(SystemPromptContributor contributor) {
            if (contributor != null) {
                this.promptContributors.add(contributor);
            }
            return this;
        }

        public Builder promptContributors(List<SystemPromptContributor> contributors) {
            if (contributors != null) {
                this.promptContributors.addAll(contributors);
            }
            return this;
        }

        public Builder addStaticTool(CuteTool tool) {
            if (tool != null) {
                this.staticTools.add(tool);
            }
            return this;
        }

        public Builder staticTools(List<CuteTool> tools) {
            if (tools != null) {
                this.staticTools.addAll(tools);
            }
            return this;
        }

        public Builder addGlobalToolProvider(DynamicToolProvider provider) {
            if (provider != null) {
                this.globalToolProviders.add(provider);
            }
            return this;
        }

        public Builder globalToolProviders(List<DynamicToolProvider> providers) {
            if (providers != null) {
                this.globalToolProviders.addAll(providers);
            }
            return this;
        }

        public Builder addMessageInterceptor(MessageInterceptor interceptor) {
            if (interceptor != null) {
                this.messageInterceptors.add(interceptor);
            }
            return this;
        }

        public Builder messageInterceptors(List<MessageInterceptor> interceptors) {
            if (interceptors != null) {
                this.messageInterceptors.addAll(interceptors);
            }
            return this;
        }

        public Builder llmHttpLogger(LlmHttpLogger logger) {
            this.llmHttpLogger = logger;
            return this;
        }

        public Builder retryPolicyProvider(RetryPolicyProvider provider) {
            this.retryPolicyProvider = provider;
            return this;
        }

        public Builder approvalRuleWriter(ApprovalRuleWriter writer) {
            this.approvalRuleWriter = writer;
            return this;
        }

        public Builder defaultConversationTitle(String title) {
            if (title != null && !title.isBlank()) {
                this.defaultConversationTitle = title;
            }
            return this;
        }

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

        public AgentEngine build() {
            validate();

            // 1. 基础设施与基础分发器
            EngineDirectListener directListener = new EngineDirectListener(conversationStore, messageStore);
            AgentEventDispatcher eventDispatcher = createEventDispatcher(directListener);
            AgentHookDispatcher hookDispatcher = new AgentHookDispatcher(opt(hookListeners), lockProvider);
            CuteChatFactory chatFactory = createChatFactory();
            MessageDataReporter messageDataReporter = new MessageDataReporter(messageStore);
            LoopDataReporter loopDataReporter = new LoopDataReporter(conversationStore, messageStore, messageDataReporter, lockProvider);
            ChatOptionsFactory chatOptionsFactory = new ChatOptionsFactory();
            AgentContextManager contextManager = new AgentContextManager(eventDispatcher, hookDispatcher,
                    conversationStore, messageStore, opt(contextInitializers));
            directListener.bindContextManager(contextManager);
            SystemPromptAssembler promptAssembler = createPromptAssembler();

            // 2. 工具注册中心与内置工具
            InvokeSubagentTool invokeSubagentTool = new InvokeSubagentTool(contextManager, executorProvider);
            ToolRegistry toolRegistry = createToolRegistry(invokeSubagentTool);

            // 3. 核心循环编排（中游组件、执行引擎、协调器与真环回填）
            LoopPipeline loopPipeline = assembleLoopPipeline(chatFactory, toolRegistry, loopDataReporter,
                    promptAssembler, chatOptionsFactory, messageDataReporter, contextManager, invokeSubagentTool);

            // 4. 工具注册中心显式初始化（原 @PostConstruct 退役）
            toolRegistry.init();

            // 5. 门面收口与 AgentEngine 返回
            return assembleFacades(contextManager, loopPipeline.coordinator(), loopPipeline.toolExecutionEngine(),
                    chatFactory, chatOptionsFactory, loopDataReporter, toolRegistry, lockProvider);
        }

        private static <T> Optional<T> opt(T value) {
            return Optional.ofNullable(value);
        }

        private AgentEventDispatcher createEventDispatcher(EngineDirectListener directListener) {
            EngineCacheSyncListener cacheSyncListener = new EngineCacheSyncListener(conversationStore);
            EngineNotificationListener notificationListener = new EngineNotificationListener();
            List<AgentEventListener> allEventListeners = new ArrayList<>();
            allEventListeners.add(directListener);
            allEventListeners.add(cacheSyncListener);
            allEventListeners.add(notificationListener);
            allEventListeners.addAll(eventListeners);
            return new AgentEventDispatcher(allEventListeners, lockProvider);
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

        private record LoopPipeline(AgentLoopCoordinator coordinator, ToolExecutionEngine toolExecutionEngine) {}

        private LoopPipeline assembleLoopPipeline(
                CuteChatFactory chatFactory,
                ToolRegistry toolRegistry,
                LoopDataReporter loopDataReporter,
                SystemPromptAssembler promptAssembler,
                ChatOptionsFactory chatOptionsFactory,
                MessageDataReporter messageDataReporter,
                AgentContextManager contextManager,
                InvokeSubagentTool invokeSubagentTool) {
            MessageHistoryAligner messageHistoryAligner = new MessageHistoryAligner(
                    messageStore, promptAssembler, chatFactory,
                    messageInterceptors, messageDataReporter);
            LlmWindowManager llmWindowManager = new LlmWindowManager(messageStore, chatFactory,
                    chatOptionsFactory, messageHistoryAligner,
                    messageDataReporter, loopDataReporter);

            ToolExecutionEngine toolExecutionEngine =
                    new ToolExecutionEngine(toolRegistry, toolGuard, messageDataReporter,
                            contextManager, messageStore, opt(approvalRuleWriter), lockProvider, executorProvider);
            AgentLoopProcessor agentLoopProcessor = new AgentLoopProcessor(chatFactory, toolRegistry,
                    loopDataReporter, llmWindowManager, chatOptionsFactory,
                    toolExecutionEngine, messageDataReporter, messageHistoryAligner);
            AgentLoopCoordinator agentLoopCoordinator = new AgentLoopCoordinator(agentLoopProcessor,
                    contextManager, conversationStore, loopDataReporter, messageStore,
                    messageDataReporter, lockProvider, executorProvider);

            // 两段式回填两处真环边
            toolExecutionEngine.bindAgentLoopCoordinator(agentLoopCoordinator);
            invokeSubagentTool.bindAgentLoopCoordinator(agentLoopCoordinator);

            return new LoopPipeline(agentLoopCoordinator, toolExecutionEngine);
        }

        private AgentEngine assembleFacades(
                AgentContextManager contextManager,
                AgentLoopCoordinator loopCoordinator,
                ToolExecutionEngine toolExecutionEngine,
                CuteChatFactory chatFactory,
                ChatOptionsFactory chatOptionsFactory,
                LoopDataReporter loopDataReporter,
                ToolRegistry toolRegistry,
                EngineLock lockProvider) {
            ChatNamingHelper chatNamingHelper = new ChatNamingHelper(conversationStore, messageStore,
                    chatFactory, contextManager, chatOptionsFactory, lockProvider, defaultConversationTitle);
            loopCoordinator.bindChatNamingHelper(chatNamingHelper);
            LoopRecoveryCoordinator loopRecoveryCoordinator = new LoopRecoveryCoordinator(conversationStore,
                    messageStore, loopDataReporter, contextManager, loopCoordinator, lockProvider);

            ContextFacade contextFacade = new ContextFacade(contextManager);
            ConversationFacade conversationFacade = new ConversationFacade(loopDataReporter, contextManager,
                    loopCoordinator);
            LoopFacade loopFacade = new LoopFacade(loopCoordinator, loopRecoveryCoordinator);
            ToolFacade toolFacade = new ToolFacade(toolRegistry, toolExecutionEngine);

            return new AgentEngine(contextFacade, loopFacade, conversationFacade, toolFacade,
                    conversationStore, messageStore, lockProvider);
        }
    }
}
