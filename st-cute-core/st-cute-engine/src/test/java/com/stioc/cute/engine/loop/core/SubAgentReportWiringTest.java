package com.stioc.cute.engine.loop.core;

import com.stioc.cute.engine.common.JsonKit;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.engine.store.types.ConversationQuery;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.store.types.SortDirection;
import com.stioc.cute.engine.testkit.EngineFixture;
import com.stioc.cute.engine.testkit.EngineTestHarness;
import com.stioc.cute.engine.testkit.FakeLlmServer;
import com.stioc.cute.engine.testkit.WaitingTool;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子智能体「派发 → 执行 → 汇报 → 父会话被唤醒汇总」的端到端跨会话编排测试。
 * <p>
 * 这是引擎中最容易断链的一条链路：父会话把子会话挂进等待屏障后必须真的「停下来」，
 * 子会话跑完必须真的把结论投回父会话，并被唤醒补一轮汇总——任何一环断裂，
 * 用户看到的现象都是「父会话永远转圈」或「子结论石沉大海」，而单点用例都测不出来。
 * </p>
 * <p>
 * 编排依赖「请求顺序确定论」：父会话在挂上等待屏障后不会再发请求，故假服务端的请求序天然为
 * 1 父端发起派发 → 2 子端推理 → 3 父端被唤醒后汇总，无需额外同步手段即可稳定断言。
 * </p>
 */
class SubAgentReportWiringTest {

    /**
     * 完整跨会话闭环：父派发 → 子跑完 → 父会话被唤醒，且第二轮请求体真的带上了子智能体汇报
     */
    @Test
    void wakesParentWithSubAgentReportAfterChildCompletes() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            fixture.llm()
                    // 第 1 次请求（父）：决策派发子智能体
                    .enqueue(FakeLlmServer.Turn.toolCall("call_sub_1", "invoke_subagent",
                            "{\"role\":\"Codebase Reader\",\"prompt\":\"阅读目标源码并给出结论\"}"))
                    // 第 2 次请求（子）：子智能体产出结论
                    .enqueue(FakeLlmServer.Turn.text("子智能体结论：该包共 3 个类，职责清晰。"))
                    // 第 3 次请求（父，被唤醒后汇总）
                    .enqueue(FakeLlmServer.Turn.text("父会话汇总：子智能体已完成源码阅读。"));

            fixture.submitAndAwait("请派发一个子智能体读源码");

            // 1. 子会话确实被创建且挂在该父会话下
            List<Conversation> subs = fixture.conversations().listByQuery(ConversationQuery.builder()
                    .parentCid(fixture.cid()).sortDirection(SortDirection.ASC).build());
            assertEquals(1, subs.size(), "派发一次应恰好创建一个子会话");
            long subCid = subs.get(0).getId();
            EngineTestHarness.awaitLoopIdle(fixture.engine(), subCid);

            // 2. 请求序恰为上表的 1→2→3：既证明父会话在等子会话期间没有空转重发，
            //    也证明父会话真的被唤醒补了一轮（少一轮即链路断裂）
            assertEquals(3, fixture.llm().requestCount(),
                    "应为「父派发 + 子推理 + 父汇总」三次请求，实际: " + fixture.llm().requestCount());

            // 3. 子会话的结论已产出并落库
            var subAssistant = EngineTestHarness.lastMessageOfRole(fixture.engine(), subCid,
                    com.stioc.cute.engine.store.types.MessageRole.ASSISTANT);
            assertNotNull(subAssistant, "子会话应产出助手结论");
            assertTrue(subAssistant.getContent().contains("子智能体结论"),
                    "子会话结论应已落库，实际: " + subAssistant.getContent());

            // 4. 父会话收到以 BRANCH 角色持久化的工作报告（前端单独样式展示的那条）
            var report = EngineTestHarness.lastMessageOfRole(fixture.engine(), fixture.cid(),
                    com.stioc.cute.engine.store.types.MessageRole.BRANCH);
            assertNotNull(report, "子会话完成后必须向父会话投递工作报告，否则父会话的结论永远缺席");
            assertTrue(report.getContent().contains("[子 Agent 工作报告]"),
                    "报告应使用统一格式，实际: " + report.getContent());
            assertTrue(report.getContent().contains("子智能体结论"),
                    "报告应携带子会话的最后输出作为结果摘要，实际: " + report.getContent());

            // 5. 核心不变量：父会话被唤醒后的那一次请求体里，必须真的带上了子智能体汇报
            //    （以 USER 角色 + 来源前缀进入历史，否则模型看不到子结论）
            String wakeUpBody = fixture.llm().requestBodies().get(2);
            JSONObject body = JsonKit.parseObject(wakeUpBody);
            JSONArray messages = body.getJSONArray("messages");
            String mergedRoleContent = messages.stream()
                    .map(m -> ((JSONObject) m).getString("role") + "|" + ((JSONObject) m).getString("content"))
                    .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(mergedRoleContent.contains("来自其他Agent："),
                    "父会话被唤醒后的请求体必须包含子智能体汇报（带来源前缀），实际请求体: " + wakeUpBody);
            assertTrue(mergedRoleContent.contains("子智能体结论"),
                    "汇报内容应携带子会话的实际结论，实际请求体: " + wakeUpBody);

            // 6. 双双收口：父会话等待子会话屏障已扣净、loopRunning 归零
            assertTrue(EngineTestHarness.isLoopIdle(fixture.engine(), fixture.cid()),
                    "父会话必须彻底收口（屏障清空且 loopRunning=0）");
        }
    }

    /**
     * 子会话自身不得再次派生：整条链路只允许一个子会话（防递归滥用的端到端验证）
     */
    @Test
    void doesNotAllowNestedSubSession() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            fixture.llm()
                    .enqueue(FakeLlmServer.Turn.toolCall("call_sub_1", "invoke_subagent",
                            "{\"role\":\"Codebase Reader\",\"prompt\":\"读源码\"}"))
                    // 子会话也试图派生——工具在子会话中不可用，引擎应直接拦截
                    .enqueue(FakeLlmServer.Turn.toolCall("call_nested", "invoke_subagent",
                            "{\"role\":\"Nested Reader\",\"prompt\":\"再派生一层\"}"))
                    .enqueue(FakeLlmServer.Turn.text("子智能体收尾。"))
                    .enqueue(FakeLlmServer.Turn.text("父会话收尾。"));

            fixture.submitAndAwait("派发子智能体");
            EngineTestHarness.awaitCondition(
                    () -> fixture.conversations().size() == 2, "链路收口（父 + 一个子会话）");

            List<Conversation> subs = fixture.conversations().listByQuery(ConversationQuery.builder()
                    .parentCid(fixture.cid()).build());
            assertEquals(1, subs.size(), "只有父会话可直接派发，子会话不得再派生（防无限递归）");

            long subCid = subs.get(0).getId();
            var nestedToolMsg = EngineTestHarness.toolMessage(fixture.engine(), subCid, "call_nested");
            assertNotNull(nestedToolMsg, "子会话的派生尝试应留下工具消息（记录被拦截的事实）");
            assertTrue(nestedToolMsg.getContent().contains("不可用"),
                    "子会话内该工具不可用，应被引擎拦截并写入失败原因，实际: " + nestedToolMsg.getContent());
        }
    }

    /**
     * 主会话停止必须级联停掉子会话，且子会话要向父会话交代「已停止」——否则父会话的等待屏障
     * 永远扣不掉，用户在界面上看到的将是一个永远转圈的主会话。
     * <p>
     * 用户点「停止」的语义是整棵树停止：子 Agent 不得在后台继续烧 token，
     * 且必须留下可解释的收场痕迹（等同于错误结束，只是状态与原因不同）。
     * </p>
     */
    @Test
    void stoppingParentCascadesToSubAgentAndReportsBack() throws IOException {
        WaitingTool blockingTool = new WaitingTool("slow_read", ToolAccessLevel.READ, "不该出现的工具结果");
        try (EngineFixture fixture = EngineFixture.builder().tool(blockingTool).build()) {
            fixture.llm()
                    // 父：派发子智能体
                    .enqueue(FakeLlmServer.Turn.toolCall("call_sub_1", "invoke_subagent",
                            "{\"role\":\"Blocker\",\"prompt\":\"执行一个会卡住的任务\"}"))
                    // 子：调用阻塞工具（用例据此获得确定的运行中窗口）
                    .enqueue(FakeLlmServer.Turn.toolCall("call_block", "slow_read", "{}"))
                    // 子：兜底台词（正常情况下不应被消费）
                    .enqueue(FakeLlmServer.Turn.text("子智能体原本的结论。"))
                    // 父：兜底台词
                    .enqueue(FakeLlmServer.Turn.text("父会话原本的汇总。"));

            fixture.submit("派发一个子智能体");

            assertTrue(blockingTool.awaitEntered(10_000), "前置条件：子会话的工具确实已进入执行，处于运行中");
            List<Conversation> subs = fixture.conversations().listByQuery(ConversationQuery.builder()
                    .parentCid(fixture.cid()).build());
            assertEquals(1, subs.size(), "前置条件：应恰好创建一个子会话");
            long subCid = subs.get(0).getId();

            // 主会话点停止
            fixture.engine().getLoopFacade().forceStopLoop(fixture.cid());

            EngineTestHarness.awaitCondition(() -> {
                Conversation sub = fixture.conversations().getById(subCid);
                return sub != null && sub.getLoopRunning() != null && sub.getLoopRunning() == 0;
            }, "子会话必须被级联停止（loopRunning 归零）");

            // 1. 父会话屏障被清空，不再永远等待
            EngineTestHarness.awaitCondition(() -> {
                Conversation parent = fixture.conversations().getById(fixture.cid());
                return parent != null
                        && (parent.getWaitingSubCids() == null || parent.getWaitingSubCids().isBlank())
                        && (parent.getWaitingToolIds() == null || parent.getWaitingToolIds().isBlank());
            }, "父会话的等待屏障必须被清空，否则界面永远转圈");

            // 2. 父会话收到子代理的「已停止」工作报告（等同错误结束，仅状态与原因不同）
            Message report = EngineTestHarness.lastMessageOfRole(fixture.engine(), fixture.cid(), MessageRole.BRANCH);
            assertNotNull(report, "子会话被停后必须向父会话交代，否则结论与死讯全部石沉大海");
            assertTrue(report.getContent().contains("[子 Agent 工作报告]"), "报告应使用统一格式");
            assertTrue(report.getContent().contains("已停止"),
                    "强杀子 Agent 应报「已停止」而非「已完成」，实际: " + report.getContent());
            assertTrue(report.getContent().contains("原因:"),
                    "已停止状态必须携带原因行，实际: " + report.getContent());

            // 3. 父子双双收口
            EngineTestHarness.awaitLoopIdle(fixture.engine(), subCid);
            EngineTestHarness.awaitLoopIdle(fixture.engine(), fixture.cid());

            blockingTool.release();
        }
    }

    /**
     * 单独强停一个子智能体：流程等同于错误结束，父会话必须被唤醒并拿到结论，
     * 而不是永远停在等待屏障上。
     */
    @Test
    void stoppingSubAgentAloneWakesParentWithoutDeadlock() throws IOException {
        WaitingTool blockingTool = new WaitingTool("slow_read", ToolAccessLevel.READ, "不该出现的工具结果");
        try (EngineFixture fixture = EngineFixture.builder().tool(blockingTool).build()) {
            fixture.llm()
                    .enqueue(FakeLlmServer.Turn.toolCall("call_sub_1", "invoke_subagent",
                            "{\"role\":\"Blocker\",\"prompt\":\"执行一个会卡住的任务\"}"))
                    .enqueue(FakeLlmServer.Turn.toolCall("call_block", "slow_read", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("子智能体原本的结论。"))
                    // 父会话被唤醒后的汇总台词
                    .enqueue(FakeLlmServer.Turn.text("父会话汇总：得知子任务已停止。"));

            fixture.submit("派发一个子智能体");

            assertTrue(blockingTool.awaitEntered(10_000), "前置条件：子会话的工具确实已进入执行");
            List<Conversation> subs = fixture.conversations().listByQuery(ConversationQuery.builder()
                    .parentCid(fixture.cid()).build());
            assertEquals(1, subs.size(), "前置条件：应恰好创建一个子会话");
            long subCid = subs.get(0).getId();

            int requestsBefore = fixture.llm().requestCount();

            // 只停子会话，父会话不动
            fixture.engine().getLoopFacade().forceStopLoop(subCid);

            // 1. 父会话被唤醒补一轮汇总（而不是永远等待）
            EngineTestHarness.awaitCondition(() -> fixture.llm().requestCount() > requestsBefore,
                    "单独停掉子智能体后，父会话应被唤醒补一轮汇总");

            // 2. 父会话屏障被扣减干净并彻底收口
            EngineTestHarness.awaitLoopIdle(fixture.engine(), fixture.cid());

            // 3. 父会话拿到「已停止」报告
            Message report = EngineTestHarness.lastMessageOfRole(fixture.engine(), fixture.cid(), MessageRole.BRANCH);
            assertNotNull(report, "子会话被停后必须向父会话投递工作报告");
            assertTrue(report.getContent().contains("已停止"),
                    "强杀子 Agent 应报「已停止」，实际: " + report.getContent());

            blockingTool.release();
        }
    }

    /**
     * 子智能体异常结束（此例走 LLM 出口不可用）：必须与成功结束一样完成上报与屏障扣减，
     * 否则父会话的等待永远无法闭环。
     */
    @Test
    void reportsSubAgentFailureBackToParentAndAsksAgain() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            fixture.llm()
                    .enqueue(FakeLlmServer.Turn.toolCall("call_sub_1", "invoke_subagent",
                            "{\"role\":\"Failing\",\"prompt\":\"执行一个注定失败的任务\"}"))
                    // 子会话的 LLM 出口持续不可用（剧本耗尽后重复末条，故子会话必然失败）
                    .enqueue(FakeLlmServer.Turn.text("占位").mode(FakeLlmServer.Mode.HTTP_500))
                    // 父会话被唤醒后的汇总台词
                    .enqueue(FakeLlmServer.Turn.text("父会话汇总：得知子任务失败了。"));

            fixture.submit("派发一个会失败的子智能体");

            EngineTestHarness.awaitCondition(() -> fixture.conversations().size() == 2,
                    "前置条件：父 + 一个子会话");
            List<Conversation> subs = fixture.conversations().listByQuery(ConversationQuery.builder()
                    .parentCid(fixture.cid()).build());
            assertEquals(1, subs.size(), "前置条件：应恰好创建一个子会话");
            long subCid = subs.get(0).getId();
            EngineTestHarness.awaitLoopIdle(fixture.engine(), subCid);

            // 1. 父会话屏障被扣减干净（异常结束同样闭环）
            EngineTestHarness.awaitLoopIdle(fixture.engine(), fixture.cid());

            // 2. 父会话收到「失败」报告（而非误报「已完成」）
            List<Message> reports = EngineTestHarness.messagesOfRole(fixture.engine(), fixture.cid(), MessageRole.BRANCH);
            assertTrue(!reports.isEmpty(), "子会话异常结束必须向父会话投递工作报告");
            String merged = reports.stream().map(Message::getContent).reduce("", (a, b) -> a + "\n" + b);
            assertTrue(merged.contains("失败"),
                    "子会话异常结束应如实报「失败」，实际报告: " + merged);
        }
    }

    /**
     * 重启对账：子智能体随进程一起中断（断电残留），重启后必须补报死讯并清空屏障，
     * 且不得自动续跑（重启后无人值守，自动发 LLM 调用既烧配额又可能踩到未就绪的宿主资产）。
     */
    @Test
    void startupReconcilesSubAgentLeftByCrashWithoutAutoResume() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            long parentCid = fixture.cid();

            // 模拟断电现场：子会话挂在父会话屏障上，自身处于运行中
            Conversation subConversation = Conversation.builder()
                    .parentCid(parentCid)
                    .workspaceId("test-workspace")
                    .title("子任务: Crash Victim")
                    .loopRunning(1)
                    .loopCount(1)
                    .build();
            fixture.conversations().insert(subConversation);
            long subCid = subConversation.getId();

            // 子会话产出了一条 RUNNING 的助手消息后断电
            fixture.messages().insert(Message.builder().cid(subCid).role(MessageRole.ASSISTANT)
                    .content("")
                    .status(MessageStatus.RUNNING)
                    .createTime(java.time.LocalDateTime.now().minusHours(1)).build());

            // 父会话挂上该子会话的等待屏障（经事件链写入内存与库，对齐生产语义）
            AgentContext parentContext = fixture.engine().getContextFacade().getOrCreateContext(parentCid);
            fixture.engine().getConversationFacade().publishConversationUpdate(parentContext,
                    new ConversationPatch(parentCid).waitingSubCids("+" + subCid));

            int requestsBefore = fixture.llm().requestCount();

            // 重启自愈扫描
            fixture.engine().getLoopFacade().cleanStale(null);

            // 1. 补报死讯：父会话收到「已停止」报告
            Message report = EngineTestHarness.lastMessageOfRole(fixture.engine(), parentCid, MessageRole.BRANCH);
            assertNotNull(report, "重启对账必须为残留子会话补一份工作报告");
            assertTrue(report.getContent().contains("已停止"),
                    "随进程中断的子智能体应报「已停止」，实际: " + report.getContent());

            // 2. 屏障被彻底清空（否则下次用户交互会被永久卡住）
            Conversation parentRow = fixture.conversations().getById(parentCid);
            assertNull(parentRow.getWaitingSubCids(), "重启后父会话的待办子会话屏障必须清空");
            assertNull(parentRow.getWaitingToolIds(), "重启后父会话的待办工具屏障必须清空");
            assertEquals(0, parentRow.getLoopRunning(), "重启后不得残留 loopRunning=1");

            // 3. 刻意不自动续跑
            assertEquals(requestsBefore, fixture.llm().requestCount(),
                    "重启对账不得自动发起 LLM 调用（无人值守 + 宿主资产可能未就绪）");
        }
    }

    /**
     * 重启对账：子智能体在断电前已经跑完（结论已落库）时，屏障虽残留，但其结论必须被补报而非静默清掉。
     */
    @Test
    void startupReconcilesCompletedSubAgentAndPreservesItsConclusion() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            long parentCid = fixture.cid();

            Conversation subConversation = Conversation.builder()
                    .parentCid(parentCid)
                    .workspaceId("test-workspace")
                    .title("子任务: Done Before Crash")
                    .loopRunning(0)
                    .loopCount(1)
                    .build();
            fixture.conversations().insert(subConversation);
            long subCid = subConversation.getId();

            // 子会话已产出结论并收尾（loopRunning=0），只是父会话尚未被唤醒就断电了
            fixture.messages().insert(Message.builder().cid(subCid).role(MessageRole.USER)
                    .content("去读源码").status(MessageStatus.SUCCESS)
                    .createTime(java.time.LocalDateTime.now().minusHours(1)).build());
            fixture.messages().insert(Message.builder().cid(subCid).role(MessageRole.ASSISTANT)
                    .content("子智能体结论：该模块共 3 个类。").status(MessageStatus.SUCCESS)
                    .createTime(java.time.LocalDateTime.now().minusHours(1)).build());

            AgentContext parentContext = fixture.engine().getContextFacade().getOrCreateContext(parentCid);
            fixture.engine().getConversationFacade().publishConversationUpdate(parentContext,
                    new ConversationPatch(parentCid).waitingSubCids("+" + subCid));

            fixture.engine().getLoopFacade().cleanStale(null);

            Message report = EngineTestHarness.lastMessageOfRole(fixture.engine(), parentCid, MessageRole.BRANCH);
            assertNotNull(report, "已跑完但未被汇总的子智能体，其结论必须被补报");
            assertTrue(report.getContent().contains("已完成"),
                    "已自行收尾的子智能体应如实报「已完成」，实际: " + report.getContent());
            assertTrue(report.getContent().contains("该模块共 3 个类"),
                    "报告必须保留子智能体的实际结论，实际: " + report.getContent());

            assertNull(fixture.conversations().getById(parentCid).getWaitingSubCids(),
                    "补报后父会话屏障必须清空");
        }
    }

    /**
     * 停止父会话时不得牵连已完结的历史子会话（级联范围必须收敛到「还在跑的」）。
     * <p>
     * 否则每个历史子会话都会被做一次无谓的上下文创建（getOrCreateContext 会触发宿主资产装载：
     * 读技能/规则/MCP），并越界清理它们早已落定的历史消息——这与「用户只想停止当前运行」的预期不符。
     * </p>
     */
    @Test
    void cascadeStopDoesNotTouchFinishedHistoricalSubAgents() throws IOException {
        WaitingTool blockingTool = new WaitingTool("slow_read", ToolAccessLevel.READ, "不该出现的工具结果");
        try (EngineFixture fixture = EngineFixture.builder().tool(blockingTool).build()) {
            long parentCid = fixture.cid();

            // 历史子会话：早已成功结束（loopRunning=0），且不在父会话屏障上
            Conversation finishedSub = Conversation.builder()
                    .parentCid(parentCid)
                    .workspaceId("test-workspace")
                    .title("子任务: 早已完成")
                    .loopRunning(0)
                    .loopCount(1)
                    .build();
            fixture.conversations().insert(finishedSub);
            long finishedSubCid = finishedSub.getId();
            fixture.messages().insert(Message.builder().cid(finishedSubCid).role(MessageRole.ASSISTANT)
                    .content("历史子智能体的既有结论").status(MessageStatus.SUCCESS)
                    .createTime(java.time.LocalDateTime.now().minusHours(2)).build());

            // 当前仍在运行的子会话
            fixture.llm()
                    .enqueue(FakeLlmServer.Turn.toolCall("call_sub_1", "invoke_subagent",
                            "{\"role\":\"Blocker\",\"prompt\":\"执行一个会卡住的任务\"}"))
                    .enqueue(FakeLlmServer.Turn.toolCall("call_block", "slow_read", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("子智能体原本的结论。"))
                    .enqueue(FakeLlmServer.Turn.text("父会话原本的汇总。"));
            fixture.submit("派发一个子智能体");
            assertTrue(blockingTool.awaitEntered(10_000), "前置条件：当前子会话的工具确实已进入执行");

            // 当前运行中的子会话（排除上面手工插入的历史子会话）
            List<Conversation> activeSubs = fixture.conversations().listByQuery(ConversationQuery.builder()
                    .parentCid(parentCid).build()).stream()
                    .filter(c -> !c.getId().equals(finishedSubCid))
                    .toList();
            assertEquals(1, activeSubs.size(), "前置条件：应恰好有一个运行中的子会话");
            long runningSubCid = activeSubs.get(0).getId();

            fixture.engine().getLoopFacade().forceStopLoop(parentCid);

            // 1. 当前运行中的子会话被停，并给出交代
            EngineTestHarness.awaitCondition(() -> {
                Conversation sub = fixture.conversations().getById(runningSubCid);
                return sub != null && sub.getLoopRunning() != null && sub.getLoopRunning() == 0;
            }, "当前运行中的子会话必须被级联停止");

            // 2. 历史子会话的消息绝不能被越界改写
            Message historical = EngineTestHarness.lastMessageOfRole(fixture.engine(), finishedSubCid, MessageRole.ASSISTANT);
            assertNotNull(historical, "历史子会话的消息不应消失");
            assertEquals(MessageStatus.SUCCESS, historical.getStatus(),
                    "已完结的历史子会话消息绝不能被级联停止越界改写");
            assertEquals("历史子智能体的既有结论", historical.getContent(),
                    "历史子会话的结论正文不应被改写");

            // 3. 历史子会话未被牵连汇报（父会话只应收到当前子会话的报告，且其屏障本就不含历史子会话）
            long branchCount = EngineTestHarness.messagesOfRole(fixture.engine(), parentCid, MessageRole.BRANCH)
                    .stream().filter(m -> m.getContent().contains(String.valueOf(finishedSubCid))).count();
            assertEquals(0, branchCount, "已完结的历史子会话不应被牵连进本次停止的报告");

            EngineTestHarness.awaitLoopIdle(fixture.engine(), parentCid);
            blockingTool.release();
        }
    }

    /**
     * 子会话已跑完（结论已落库）但父会话尚未被唤醒时，用户点了停止：
     * 该子会话的结论必须如实报「已完成」，而不是被误报成「已停止」。
     */
    @Test
    void cascadeStopReportsAlreadyFinishedSubAgentAsCompleted() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            long parentCid = fixture.cid();

            // 子会话已自行收尾（loopRunning=0）却仍占位于父会话屏障（父尚未被唤醒）
            Conversation subConversation = Conversation.builder()
                    .parentCid(parentCid)
                    .workspaceId("test-workspace")
                    .title("子任务: 跑完未汇总")
                    .loopRunning(0)
                    .loopCount(1)
                    .build();
            fixture.conversations().insert(subConversation);
            long subCid = subConversation.getId();
            fixture.messages().insert(Message.builder().cid(subCid).role(MessageRole.ASSISTANT)
                    .content("该模块共 3 个类。").status(MessageStatus.SUCCESS)
                    .createTime(java.time.LocalDateTime.now().minusMinutes(5)).build());

            AgentContext parentContext = fixture.engine().getContextFacade().getOrCreateContext(parentCid);
            fixture.engine().getConversationFacade().publishConversationUpdate(parentContext,
                    new ConversationPatch(parentCid).waitingSubCids("+" + subCid));

            fixture.engine().getLoopFacade().forceStopLoop(parentCid);

            Message report = EngineTestHarness.lastMessageOfRole(fixture.engine(), parentCid, MessageRole.BRANCH);
            assertNotNull(report, "停止时该子会话必须被交代，否则其结论会随屏障一起被静默清掉");
            assertTrue(report.getContent().contains("已完成"),
                    "已自行收尾的子会话应如实报「已完成」，而不是被误报为「已停止」，实际: " + report.getContent());
            assertTrue(report.getContent().contains("该模块共 3 个类"),
                    "报告必须保留子智能体的实际结论，实际: " + report.getContent());
            assertFalse(report.getContent().contains("已停止"),
                    "不得把一次成功执行误报成被停止，实际: " + report.getContent());
        }
    }
}
