package com.stioc.cute.engine.loop.message;

import com.stioc.cute.engine.assembly.EngineStores;
import com.stioc.cute.engine.llm.ChatOptionsFactory;
import com.stioc.cute.engine.llm.types.CuteMessage;
import com.stioc.cute.engine.llm.types.CuteMessageRole;
import com.stioc.cute.engine.llm.types.CuteToolCall;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.testkit.EngineFixture;
import com.stioc.cute.engine.testkit.EngineStubs;
import com.stioc.cute.engine.testkit.EngineTestHarness;
import com.stioc.cute.engine.testkit.FakeLlmServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文窗口管理与历史消息对齐测试。
 * <p>
 * 历史还原是每次 LLM 调用的输入来源，出错会直接表现为「模型看不见某条消息」或
 * 「重复发送已取消的内容」——这类问题在界面上极难察觉，只能靠对还原结果的精确断言兜住。
 * Token 估算与裁剪阈值则决定会不会在长对话中突然爆窗。
 * </p>
 * <p>
 * 本类只覆盖可直接实例化的纯逻辑组件（Token 估算 / 历史还原 / 插槽式裁剪判定）。
 * 完整压缩链路（含真实压缩调用与归档落库）在 {@code ReActLoopStateMachineTest} 中以闭环方式覆盖。
 * </p>
 */
class LlmWindowManagerTest {

    /**
     * Token 估算：空文本为 0，长文本应显著大于短文本（jtokkit 可用且未走 fallback）
     */
    @Test
    void estimateTokensBehavesMonotonically() {
        LlmWindowManager manager = newManager();

        assertEquals(0, manager.estimateTokens(null));
        assertEquals(0, manager.estimateTokens(""));

        long shortTokens = manager.estimateTokens("你好");
        long longTokens = manager.estimateTokens("你好，这是一个明显更长的句子，用于验证 token 估算的单调性。");

        assertTrue(shortTokens > 0, "非空文本的 token 数应大于 0");
        assertTrue(longTokens > shortTokens, "更长文本的 token 估算应更大");
    }

    /**
     * 批量 Token 计算应为各条消息之和
     */
    @Test
    void calculatesMessageTokensAsSumOfMessages() {
        LlmWindowManager manager = newManager();

        List<CuteMessage> messages = List.of(
                CuteMessage.builder().role(CuteMessageRole.USER).content("第一段").build(),
                CuteMessage.builder().role(CuteMessageRole.ASSISTANT).content("第二段内容").build());

        long total = manager.calculateMessageTokens(messages);
        assertEquals(manager.estimateTokens("第一段") + manager.estimateTokens("第二段内容"), total);
        assertEquals(0, manager.calculateMessageTokens(List.of()));
    }

    /**
     * 历史还原：SYSTEM 提示词恒定置于首条，TOOL 消息按状态渲染为对应错误占位
     */
    @Test
    void rebuildsHistoryWithSystemFirstAndStatusAwareToolPlaceholders() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            long cid = fixture.cid();
            var store = fixture.messages();

            var user = Message.builder().cid(cid).role(MessageRole.USER).content("用户提问")
                    .status(MessageStatus.SUCCESS).build();
            store.insert(user);

            var assistant = Message.builder().cid(cid).role(MessageRole.ASSISTANT).content("助手回答")
                    .status(MessageStatus.SUCCESS)
                    .toolCalls("[{\"id\":\"c1\",\"name\":\"read_file\",\"arguments\":\"{}\"}]")
                    .build();
            store.insert(assistant);

            // 三种非成功状态的工具消息，应分别渲染为不同占位文案
            store.insert(Message.builder().cid(cid).role(MessageRole.TOOL).content("")
                    .status(MessageStatus.REJECTED).callId("c1")
                    .toolCalls("{\"id\":\"c1\",\"name\":\"read_file\",\"arguments\":\"{}\"}").build());
            store.insert(Message.builder().cid(cid).role(MessageRole.TOOL).content("")
                    .status(MessageStatus.WAITING_APPROVAL).callId("c2")
                    .toolCalls("{\"id\":\"c2\",\"name\":\"write_file\",\"arguments\":\"{}\"}").build());

            AgentContext context = fixtureContext(cid);
            MessageHistoryAligner aligner = new MessageHistoryAligner(
                    new EngineStores(null, fixture.engine().getMessageStore()),
                    new com.stioc.cute.engine.prompt.SystemPromptAssembler(new ArrayList<>()),
                    newTestChatFactory(fixture),
                    List.of(),
                    new MessageDataReporter(fixture.engine().getMessageStore()));

            List<CuteMessage> history = aligner.rebuildHistory(context);

            assertEquals(CuteMessageRole.SYSTEM, history.get(0).getRole(),
                    "历史首条必须是由引擎合成的系统提示词");
            assertEquals(CuteMessageRole.USER, history.get(1).getRole());

            CuteMessage rejected = history.stream()
                    .filter(m -> m.getRole() == CuteMessageRole.TOOL && "c1".equals(m.getToolCallId()))
                    .findFirst().orElse(null);
            assertNotNull(rejected, "拒批的工具消息应在历史中占位");
            assertTrue(rejected.getContent().contains("Permission denied"),
                    "拒批工具结果应渲染为拒绝占位，实际: " + rejected.getContent());

            CuteMessage waiting = history.stream()
                    .filter(m -> m.getRole() == CuteMessageRole.TOOL && "c2".equals(m.getToolCallId()))
                    .findFirst().orElse(null);
            assertNotNull(waiting);
            assertTrue(waiting.getContent().contains("WAITING_APPROVAL"),
                    "待审批工具结果应渲染为待审批占位，实际: " + waiting.getContent());
        }
    }

    /**
     * 历史还原：ALLOW 工具角色的 SYSTEM 消息与 CANCELED 的 USER/ASSISTANT 消息都应被排除
     */
    @Test
    void excludesSystemAndCanceledMessagesFromHistory() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            long cid = fixture.cid();
            var store = fixture.messages();

            store.insert(Message.builder().cid(cid).role(MessageRole.USER).content("正常提问")
                    .status(MessageStatus.SUCCESS).build());
            store.insert(Message.builder().cid(cid).role(MessageRole.USER).content("已取消的提问")
                    .status(MessageStatus.CANCELED).build());
            store.insert(Message.builder().cid(cid).role(MessageRole.ASSISTANT).content("已失败的回答")
                    .status(MessageStatus.FAILED).build());
            store.insert(Message.builder().cid(cid).role(MessageRole.USER).content("库中残留的系统消息")
                    .status(MessageStatus.SUCCESS).build());

            AgentContext context = fixtureContext(cid);
            MessageHistoryAligner aligner = new MessageHistoryAligner(
                    new EngineStores(null, fixture.engine().getMessageStore()),
                    new com.stioc.cute.engine.prompt.SystemPromptAssembler(new ArrayList<>()),
                    newTestChatFactory(fixture),
                    List.of(),
                    new MessageDataReporter(fixture.engine().getMessageStore()));

            List<CuteMessage> history = aligner.rebuildHistory(context);

            boolean containsCanceledUser = history.stream()
                    .anyMatch(m -> "已取消的提问".equals(m.getContent()));
            assertFalse(containsCanceledUser, "被取消的用户消息不应进入模型历史");

            boolean containsFailedAssistant = history.stream()
                    .anyMatch(m -> "已失败的回答".equals(m.getContent()));
            assertFalse(containsFailedAssistant, "失败的助手消息不应进入模型历史");

            assertTrue(history.stream().anyMatch(m -> "正常提问".equals(m.getContent())),
                    "正常用户消息必须进入历史");
        }
    }

    /**
     * 子 Agent 汇报（BRANCH）应以 USER 角色进入历史，并带来源前缀
     */
    @Test
    void rendersBranchReportAsUserMessageWithPrefix() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            long cid = fixture.cid();
            fixture.messages().insert(Message.builder().cid(cid).role(MessageRole.BRANCH)
                    .content("子任务已完成")
                    .status(MessageStatus.SUCCESS).build());

            AgentContext context = fixtureContext(cid);
            MessageHistoryAligner aligner = new MessageHistoryAligner(
                    new EngineStores(null, fixture.engine().getMessageStore()),
                    new com.stioc.cute.engine.prompt.SystemPromptAssembler(new ArrayList<>()),
                    newTestChatFactory(fixture),
                    List.of(),
                    new MessageDataReporter(fixture.engine().getMessageStore()));

            List<CuteMessage> history = aligner.rebuildHistory(context);

            CuteMessage branch = history.stream()
                    .filter(m -> m.getContent() != null && m.getContent().contains("子任务已完成"))
                    .findFirst().orElse(null);
            assertNotNull(branch, "子代理汇报必须进入历史（否则父代理收不到结论）");
            assertEquals(CuteMessageRole.USER, branch.getRole(), "子代理汇报应以 USER 角色发送");
            assertTrue(branch.getContent().startsWith("来自其他Agent："),
                    "子代理汇报应带来源前缀，实际: " + branch.getContent());
        }
    }

    /**
     * 压缩摘要（COMPRESSED）应前置到历史中较早的位置（先于普通消息）
     */
    @Test
    void placesCompressedSummaryBeforeOtherMessages() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            long cid = fixture.cid();
            fixture.messages().insert(Message.builder().cid(cid).role(MessageRole.USER)
                    .content("常规历史消息").status(MessageStatus.SUCCESS).build());
            fixture.messages().insert(Message.builder().cid(cid).role(MessageRole.COMPRESSED)
                    .content("[System Memory Summary]: 之前的对话摘要")
                    .status(MessageStatus.SUCCESS).build());

            AgentContext context = fixtureContext(cid);
            MessageHistoryAligner aligner = new MessageHistoryAligner(
                    new EngineStores(null, fixture.engine().getMessageStore()),
                    new com.stioc.cute.engine.prompt.SystemPromptAssembler(new ArrayList<>()),
                    newTestChatFactory(fixture),
                    List.of(),
                    new MessageDataReporter(fixture.engine().getMessageStore()));

            List<CuteMessage> history = aligner.rebuildHistory(context);

            int summaryIndex = -1;
            int normalIndex = -1;
            for (int i = 0; i < history.size(); i++) {
                String content = history.get(i).getContent();
                if (content != null && content.contains("System Memory Summary")) {
                    summaryIndex = i;
                }
                if ("常规历史消息".equals(content)) {
                    normalIndex = i;
                }
            }
            assertTrue(summaryIndex > 0, "压缩摘要应进入历史");
            assertTrue(normalIndex > summaryIndex,
                    "压缩摘要必须前置到常规历史消息之前（顺序：系统提示词 → 摘要 → 历史）");
        }
    }

    /**
     * Token 预估的「真实用量缓存」分支：历史里已有 ASSISTANT 且上下文缓存了上轮真实用量时，
     * ASSISTANT 及其之前的历史直接采用真实值，只有尾部新增消息走估算
     */
    @Test
    void reusesRealUsageCacheForHistoryBeforeLastAssistant() {
        LlmWindowManager manager = newManager();

        List<CuteMessage> messages = List.of(
                CuteMessage.builder().role(CuteMessageRole.USER)
                        .content("一段会被真实用量覆盖的长文本".repeat(20)).build(),
                CuteMessage.builder().role(CuteMessageRole.ASSISTANT).content("助手回答").build());

        AgentContext context = EngineStubs.agentContext(1001L);
        context.setInputTokens(1000L);
        context.setOutputTokens(500L);

        long tokens = manager.calculateMessageTokens(context, messages);

        assertEquals(1500L, tokens,
                "存在 ASSISTANT 且缓存了真实用量时，应用 input+output 真实值而非逐条估算");
    }

    /**
     * 尾部新增消息（ASSISTANT 之后的 TOOL/USER 等）必须叠加估算值，不能漏计
     */
    @Test
    void estimatesOnlyTailMessagesAfterLastAssistant() {
        LlmWindowManager manager = newManager();

        List<CuteMessage> messages = List.of(
                CuteMessage.builder().role(CuteMessageRole.ASSISTANT).content("助手回答").build(),
                CuteMessage.builder().role(CuteMessageRole.TOOL).content("工具返回的尾部内容").build());

        AgentContext context = EngineStubs.agentContext(1001L);
        context.setInputTokens(800L);
        context.setOutputTokens(200L);

        long tailTokens = manager.estimateTokens("工具返回的尾部内容");
        assertEquals(1000L + tailTokens, manager.calculateMessageTokens(context, messages),
                "ASSISTANT 之前的真实用量 + 尾部新增消息的估算值，二者必须都计入");
    }

    /**
     * 没有真实用量缓存（inputTokens 为 0）或历史无 ASSISTANT 时必须回落到纯估算，不得误用 0
     */
    @Test
    void fallsBackToEstimationWithoutUsageCacheOrAssistant() {
        LlmWindowManager manager = newManager();

        List<CuteMessage> messages = List.of(
                CuteMessage.builder().role(CuteMessageRole.USER).content("用户提问").build(),
                CuteMessage.builder().role(CuteMessageRole.ASSISTANT).content("助手回答").build());
        long estimated = manager.calculateMessageTokens(messages);

        // 分支 1：上下文存在但从未记录过真实用量（inputTokens=0）→ 必须回落估算
        AgentContext noUsage = EngineStubs.agentContext(1001L);
        assertEquals(estimated, manager.calculateMessageTokens(noUsage, messages),
                "无真实用量缓存时应回落到纯估算，绝不能按 0 计算导致窗口管理失真");

        // 分支 2：历史中没有任何 ASSISTANT 消息 → 必须回落估算
        AgentContext withUsage = EngineStubs.agentContext(1001L);
        withUsage.setInputTokens(999L);
        withUsage.setOutputTokens(999L);
        List<CuteMessage> userOnly = List.of(
                CuteMessage.builder().role(CuteMessageRole.USER).content("只有用户消息").build());
        assertEquals(manager.calculateMessageTokens(userOnly), manager.calculateMessageTokens(withUsage, userOnly),
                "历史无 ASSISTANT 时无可对齐的真实用量锚点，必须回落估算");

        // 边界：空列表与 null 上下文
        assertEquals(0, manager.calculateMessageTokens(EngineStubs.agentContext(1001L), List.of()));
        assertEquals(manager.calculateMessageTokens(messages), manager.calculateMessageTokens(null, messages));
    }

    /**
     * 构建一个挂在真实引擎存储上的上下文（cid 与夹具一致）
     */
    private static AgentContext fixtureContext(long cid) {
        return EngineStubs.agentContext(cid);
    }

    /**
     * 由夹具的 ProviderResolver 组出真实 CuteChatFactory（历史还原需要读取多模态开关等配置语义）
     */
    private static com.stioc.cute.engine.llm.CuteChatFactory newTestChatFactory(EngineFixture fixture) {
        return new com.stioc.cute.engine.llm.CuteChatFactory(
                context -> fixture.provider(), java.util.Optional.empty(), java.util.Optional.empty());
    }

    private static LlmWindowManager newManager() {
        return new LlmWindowManager(new EngineStores(null, null), null, new ChatOptionsFactory(), null, null, null);
    }
}
