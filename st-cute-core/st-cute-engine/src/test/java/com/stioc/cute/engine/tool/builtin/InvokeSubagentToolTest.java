package com.stioc.cute.engine.tool.builtin;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationQuery;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.store.types.SortDirection;
import com.stioc.cute.engine.testkit.EngineFixture;
import com.stioc.cute.engine.testkit.EngineTestHarness;
import com.stioc.cute.engine.testkit.FakeLlmServer;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子智能体派发工具（invoke_subagent）的同步行为直测。
 * <p>
 * 本类从引擎真实装配的实例上直取该工具并调用其 {@code execute}——依赖（上下文管理器、执行器、
 * 循环协调器）全是生产实现，只是绕过了「父会话先发起工具调用」这一段，从而把断言聚焦在
 * execute 返回那一刻的<b>同步事实</b>上：子会话的运行时状态继承、父会话等待屏障的建立、
 * 子会话首条任务的落库、返回值契约，以及「子会话内不可再派生」的防递归门禁。
 * </p>
 * <p>
 * 需要「父会话被汇报唤醒」的异步编排不在此覆盖，见 {@code loop/core/SubAgentReportWiringTest}。
 * </p>
 */
class InvokeSubagentToolTest {

    /**
     * 派发一次子任务：子会话必须完整继承父会话运行时状态，父会话屏障写入 subCid，子会话落首条任务
     */
    @Test
    void dispatchInheritsParentStateAndOpensWaitBarrier() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            // 帧延迟 + 大切片营造确定的窗口：子会话的流至少要 4 帧才走得完，
            // 期间父会话屏障必然稳定处于「已挂 subCid」的中间态（切片放大是为了压住帧数、控住耗时）
            fixture.llm().frameDelayMs(200).chunkSize(64)
                    .enqueue(FakeLlmServer.Turn.text("子智能体结论已产出。"))
                    .enqueue(FakeLlmServer.Turn.text("父端已收到结论并汇总。"));

            AgentContext parentContext = fixture.engine().getContextFacade().getOrCreateContext(fixture.cid());
            CuteTool subagentTool = fixture.engine().getToolFacade().getToolRegistry()
                    .getTool("invoke_subagent", parentContext);
            assertNotNull(subagentTool, "invoke_subagent 是引擎内置工具，必然已在注册中心");

            String result = subagentTool.execute(
                    Map.of("role", "Codebase Reader", "prompt", "读一遍目标源码"),
                    new ToolExecutionContext(parentContext, "call_sub_1"));

            // 1. 返回值契约：模型据此知道「已异步派发，稍后自行汇总」
            JSONObject resultJson = JSON.parseObject(result);
            assertTrue(resultJson.getBooleanValue("success"), "派发成功标志应为 true，实际: " + result);
            assertTrue(resultJson.getString("message").contains("已成功拉起后台并发子智能体"),
                    "返回值应告知模型任务已异步派发，实际: " + result);

            // 2. 父会话等待子会话屏障必须先于「取子会话」直读断言：
            //    execute 返回那一刻屏障已同步写入，而子会话至少还要等一帧（下方帧延迟）才可能收尾扣减，
            //    故此处直读是稳定的中间态；若改成轮询等待则可能被「子会话跑太快已扣净」反将一军。
            Conversation parentRow = fixture.conversations().getById(fixture.cid());
            assertNotNull(parentRow.getWaitingSubCids(), "派发后父会话必须立即挂上子会话屏障");

            // 3. 子会话已落库，且运行时状态逐项继承父会话（权限/供应商/工作区缺一不可，
            //    否则子智能体会以错误的权限或错误的模型运行）
            Conversation subConversation = awaitSubConversation(fixture);
            assertTrue(parentRow.getWaitingSubCids().contains(String.valueOf(subConversation.getId())),
                    "屏障内容应为子会话 ID，实际: " + parentRow.getWaitingSubCids());
            assertEquals(fixture.cid(), subConversation.getParentCid(), "子会话必须登记父会话 ID");
            assertEquals("test-workspace", subConversation.getWorkspaceId(), "工作区必须继承父会话");
            assertEquals(parentContext.getPermissionMode(), subConversation.getPermissionMode(),
                    "权限模式必须继承父会话（子会话权限随父）");
            assertEquals(fixture.provider().getGroup(), subConversation.getProviderGroup(),
                    "供应商分组必须继承父会话");
            assertEquals(fixture.provider().getModelName(), subConversation.getProviderModelName(),
                    "模型名必须继承父会话");
            assertEquals("子任务: Codebase Reader", subConversation.getTitle(), "子会话标题应包含角色名");

            // 4. 子会话首条任务已落库为 USER 消息（子智能体的 ReAct 起点）
            Message subTask = awaitSubTaskMessage(fixture, subConversation.getId());
            assertEquals(MessageRole.USER, subTask.getRole(), "子会话首条消息应为 USER 角色");
            assertTrue(subTask.getContent().contains("[SubAgent 派发任务 - 角色: Codebase Reader]"),
                    "子会话首条消息应带派发角色前缀，实际: " + subTask.getContent());
            assertTrue(subTask.getContent().contains("读一遍目标源码"), "子会话首条消息应携带任务描述");

            // 5. 收敛：子会话跑完并唤醒父会话汇总（失败会让等待超时暴露编排断链）
            EngineTestHarness.awaitLoopIdle(fixture.engine(), subConversation.getId());
            fixture.awaitIdle();
            assertEquals(MessageStatus.SUCCESS,
                    fixture.messages().getById(subTask.getId()).getStatus(),
                    "子会话的首条任务应被消费为 SUCCESS");
        }
    }

    /**
     * 子会话上下文中该工具必须不可用：否则子智能体可无限递归派生，直接打爆线程与配额
     */
    @Test
    void isUnavailableInsideSubAgentContext() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            AgentContext parentContext = fixture.engine().getContextFacade().getOrCreateContext(fixture.cid());
            CuteTool subagentTool = fixture.engine().getToolFacade().getToolRegistry()
                    .getTool("invoke_subagent", parentContext);
            assertNotNull(subagentTool);

            assertTrue(subagentTool.isAvailable(parentContext), "父会话（非子会话）中该工具应可用");

            // 子会话上下文：parentCid 非空即进入子智能体运行周期
            AgentContext subContext = fixture.engine().getContextFacade().getOrCreateContext(9001L);
            subContext.setParentCid(fixture.cid());

            assertFalse(subagentTool.isAvailable(subContext), "子会话中必须禁止再次派生（防递归滥用）");
        }
    }

    /**
     * 缺参数 / 缺工作区的派发请求应被拒绝且不创建任何子会话（模型幻觉参数的兜底）
     */
    @Test
    void rejectsInvalidArgumentsWithoutCreatingSubSession() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            AgentContext parentContext = fixture.engine().getContextFacade().getOrCreateContext(fixture.cid());
            CuteTool subagentTool = fixture.engine().getToolFacade().getToolRegistry()
                    .getTool("invoke_subagent", parentContext);
            assertNotNull(subagentTool);

            String missingRole = subagentTool.execute(Map.of("prompt", "只有任务没有角色"),
                    new ToolExecutionContext(parentContext, "call_bad_1"));
            assertTrue(missingRole.contains("role"), "缺 role 应返回参数错误，实际: " + missingRole);

            String missingPrompt = subagentTool.execute(Map.of("role", "Codebase Reader"),
                    new ToolExecutionContext(parentContext, "call_bad_2"));
            assertTrue(missingPrompt.contains("prompt"), "缺 prompt 应返回参数错误，实际: " + missingPrompt);

            // 工作区为空时无法确定子会话落点，必须拒绝而不是建出孤儿会话
            AgentContext noWorkspace = fixture.engine().getContextFacade().getOrCreateContext(9002L);
            noWorkspace.setWorkspaceId(null);
            String noWorkspaceResult = subagentTool.execute(
                    Map.of("role", "Codebase Reader", "prompt", "读源码"),
                    new ToolExecutionContext(noWorkspace, "call_bad_3"));
            assertTrue(noWorkspaceResult.contains("工作区"), "缺工作区应返回明确错误，实际: " + noWorkspaceResult);

            List<Conversation> all = fixture.conversations().listByQuery(
                    ConversationQuery.builder().parentCid(fixture.cid()).build());
            assertTrue(all.isEmpty(), "非法请求不得创建任何子会话，实际创建了: " + all.size());
        }
    }

    /**
     * 轮询等待子会话落库（子会话由 execute 内部经事件链同步创建，正常在毫秒级出现）
     */
    private static Conversation awaitSubConversation(EngineFixture fixture) {
        EngineTestHarness.awaitCondition(() -> !subConversations(fixture).isEmpty(), "子会话落库");
        return subConversations(fixture).get(0);
    }

    private static List<Conversation> subConversations(EngineFixture fixture) {
        return fixture.conversations().listByQuery(ConversationQuery.builder()
                .parentCid(fixture.cid())
                .sortDirection(SortDirection.ASC)
                .build());
    }

    /**
     * 轮询等待子会话的首条任务消息落库
     */
    private static Message awaitSubTaskMessage(EngineFixture fixture, long subCid) {
        EngineTestHarness.awaitCondition(() -> subTaskMessage(fixture, subCid) != null, "子会话首条任务消息落库");
        return subTaskMessage(fixture, subCid);
    }

    private static Message subTaskMessage(EngineFixture fixture, long subCid) {
        return fixture.messages().getByQuery(MessageQuery.builder()
                .cid(subCid)
                .role(MessageRole.USER)
                .sortDirection(SortDirection.ASC)
                .build());
    }
}
