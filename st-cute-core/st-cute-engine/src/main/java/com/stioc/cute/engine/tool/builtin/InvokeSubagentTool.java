package com.stioc.cute.engine.tool.builtin;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolResult;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.loop.core.AgentContextManager;
import com.stioc.cute.engine.loop.core.AgentLoopCoordinator;
import com.stioc.cute.engine.loop.core.AgentContext;


import com.stioc.cute.engine.event.AgentEventFactory;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.common.EngineExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 委派子智能体并行处理复杂任务的工具 (invoke_subagent)
 */
@Slf4j
@RequiredArgsConstructor
public class InvokeSubagentTool implements CuteTool {

    public static final String NAME = "invoke_subagent";

    private final AgentContextManager agentContextManager;
    private final EngineExecutor executorProvider;

    private AgentLoopCoordinator agentLoopCoordinator;

    public void bindAgentLoopCoordinator(AgentLoopCoordinator coordinator) {
        this.agentLoopCoordinator = coordinator;
    }

    @Override
    public String getRawName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "针对可以完全解耦的独立复杂子任务（如阅读另一处无关的源码、分析特定子功能包等），拉起一个独立且能并发运行的子智能体。它会在后台执行 ReAct 流程直到结束，并自动将汇总结论以 USER 消息投递回父智能体。请不要在主流程受阻必须等待该结论时，滥用此工具产生死锁。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "role": {
              "type": "string",
              "description": "被委派的子智能体的专属角色名称（例如：'Codebase Reader'，'Test Runner'）"
            },
            "prompt": {
              "type": "string",
              "description": "派发给子智能体执行的独立具体任务描述，建议提供详尽的上下文以便其高效定位执行"
            }
          },
          "required": ["role", "prompt"]
        }
        """;
    }

    @Override
    public boolean isAvailable(AgentContext context) {
        // 如果是子 Agent 运行，禁止递归拉起子智能体，防止无限递归和嵌套
        return !context.isSubAgent();
    }

    @Override
    public boolean isApprovalExempt() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        ToolArgs args = ToolArgs.of(arguments);
        String role = args.getString("role");
        String prompt = args.getString("prompt");

        if (role == null || role.isBlank()) {
            return ToolResult.error("参数 'role' 不能为空。");
        }
        if (prompt == null || prompt.isBlank()) {
            return ToolResult.error("参数 'prompt' 不能为空。");
        }

        Long parentCid = agentContext.getCid();
        String workspaceId = agentContext.getWorkspaceId();

        if (workspaceId == null || workspaceId.isBlank()) {
            return ToolResult.error("当前会话未绑定合法的工作区（workspaceId 为空），无法拉起子智能体。");
        }

        log.info("开始并发委派子代理任务 - 角色: {}, 父会话 ID: {}", role, parentCid);

        // 1. 创建子会话数据库实体，通过事件系统进行同步写盘
        Conversation subSession = new Conversation();
        subSession.setParentCid(parentCid);
        subSession.setWorkspaceId(workspaceId);
        // permissionMode 已字符串化：直接透传引擎上下文中的字符串值
        subSession.setPermissionMode(agentContext.getPermissionMode());
        subSession.setProviderGroup(agentContext.getProviderGroup());
        subSession.setProviderModelName(agentContext.getProviderModelName());
        subSession.setTitle("子任务: " + role);

        // 发布会话创建命令（由于第一层是同步写盘，发布返回后 subSession 中已回填自增 id）
        agentContext.publishEvent(AgentEventFactory.createConversationCreate(agentContext, subSession));
        Long subCid = subSession.getId();

        // 2. 发布 CONVERSATION_UPDATE 差量追加子会话并触发前端绘制卡片通知
        ConversationPatch parentUpdate = new ConversationPatch(agentContext.getCid())
                .waitingSubCids("+" + subCid);
        agentContext.publishEvent(AgentEventFactory.createConversationUpdate(agentContext, parentUpdate));

        // 3. 异步利用虚拟线程池并行拉起子智能体 ReAct 对话循环
        final Long finalSubCid = subCid;

        // 3a. 前置持久化子会话的第一条 USER (PENDING) 消息作为状态机起点
        String subUserText = "[SubAgent 派发任务 - 角色: " + role + "]\n" + prompt;
        Message subUserMsg = Message.builder()
                .cid(finalSubCid)
                .role(MessageRole.USER)
                .content(subUserText)
                .status(MessageStatus.PENDING)
                .visibleToUser(true)
                .visibleToModel(true)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();

        // 提前获取/创建子智能体的运行时上下文，以便其能在正确的会话物理网络通道上外推消息
        AgentContext subContext = agentContextManager.getOrCreateContext(finalSubCid);
        subContext.setParentCid(agentContext.getCid());
        subContext.publishEvent(AgentEventFactory.createMessageCreate(subContext, subUserMsg));

        executorProvider.getAsyncExecutor().submit(() -> {
            // 异步拉起子智能体 ReAct Loop 状态机
            agentLoopCoordinator.executeLoopAsync(finalSubCid);
        });

        log.info("并发子智能体通过协调器启动, cid: {}, role: {}", finalSubCid, role);

        return new JSONObject()
                .fluentPut("success", true)
                .fluentPut("message", "已成功拉起后台并发子智能体 [ID: " + subCid + ", 角色: " + role + "] 去执行此任务。我将开始执行我后续的其他工具调用。当你看到子智能体运行结束向我反馈数据后，我会在下一轮次汇总并向你展示它的结论。")
                .toJSONString();
    }
}
