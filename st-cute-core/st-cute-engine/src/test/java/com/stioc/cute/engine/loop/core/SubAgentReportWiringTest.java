package com.stioc.cute.engine.loop.core;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationQuery;
import com.stioc.cute.engine.store.types.SortDirection;
import com.stioc.cute.engine.testkit.EngineFixture;
import com.stioc.cute.engine.testkit.EngineTestHarness;
import com.stioc.cute.engine.testkit.FakeLlmServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            JSONObject body = JSON.parseObject(wakeUpBody);
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
}
