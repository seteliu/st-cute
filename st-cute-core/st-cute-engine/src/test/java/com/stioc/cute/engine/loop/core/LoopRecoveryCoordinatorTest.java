package com.stioc.cute.engine.loop.core;

import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.engine.store.types.ConversationQuery;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.store.types.SortDirection;
import com.stioc.cute.engine.testkit.EngineFixture;
import com.stioc.cute.engine.testkit.EngineTestHarness;
import com.stioc.cute.engine.testkit.FakeLlmServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 循环僵死自愈（{@code LoopRecoveryCoordinator.cleanStale}）测试。
 * <p>
 * 自愈是进程崩溃/强杀/断网后唯一的兜底修复路径：判断错方向会造成两种极端后果——
 * 该清的不清（会话永久卡在 loopRunning=1，前端永远转圈）或不该清的乱清（把用户刚提交的
 * 消息误判为僵死）。因此本类重点钉住两条入口<strong>语义差异</strong>与<strong>时间边界</strong>：
 * <ul>
 *   <li>启动全量自愈（createdBefore=null）：不按时间过滤，重置所有 loopRunning=1，残留消息一律 FAILED；</li>
 *   <li>定时超时扫描（createdBefore 非 null）：只处理早于截止点的消息，且 WAITING_APPROVAL 走
 *       <b>CANCELED</b>（审批超时）而非 FAILED —— 二者混淆会让用户看到错误的终态文案；</li>
 *   <li>子会话僵死必须向父会话补一份工作报告，否则父会话的等待屏障永远扣不掉、父永远醒不来。</li>
 * </ul>
 * </p>
 */
class LoopRecoveryCoordinatorTest {

    /**
     * 定时扫描：待审批工具落 CANCELED（审批超时文案），挂起用户输入落 FAILED，返回修复计数
     */
    @Test
    void timedScanCancelsWaitingApprovalAndFailsPendingMessages() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            long cid = fixture.cid();
            LocalDateTime staleTime = LocalDateTime.now().minusMinutes(10);

            fixture.messages().insert(Message.builder().cid(cid).role(MessageRole.TOOL).content("")
                    .status(MessageStatus.WAITING_APPROVAL).callId("call_wait").createTime(staleTime).build());
            fixture.messages().insert(Message.builder().cid(cid).role(MessageRole.USER).content("挂起的输入")
                    .status(MessageStatus.PENDING).createTime(staleTime).build());

            int repaired = fixture.engine().getLoopFacade().cleanStale(LocalDateTime.now());

            Message waitingTool = fixture.messages().getByQuery(MessageQuery.builder()
                    .cid(cid).callId("call_wait").build());
            assertEquals(MessageStatus.CANCELED, waitingTool.getStatus(),
                    "定时扫描下待审批工具应落 CANCELED（审批超时），而非 FAILED");
            assertTrue(waitingTool.getContent().contains("审批超时"),
                    "待审批工具应写入审批超时文案，实际: " + waitingTool.getContent());

            Message pendingUser = fixture.messages().getById(pendingUserId(fixture, cid));
            assertEquals(MessageStatus.FAILED, pendingUser.getStatus(), "挂起的用户输入应落 FAILED 终态");
            assertEquals("挂起的输入", pendingUser.getContent(),
                    "非 TOOL 消息只改状态不改正文（避免污染用户原文，正文缺失的助手消息才补错误说明）");

            assertEquals(2, repaired, "返回修复计数应为本次被修复的消息条数");
        }
    }

    /**
     * 定时扫描的时间边界：晚于截止点的在途消息绝不能被误判为僵死
     */
    @Test
    void timedScanLeavesFreshMessagesUntouched() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            long cid = fixture.cid();

            fixture.messages().insert(Message.builder().cid(cid).role(MessageRole.ASSISTANT).content("")
                    .status(MessageStatus.RUNNING).createTime(LocalDateTime.now()).build());

            // 截止点设在过去：此刻正在跑的 RUNNING 消息必须被放过（否则会把正常执行的会话打断）
            int repaired = fixture.engine().getLoopFacade().cleanStale(LocalDateTime.now().minusMinutes(10));

            assertEquals(0, repaired, "无早于截止点的僵死消息时不应修复任何内容");

            Message running = fixture.messages().getByQuery(MessageQuery.builder()
                    .cid(cid).role(MessageRole.ASSISTANT).build());
            assertEquals(MessageStatus.RUNNING, running.getStatus(),
                    "晚于截止点的在途消息必须保持原状，不得被超时扫描误伤");
        }
    }

    /**
     * 启动全量自愈：不分时间过滤，重置 loopRunning=1 的会话，且待审批工具走 FAILED 而非 CANCELED
     */
    @Test
    void startupScanResetsLoopRunningAndFailsAllStaleStatuses() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            long cid = fixture.cid();

            // 模拟上次进程被杀时留下的现场：会话标记为运行中，消息停在中间态
            fixture.conversations().updateByQuery(
                    Conversation.builder().loopRunning(1).build(),
                    ConversationQuery.builder().id(cid).build());

            LocalDateTime oldTime = LocalDateTime.now().minusHours(1);
            fixture.messages().insert(Message.builder().cid(cid).role(MessageRole.ASSISTANT).content("")
                    .status(MessageStatus.RUNNING).createTime(oldTime).build());
            fixture.messages().insert(Message.builder().cid(cid).role(MessageRole.TOOL).content("")
                    .status(MessageStatus.WAITING_APPROVAL).callId("call_stale").createTime(oldTime).build());

            // createdBefore 传 null 即启动全量自愈
            int repaired = fixture.engine().getLoopFacade().cleanStale(null);

            Conversation conv = fixture.conversations().getById(cid);
            assertEquals(0, conv.getLoopRunning(), "启动自愈必须把残留的 loopRunning=1 重置为 0");
            assertNull(conv.getWaitingToolIds(), "启动自愈应一并清空等待工具屏障");
            assertNull(conv.getWaitingSubCids(), "启动自愈应一并清空等待子会话屏障");

            Message waitingTool = fixture.messages().getByQuery(MessageQuery.builder()
                    .cid(cid).callId("call_stale").build());
            assertEquals(MessageStatus.FAILED, waitingTool.getStatus(),
                    "启动自愈语义是「系统重置」，待审批工具也应终结为 FAILED（与定时扫描的 CANCELED 区分）");
            assertTrue(waitingTool.getContent().contains("系统重置"),
                    "启动自愈文案应说明系统重置原因，实际: " + waitingTool.getContent());

            assertEquals(2, repaired, "RUNNING + WAITING_APPROVAL 两条都应被修复");
        }
    }

    /**
     * 子会话僵死必须向父会话补一份工作报告并扣减父会话等待屏障（否则父会话永远醒不来）
     */
    @Test
    void reportsStaleSubSessionBackToParentAndClearsBarrier() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            long parentCid = fixture.cid();

            // 子会话僵死清理会唤醒父会话补一轮汇总，故预置一条台词避免父会话打空请求
            fixture.llm().enqueue(FakeLlmServer.Turn.text("父端汇总：子任务已超时终止。"));

            // 子会话以 store 直插模拟「进程重启后从库中恢复」的僵死现场
            Conversation subConversation = Conversation.builder()
                    .parentCid(parentCid)
                    .workspaceId("test-workspace")
                    .title("子任务: Stale Reader")
                    .loopRunning(1)
                    .loopCount(1)
                    .build();
            fixture.conversations().insert(subConversation);
            long subCid = subConversation.getId();

            // 子会话已产出一条助手输出，随后僵死在 RUNNING 态
            fixture.messages().insert(Message.builder().cid(subCid).role(MessageRole.ASSISTANT)
                    .content("子智能体未完成的中间输出")
                    .status(MessageStatus.RUNNING).createTime(LocalDateTime.now().minusMinutes(30)).build());

            // 父会话挂上该子会话的等待屏障（经事件链写入内存与库，对齐生产语义）
            AgentContext parentContext = fixture.engine().getContextFacade().getOrCreateContext(parentCid);
            fixture.engine().getConversationFacade().publishConversationUpdate(parentContext,
                    new ConversationPatch(parentCid).waitingSubCids("+" + subCid));
            assertTrue(parentContext.getWaitingSubCids().contains(subCid), "前置条件：父会话屏障已挂上子会话");

            int repaired = fixture.engine().getLoopFacade().cleanStale(LocalDateTime.now());

            assertEquals(1, repaired, "子会话的僵死消息应被修复");

            // 1. 父会话收到子智能体的工作报告（以 BRANCH 角色持久化在父会话消息表中）
            Message report = EngineTestHarness.lastMessageOfRole(fixture.engine(), parentCid, MessageRole.BRANCH);
            assertNotNull(report, "子会话僵死必须向父会话补一份工作报告，否则父会话的结论永远缺席");
            assertTrue(report.getContent().contains("[子 Agent 工作报告]"), "报告应使用统一报告格式");
            assertTrue(report.getContent().contains(String.valueOf(subCid)), "报告应标明来源子会话 ID");

            // 2. 父会话等待屏障已剔除该死会话（后续判定不再被僵尸占位永久阻塞）
            Conversation parentRow = fixture.conversations().getById(parentCid);
            assertTrue(parentRow.getWaitingSubCids() == null
                            || !parentRow.getWaitingSubCids().contains(String.valueOf(subCid)),
                    "父会话屏障必须剔除已清理的子会话，实际: " + parentRow.getWaitingSubCids());

            // 3. 子会话自身被收口：运行态归零并回收内存上下文
            assertEquals(0, fixture.conversations().getById(subCid).getLoopRunning(),
                    "僵死子会话的 loopRunning 必须归零");
            assertNull(fixture.engine().getContextFacade().getActiveContext(subCid),
                    "僵死子会话的内存上下文应被回收，避免常驻泄漏");
        }
    }

    /**
     * 无僵死消息时不得产生任何副作用（幂等，可被定时器高频调用）
     */
    @Test
    void doesNothingWhenNoStaleMessages() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            fixture.messages().insert(Message.builder().cid(fixture.cid()).role(MessageRole.ASSISTANT)
                    .content("已成功的回答").status(MessageStatus.SUCCESS)
                    .createTime(LocalDateTime.now().minusHours(2)).build());

            assertEquals(0, fixture.engine().getLoopFacade().cleanStale(LocalDateTime.now()),
                    "无僵死消息时应返回 0");
            assertEquals(0, fixture.engine().getLoopFacade().cleanStale(null),
                    "即使启动全量自愈，无僵死消息时也应返回 0");

            assertEquals(MessageStatus.SUCCESS,
                    EngineTestHarness.allMessages(fixture.engine(), fixture.cid()).get(0).getStatus(),
                    "已成功的历史消息不得被自愈扫描改动");
        }
    }

    /**
     * 取会话中唯一一条 USER 消息的 ID（构造断言目标）
     */
    private static Long pendingUserId(EngineFixture fixture, long cid) {
        List<Message> users = fixture.messages().listByQuery(MessageQuery.builder()
                .cid(cid).role(MessageRole.USER).sortDirection(SortDirection.ASC).build());
        return users.get(0).getId();
    }
}
