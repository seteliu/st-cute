package com.stioc.cute.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.agent.access.AgentContextManager;
import com.stioc.cute.agent.event.AgentEventFactory;
import com.stioc.cute.agent.event.AgentEventType;
import com.stioc.cute.agent.access.AgentLoopCoordinator;
import com.stioc.cute.agent.ToolExecutionEngine;
import com.mybatisflex.core.util.UpdateEntity;
import com.stioc.cute.llm.CuteToolCall;
import com.stioc.cute.message.access.MessageEntity;
import com.stioc.cute.message.access.MessageRole;
import com.stioc.cute.message.access.MessageService;
import com.stioc.cute.message.access.MessageStatus;
import com.stioc.cute.security.access.PermissionEngine;
import com.stioc.cute.security.access.PermissionRule;
import com.stioc.cute.agent.access.ToolApprovalService;
import com.stioc.cute.conversation.access.ConversationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 工具审批人在回路决策服务实现类
 */
@Slf4j
@Service
public class ToolApprovalServiceImpl implements ToolApprovalService {

    @Resource
    private AgentContextManager agentContextManager;
    @Resource
    private MessageService messageService;
    @Resource
    private PermissionEngine permissionEngine;
    @Resource
    private AgentLoopCoordinator agentLoopCoordinator;
    @Resource
    private ToolExecutionEngine toolExecutionEngine;
    @Resource
    private ConversationService conversationService;

    /**
     * 审核工具执行决策并分流执行/拒绝流向
     */
    @Override
    public boolean approveTool(Long cid, String toolCallId, String decision, boolean alwaysAllow,
                               String toolName, String contentPattern, String customArgOverride) {
        AgentContext context = agentContextManager.getOrCreateContext(cid);

        String projectBasePath = context != null ? context.getWorktreePath() : null;
        if (!StringUtils.hasText(projectBasePath) && context != null) {
            projectBasePath = conversationService.getProjectPath(context.getCid());
        }
        if (alwaysAllow && "ALLOW".equalsIgnoreCase(decision)
                && StringUtils.hasText(toolName) && StringUtils.hasText(contentPattern)) {
            try {
                PermissionRule rule = new PermissionRule(toolName, contentPattern, "ALLOW");
                permissionEngine.writeLocalRule(rule, projectBasePath);
                log.info("用户选择总是放行，已写入本地权限规则: {}", rule);
            } catch (Exception e) {
                log.error("写入总是放行权限规则失败", e);
            }
        }

        MessageEntity toolMsg = messageService.findToolMessage(cid, toolCallId);
        if (toolMsg == null) {
            log.warn("审批失败：会话 {} 中找不到 toolCallId={} 对应的工具消息", cid, toolCallId);
            return false;
        }

        if (!"ALLOW".equalsIgnoreCase(decision)) {
            if (context != null) {
                rejectToolAndContinue(context, toolMsg, toolCallId);
            }
            log.info("工具审批已拒绝: toolName={}, toolCallId={}", toolName, toolCallId);
            return true;
        }

        String originalArgs = extractArgsFromToolMsg(toolMsg);
        String finalArgs = StringUtils.hasText(customArgOverride) ? customArgOverride : originalArgs;
        String finalToolName = extractNameFromToolMsg(toolMsg);
        if (context != null) {
            CuteToolCall pendingToolCall = CuteToolCall.builder()
                    .id(toolCallId)
                    .name(finalToolName)
                    .arguments(finalArgs)
                    .build();
            toolExecutionEngine.resumeApprovedToolAsync(pendingToolCall, context);
        }
        log.info("工具审批已通过: toolName={}, toolCallId={}", finalToolName, toolCallId);
        return true;
    }

    private void rejectToolAndContinue(AgentContext context, MessageEntity toolMsg, String toolCallId) {
        MessageEntity updateRejected = UpdateEntity.of(MessageEntity.class);
        updateRejected.setId(toolMsg.getId());
        updateRejected.setRole(MessageRole.TOOL);
        updateRejected.setStatus(MessageStatus.REJECTED);
        updateRejected.setContent("{\"error\": \"Permission denied by user.\"}");
        context.publishEvent(AgentEventFactory.createMessageUpdate(context, updateRejected));
        agentLoopCoordinator.notifyToolCompleted(context, toolCallId);
    }

    private String extractNameFromToolMsg(MessageEntity toolMsg) {
        try {
            JSONObject obj = JSON.parseObject(toolMsg.getToolCalls());
            return obj.getString("name");
        } catch (Exception e) {
            return "";
        }
    }

    private String extractArgsFromToolMsg(MessageEntity toolMsg) {
        try {
            JSONObject obj = JSON.parseObject(toolMsg.getToolCalls());
            String args = obj.getString("arguments");
            return args != null ? args : "{}";
        } catch (Exception e) {
            return "{}";
        }
    }
}
