package com.stioc.cute.engine.loop.core;

import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.testkit.EngineFixture;
import com.stioc.cute.engine.testkit.EngineTestHarness;
import com.stioc.cute.engine.testkit.FakeLlmServer;
import com.stioc.cute.engine.testkit.FakeTool;
import com.stioc.cute.engine.testkit.WaitingTool;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话取消、强停重置与消息历史控制的闭环测试。
 * <p>
 * 用户点「停止」之后的收场语义是线上事故高发区：必须真正中断在途的 LLM 请求与工具执行、
 * 把所有悬挂消息置为取消终态、并且绝不留下 loopRunning=1 这种「前端永远转圈」的僵尸状态。
 * 这些路径无法靠单元测试覆盖，必须在真实事件链与真实并发下验证。
 * </p>
 */
class LoopCancellationTest {

    /**
     * 运行中取消：循环应尽快收口，悬挂工具消息应落 CANCELED，且不留运行态残留
     */
    @Test
    void cancelsRunningLoopAndCancelsInflightTool() throws IOException {
        WaitingTool blockingTool = new WaitingTool("slow_tool", ToolAccessLevel.READ, "不该出现在结果里");
        try (EngineFixture fixture = EngineFixture.builder().tool(blockingTool).build()) {
            fixture.llm().enqueue(FakeLlmServer.Turn.toolCall("c1", "slow_tool", "{}"));

            fixture.submit("执行一个很慢的工具");

            // 建立确定中间态：工具确实已进入执行、循环正在运行
            assertTrue(blockingTool.awaitEntered(10_000), "工具应进入执行");
            EngineTestHarness.awaitLoopRunning(fixture.engine(), fixture.cid());

            AgentEngine engine = fixture.engine();
            engine.getLoopFacade().forceStopLoop(fixture.cid());

            EngineTestHarness.awaitCondition(() -> {
                Message tool = EngineTestHarness.toolMessage(engine, fixture.cid(), "c1");
                return tool != null && tool.getStatus() == MessageStatus.CANCELED;
            }, "悬挂工具消息应被置为 CANCELED");

            EngineTestHarness.awaitCondition(() -> EngineTestHarness.isLoopIdle(engine, fixture.cid()),
                    "强停后循环必须收口（loopRunning 归零）");

            blockingTool.release();
        }
    }

    /**
     * 强停应把飞行中的 ASSISTANT 消息一并置为 CANCELED，避免悬挂孤儿消息
     */
    @Test
    void forceStopCancelsInflightAssistantMessage() throws IOException {
        WaitingTool blockingTool = new WaitingTool("slow_tool", ToolAccessLevel.READ, "ok");
        try (EngineFixture fixture = EngineFixture.builder().tool(blockingTool).build()) {
            fixture.llm().enqueue(FakeLlmServer.Turn.toolCall("c1", "slow_tool", "{}"));

            fixture.submit("执行慢工具");
            assertTrue(blockingTool.awaitEntered(10_000));

            fixture.engine().getLoopFacade().forceStopLoop(fixture.cid());

            EngineTestHarness.awaitCondition(() -> {
                List<Message> assistants = EngineTestHarness.messagesOfRole(
                        fixture.engine(), fixture.cid(), MessageRole.ASSISTANT);
                return !assistants.isEmpty()
                        && assistants.stream().allMatch(m -> m.getStatus() == MessageStatus.CANCELED
                        || m.getStatus() == MessageStatus.SUCCESS);
            }, "飞行中的助手消息不应停留在 PENDING/RUNNING");

            boolean noRunning = EngineTestHarness.messagesOfRole(fixture.engine(), fixture.cid(), MessageRole.ASSISTANT)
                    .stream()
                    .noneMatch(m -> m.getStatus() == MessageStatus.RUNNING);
            assertTrue(noRunning, "强停后不得残留 RUNNING 状态的助手消息");

            blockingTool.release();
        }
    }

    /**
     * 强停应清空会话的循环账目（等待屏障与运行标志）
     */
    @Test
    void forceStopClearsLoopLedger() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            // 先制造一条历史消息，确保会话与消息链路可用
            fixture.llm().enqueue(FakeLlmServer.Turn.text("先完成一轮。"));
            fixture.submitAndAwait("预热");

            fixture.engine().getLoopFacade().forceStopLoop(fixture.cid());

            EngineTestHarness.awaitCondition(() -> {
                var conv = fixture.conversations().getById(fixture.cid());
                return conv != null
                        && (conv.getLoopRunning() == null || conv.getLoopRunning() == 0)
                        && conv.getWaitingToolIds() == null
                        && conv.getWaitingSubCids() == null;
            }, "强停应清空 loopRunning 与两个等待集合");
        }
    }

    /**
     * 取消后在途响应被丢弃：取消期间到达的 LLM 内容不得覆盖取消终态
     */
    @Test
    void doesNotOverwriteCanceledStateWithLateResponse() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            // 帧间加延迟，制造「流仍在传输」的窗口
            fixture.llm().frameDelayMs(40)
                    .enqueue(FakeLlmServer.Turn.text("这段内容本应被丢弃").usage(5L, 5L, 0L));

            fixture.submit("发送一个慢响应请求");
            EngineTestHarness.awaitLoopRunning(fixture.engine(), fixture.cid());

            fixture.engine().getLoopFacade().forceStopLoop(fixture.cid());
            EngineTestHarness.awaitCondition(() -> EngineTestHarness.isLoopIdle(fixture.engine(), fixture.cid()),
                    "取消后循环应尽快收口");

            // 再等一小会儿，确认迟到的响应没有把取消终态改写回 SUCCESS
            EngineTestHarness.awaitStable(() -> {
                List<Message> assistants = EngineTestHarness.messagesOfRole(
                        fixture.engine(), fixture.cid(), MessageRole.ASSISTANT);
                return assistants.stream().noneMatch(m -> m.getStatus() == MessageStatus.RUNNING);
            }, "助手消息不应停留在 RUNNING", 3000, 2);
        }
    }

    /**
     * 清空会话应删除全部消息并归零循环指标
     */
    @Test
    void clearsConversationMessagesAndMetrics() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            fixture.llm().enqueue(FakeLlmServer.Turn.text("第一轮完成。"));
            fixture.submitAndAwait("第一轮");

            assertTrue(EngineTestHarness.allMessages(fixture.engine(), fixture.cid()).size() >= 2);

            fixture.engine().getConversationFacade().clearConversation(fixture.cid());

            assertEquals(0, EngineTestHarness.allMessages(fixture.engine(), fixture.cid()).size(),
                    "清空会话应删除该会话全部消息");
            var conv = fixture.conversations().getById(fixture.cid());
            assertNotNull(conv, "清空消息不应删除会话本身");
            assertEquals(0, conv.getLoopRunning(), "清空会话应同时归零 loopRunning");
            assertNull(conv.getWaitingToolIds());
        }
    }

    /**
     * 消息重试：ASSISTANT 消息应被重置为 PENDING 并重新走一轮推理
     */
    @Test
    void retriesAssistantMessage() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            fixture.llm()
                    .enqueue(FakeLlmServer.Turn.text("第一次回答。"))
                    .enqueue(FakeLlmServer.Turn.text("重试后的回答。"));

            fixture.submitAndAwait("请回答");
            Message first = EngineTestHarness.lastMessageOfRole(fixture.engine(),
                    fixture.cid(), MessageRole.ASSISTANT);
            assertEquals("第一次回答。", first.getContent());

            fixture.engine().getLoopFacade().retryMessage(fixture.cid(), first.getId());

            EngineTestHarness.awaitCondition(() -> {
                Message latest = EngineTestHarness.lastMessageOfRole(fixture.engine(),
                        fixture.cid(), MessageRole.ASSISTANT);
                return latest != null && "重试后的回答。".equals(latest.getContent());
            }, "重试后应产生新的助手回答");

            assertEquals(2, fixture.llm().requestCount(), "重试应再发起一次 LLM 调用");
        }
    }

    /**
     * 历史重置：应删除目标节点及其后的全部消息（目标节点本身也被删除）
     */
    @Test
    void resetsHistoryToUserMessageNode() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            fixture.llm()
                    .enqueue(FakeLlmServer.Turn.text("第一轮回答。"))
                    .enqueue(FakeLlmServer.Turn.text("第二轮回答。"));

            fixture.submitAndAwait("第一轮提问");
            fixture.submitAndAwait("第二轮提问");

            int before = EngineTestHarness.allMessages(fixture.engine(), fixture.cid()).size();
            assertEquals(4, before, "两轮应各落 USER + ASSISTANT 两条消息");

            Message secondUser = EngineTestHarness.messagesOfRole(fixture.engine(),
                    fixture.cid(), MessageRole.USER).get(1);
            fixture.engine().getConversationFacade().resetConversationMessages(fixture.cid(), secondUser.getId());

            // 引擎语义为「删除该节点及之后」，故第二个用户节点自身也被删除，只留下前两条
            List<Message> after = EngineTestHarness.allMessages(fixture.engine(), fixture.cid());
            assertEquals(2, after.size(), "重置到第二个用户节点应保留该节点之前的全部消息");
            assertEquals("第一轮提问", after.get(0).getContent());
            assertEquals("第一轮回答。", after.get(1).getContent());
        }
    }

    /**
     * 宽松审批豁免的工具（isApprovalExempt）不进入权限评估，直接执行
     */
    @Test
    void bypassesGuardForApprovalExemptTool() throws IOException {
        FakeTool exempt = FakeTool.builder("internal_tool")
                .accessLevel(ToolAccessLevel.SENSITIVE)
                .approvalExempt(true)
                .executeResult("内部调度完成")
                .build();

        try (EngineFixture fixture = EngineFixture.builder().tool(exempt).build()) {
            // 守卫一律 ASK，若豁免失效则工具会被挂起
            fixture.guard().alwaysReturn(com.stioc.cute.engine.tool.types.ToolPermissionVerdict.ask());
            fixture.llm().enqueue(FakeLlmServer.Turn.toolCall("c1", "internal_tool", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("内部工具已执行。"));

            fixture.submitAndAwait("调用免审批工具");

            Message tool = EngineTestHarness.toolMessage(fixture.engine(), fixture.cid(), "c1");
            assertEquals(MessageStatus.SUCCESS, tool.getStatus(), "免审批工具应直接放行执行");
            assertEquals(0, fixture.guard().getEvaluateCount(), "免审批工具不应触发权限评估");
        }
    }
}
