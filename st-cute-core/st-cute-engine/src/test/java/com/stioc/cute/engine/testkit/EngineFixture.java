package com.stioc.cute.engine.testkit;

import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.event.AgentEventListener;
import com.stioc.cute.engine.hook.HookListener;
import com.stioc.cute.engine.llm.ProviderResolver;
import com.stioc.cute.engine.llm.RetryPolicyProvider;
import com.stioc.cute.engine.llm.types.Provider;
import com.stioc.cute.engine.llm.types.ProviderProtocol;
import com.stioc.cute.engine.loop.ApprovalRuleWriter;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.tool.CuteTool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 引擎闭环测试夹具：一次性装配「真实引擎 + 内存存储 + 假 LLM 服务端 + 可编程守卫」，让 ReAct 全链路离线跑通。
 * <p>
 * 装配原则：<b>除 LLM 出口外一切皆为真品</b>。事件链、内存回填、等待屏障、状态机、工具执行引擎全部是
 * 生产实现，只有三处替身——存储（{@link InMemoryConversationStore} / {@link InMemoryMessageStore}，
 * 语义逐条对齐宿主 MyBatis-Flex 实现）、LLM 出口（{@link FakeLlmServer} 真实走 HTTP/SSE）与权限守卫
 * （{@link ScriptedToolGuard}，宿主任意编排裁决）。这样测到的是真正的时序语义，而不是被 mock 抹平后的假绿。
 * </p>
 * <p>
 * 两处刻意的默认配置，均有明确理由：
 * <ul>
 *   <li><b>重试策略默认 0 次</b>：引擎默认兜底是「重试 3 次、每次间隔 5 秒」，闭环用例若误触失败路径会白等 15 秒。
 *       需要验证重试语义的用例请显式传入自己的策略；</li>
 *   <li><b>默认标题不等于会话初始标题</b>：{@code ChatNamingHelper} 只对「仍为初始标题」的会话发起自动命名，
 *       而自动命名会额外打一次 LLM 并产生一条 USER 记录，将污染对 LLM 请求次数与历史消息的断言。
 *       本夹具把初始标题设为「已命名会话」、种子会话标题设为「闭环测试会话」，从而天然跳过自动命名。</li>
 * </ul>
 * </p>
 */
public final class EngineFixture implements AutoCloseable {

    /**
     * 会话初始标题：与种子会话标题刻意不同，用于跳过自动命名
     */
    private static final String DEFAULT_TITLE = "已命名会话";

    private final FakeLlmServer server;
    private final InMemoryConversationStore conversationStore;
    private final InMemoryMessageStore messageStore;
    private final ScriptedToolGuard toolGuard;
    private final Provider provider;
    private final AgentEngine engine;
    private final long cid;
    private final String workspaceId;

    private EngineFixture(FakeLlmServer server,
                          InMemoryConversationStore conversationStore,
                          InMemoryMessageStore messageStore,
                          ScriptedToolGuard toolGuard,
                          Provider provider,
                          AgentEngine engine,
                          long cid,
                          String workspaceId) {
        this.server = server;
        this.conversationStore = conversationStore;
        this.messageStore = messageStore;
        this.toolGuard = toolGuard;
        this.provider = provider;
        this.engine = engine;
        this.cid = cid;
        this.workspaceId = workspaceId;
    }

    /**
     * 以默认协议（OPENAI）装配夹具
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 夹具流式构建器
     */
    public static final class Builder {

        private ProviderProtocol protocol = ProviderProtocol.OPENAI;
        private final List<CuteTool> tools = new ArrayList<>();
        private final List<HookListener> hookListeners = new ArrayList<>();
        private final List<AgentEventListener> eventListeners = new ArrayList<>();
        private RetryPolicyProvider retryPolicyProvider = noRetry();
        private ApprovalRuleWriter approvalRuleWriter;
        private String workspaceId = "test-workspace";
        private long cid = 1001L;
        private int contextSize = 200_000;

        private Builder() {
        }

        public Builder protocol(ProviderProtocol protocol) {
            this.protocol = protocol;
            return this;
        }

        /**
         * 上下文窗口大小（token）：压缩触发门限为窗口的 85%，压小即可稳定触发压缩链路
         */
        public Builder contextSize(int contextSize) {
            this.contextSize = contextSize;
            return this;
        }

        public Builder tool(CuteTool tool) {
            if (tool != null) {
                this.tools.add(tool);
            }
            return this;
        }

        public Builder tools(List<CuteTool> tools) {
            if (tools != null) {
                this.tools.addAll(tools);
            }
            return this;
        }

        public Builder hookListener(HookListener listener) {
            if (listener != null) {
                this.hookListeners.add(listener);
            }
            return this;
        }

        public Builder eventListener(AgentEventListener listener) {
            if (listener != null) {
                this.eventListeners.add(listener);
            }
            return this;
        }

        public Builder retryPolicyProvider(RetryPolicyProvider provider) {
            this.retryPolicyProvider = provider;
            return this;
        }

        /**
         * 审批授信规则写入器：验证「总是放行」链路是否真的回调宿主落盘
         */
        public Builder approvalRuleWriter(ApprovalRuleWriter writer) {
            this.approvalRuleWriter = writer;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        public Builder cid(long cid) {
            this.cid = cid;
            return this;
        }

        public EngineFixture build() throws IOException {
            FakeLlmServer server = FakeLlmServer.start(protocol);
            Provider provider = server.providerBuilder()
                    .contextSize(contextSize)
                    .build();
            InMemoryConversationStore conversationStore = new InMemoryConversationStore();
            InMemoryMessageStore messageStore = new InMemoryMessageStore();
            ScriptedToolGuard toolGuard = new ScriptedToolGuard();

            ProviderResolver resolver = context -> provider;
            AgentEngine.Builder engineBuilder = AgentEngine.builder()
                    .conversationStore(conversationStore)
                    .messageStore(messageStore)
                    .providerResolver(resolver)
                    .toolGuard(toolGuard)
                    .lockProvider(EngineStubs.engineLock())
                    .executorProvider(EngineStubs.engineExecutor())
                    .staticTools(tools)
                    .hookListeners(hookListeners)
                    .eventListeners(eventListeners)
                    .retryPolicyProvider(retryPolicyProvider)
                    .approvalRuleWriter(approvalRuleWriter)
                    .defaultConversationTitle(DEFAULT_TITLE);
            AgentEngine engine = engineBuilder.build();

            EngineFixture fixture = new EngineFixture(server, conversationStore, messageStore,
                    toolGuard, provider, engine, seedConversation(engine, provider, workspaceId), workspaceId);
            return fixture;
        }

        /**
         * 预置一条会话行并返回其真实主键。
         * <p>
         * 注意：宿主主键策略为自增（{@code @Id(keyType = Auto)}），插入时会<b>丢弃调用方传入的 id</b>
         * 并重新分配，故会话真实 cid 必须取自插入后的回填值，不能沿用预想值——否则后续按 cid 查库全落空。
         * 这里刻意走引擎的 CONVERSATION_CREATE 事件链创建（而非直插 Store），
         * 顺带验证了「事件链落库并回填自增 id」这一引擎强依赖的行为。
         * </p>
         */
        private static long seedConversation(AgentEngine engine, Provider provider, String workspaceId) {
            Conversation conversation = Conversation.builder()
                    .title("闭环测试会话")
                    .workspaceId(workspaceId)
                    .providerGroup(provider.getGroup())
                    .providerModelName(provider.getModelName())
                    .permissionMode("ALL_ALLOW")
                    .loopRunning(0)
                    .loopCount(0)
                    .build();
            engine.getConversationFacade().createConversation(conversation);
            return conversation.getId();
        }

        /**
         * 零重试策略：避免误触失败路径时白等间隔（需要重试语义的用例请自行注入）
         */
        private static RetryPolicyProvider noRetry() {
            return new RetryPolicyProvider() {
                @Override
                public int getRetryCount() {
                    return 0;
                }

                @Override
                public int getRetryIntervalSec() {
                    return 0;
                }
            };
        }
    }

    /**
     * 提交用户消息并启动 ReAct 循环
     */
    public void submit(String text) {
        engine.getLoopFacade().submitUserMessage(cid, text, null);
    }

    /**
     * 等待假服务端收到第 n 个请求（用于在循环推进的精确时点施加操作）
     */
    public void awaitLlmRequest(int count) {
        EngineTestHarness.awaitCondition(() -> server.requestCount() >= count,
                "假 LLM 服务端收到第 " + count + " 个请求");
    }

    /**
     * 提交用户消息后阻塞等待循环彻底收尾（loopRunning=0 且等待屏障全空）
     */
    public void submitAndAwait(String text) {
        int baseline = EngineTestHarness.allMessages(engine, cid).size();
        submit(text);
        EngineTestHarness.awaitLoopIdle(engine, cid, baseline);
    }

    /**
     * 等待循环彻底收尾（供已异步提交的场景复用）
     */
    public void awaitIdle() {
        EngineTestHarness.awaitLoopIdle(engine, cid);
    }

    public AgentEngine engine() {
        return engine;
    }

    /**
     * 假 LLM 服务端（编排台词与断言请求）
     */
    public FakeLlmServer llm() {
        return server;
    }

    public InMemoryConversationStore conversations() {
        return conversationStore;
    }

    public InMemoryMessageStore messages() {
        return messageStore;
    }

    public ScriptedToolGuard guard() {
        return toolGuard;
    }

    public Provider provider() {
        return provider;
    }

    public long cid() {
        return cid;
    }

    @Override
    public void close() {
        server.close();
    }
}
