package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.tool.access.ToolExecutionContext;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolNames;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.agent.access.AgentContextManager;
import com.stioc.cute.agent.access.AgentLoopCoordinator;
import com.stioc.cute.agent.event.AgentEventFactory;
import com.mybatisflex.core.util.UpdateEntity;
import com.stioc.cute.conversation.access.ConversationEntity;
import com.stioc.cute.conversation.access.ConversationService;
import com.stioc.cute.message.access.MessageEntity;
import com.stioc.cute.message.access.MessageRole;
import com.stioc.cute.message.access.MessageStatus;
import com.stioc.cute.platform.common.CommonThread;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 委派子智能体并行处理复杂任务的工具 (invoke_subagent)
 */
@Slf4j
@Component
public class InvokeSubagentTool implements CuteTool {

    @Resource
    private ObjectProvider<AgentLoopCoordinator> agentLoopCoordinatorProvider;
    @Resource
    private ConversationService conversationService;
    @Resource
    private AgentContextManager agentContextManager;

    @Override
    public String getName() {
        return ToolNames.INVOKE_SUBAGENT;
    }

    @Override
    public String getDescription() {
        return "【子智能体并行委派核心工具】针对可以完全解耦的独立复杂子任务（如阅读另一处无关的源码、分析特定子功能包等），拉起一个独立且能并发运行的子智能体。它会在后台执行 ReAct 流程直到结束，并自动将汇总结论以 USER 消息投递回父智能体。请不要在主流程受阻必须等待该结论时，滥用此工具产生死锁。";
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
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        String role = (String) arguments.get("role");
        String prompt = (String) arguments.get("prompt");

        if (role == null || role.isBlank()) {
            return new JSONObject().fluentPut("error", "参数 'role' 不能为空。").toJSONString();
        }
        if (prompt == null || prompt.isBlank()) {
            return new JSONObject().fluentPut("error", "参数 'prompt' 不能为空。").toJSONString();
        }

        Long parentCid = agentContext.getCid();
        Long projectId = getProjectId(parentCid);

        if (projectId == null) {
            return new JSONObject().fluentPut("error", "未绑定合法的项目，无法拉起子智能体").toJSONString();
        }

        log.info("开始并发委派子代理任务 - 角色: {}, 父会话 ID: {}", role, parentCid);

        // 1. 创建子会话数据库实体，通过事件系统进行同步写盘
        ConversationEntity subSession = new ConversationEntity();
        subSession.setParentCid(parentCid);
        subSession.setProjectId(projectId);
        subSession.setPermissionMode(agentContext.getPermissionMode().name());
        subSession.setProviderGroup(agentContext.getProviderGroup());
        subSession.setProviderModelName(agentContext.getProviderModelName());

        // 子会话自动继承父会话的 Worktree 物理隔离属性，确保写越界安全拦截
        if (agentContext.getWorktreePath() != null) {
            subSession.setWorktreePath(agentContext.getWorktreePath());
            subSession.setWorktreeBranch(agentContext.getWorktreeBranch());
        }

        // 子会话继承父会话已解锁的高级工具，使其可在写库和内存初始化时直接生效
        if (agentContext.getUnlockedTools() != null && !agentContext.getUnlockedTools().isEmpty()) {
            subSession.setUnlockedToolNames(String.join(",", agentContext.getUnlockedTools()));
        }
        subSession.setTitle("子任务: " + role);

        // 发布会话创建命令（由于第一层是同步写盘，发布返回后 subSession 中已回填自增 id）
        agentContext.publishEvent(AgentEventFactory.createConversationCreate(agentContext, subSession));
        Long subCid = subSession.getId();

        // 2. 发布 CONVERSATION_UPDATE 差量追加子会话并触发前端绘制卡片通知
        ConversationEntity parentUpdate = UpdateEntity.of(ConversationEntity.class);
        parentUpdate.setId(agentContext.getCid());
        parentUpdate.setWaitingSubCids("+" + subCid);
        agentContext.publishEvent(AgentEventFactory.createConversationUpdate(agentContext, parentUpdate));

        // 3. 异步利用虚拟线程池并行拉起子智能体 ReAct 对话循环
        final Long finalSubCid = subCid;

        // 3a. 前置持久化子会话的第一条 USER (PENDING) 消息作为状态机起点
        String subUserText = "[SubAgent 派发任务 - 角色: " + role + "]\n" + prompt;
        MessageEntity subUserMsg = MessageEntity.builder()
                .cid(finalSubCid)
                .role(MessageRole.USER)
                .content(subUserText)
                .status(MessageStatus.PENDING)
                .visibleToUser(true)
                .visibleToModel(true)
                .createdAt(LocalDateTime.now())
                .build();

        // 提前获取/创建子智能体的运行时上下文，以便其能在正确的会话物理网络通道上外推消息
        AgentContext subContext = agentContextManager.getOrCreateContext(finalSubCid);
        subContext.setParentCid(agentContext.getCid());
        subContext.publishEvent(AgentEventFactory.createMessageCreate(subContext, subUserMsg));

        CommonThread.submit(() -> {
            AgentLoopCoordinator coordinator = agentLoopCoordinatorProvider.getIfAvailable();
            if (coordinator != null) {
                // 异步拉起子智能体 ReAct Loop 状态机
                coordinator.executeLoopAsync(finalSubCid);
            } else {
                log.error("委派子任务失败: 找不到 AgentLoopCoordinator 实例, cid: {}", finalSubCid);
            }
        });

        log.info("并发子智能体通过协调器启动, cid: {}, role: {}", finalSubCid, role);

        return new JSONObject()
                .fluentPut("success", true)
                .fluentPut("message", "已成功拉起后台并发子智能体 [ID: " + subCid + ", 角色: " + role + "] 去执行此任务。我将开始执行我后续的其他工具调用。当你看到子智能体运行结束向我反馈数据后，我会在下一轮次汇总并向你展示它的结论。")
                .toJSONString();
    }

    private Long getProjectId(Long cid) {
        return conversationService.findById(cid)
                .map(ConversationEntity::getProjectId)
                .orElse(null);
    }
}
