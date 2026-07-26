package com.stioc.cute.message;

import com.stioc.cute.message.access.MessageRole;
import com.stioc.cute.message.access.MessageStatus;
import com.stioc.cute.message.access.MessageEntity;
import com.stioc.cute.message.access.*;

import com.alibaba.fastjson2.JSON;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.agent.LlmWindowManager;
import com.stioc.cute.agent.event.AgentEventFactory;
import com.stioc.cute.agent.event.AgentEventType;
import com.mybatisflex.core.util.UpdateEntity;
import com.stioc.cute.llm.CuteToolCall;
import com.stioc.cute.llm.CuteChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * 统一管理 Agent 循环中的持久化消息状态迁移。
 *
 * 这个服务属于消息领域：它只负责消息记录如何在 PENDING、RUNNING、SUCCESS
 * 以及各类终态之间流转，不负责工具执行、下一轮 Loop 调度，也不负责 WebSocket 事件翻译。
 */
@Slf4j
@Component
public class MessageStateMachineServiceImpl implements MessageStateMachineService {

    @Resource
    private MessageService messageService;
    @Resource
    private LlmWindowManager llmWindowManager;

    /**
     * 将数据库末尾消息对齐到下一次模型推理步骤。
     * 必要时创建初始 SYSTEM 消息，将待处理输入标记为已消费，并创建或重新打开当前活跃的 ASSISTANT 占位消息。
     */
    public Long alignForNextStep(AgentContext context) {
        Long cid = context.getCid();

        // 1. 查找并标记该会话下所有挂起状态的用户输入（USER/BRANCH）为 SUCCESS
        List<MessageEntity> pendingInputs = messageService.findByCidOrderByIdAsc(cid).stream()
                .filter(m -> (MessageRole.USER == m.getRole() || MessageRole.BRANCH == m.getRole()) && MessageStatus.PENDING == m.getStatus())
                .collect(Collectors.toList());

        boolean hasNewInput = !pendingInputs.isEmpty();
        for (MessageEntity input : pendingInputs) {
            markPendingInputSuccess(context, input);
        }

        // 2. 如果存在挂起的输入被消费，说明是新一轮交互的开始，在此重置 ReAct 循环计数与读取缓存
        if (hasNewInput) {
            context.setIterationCount(0);
            context.setConsecutiveUnknownTools(0);
            context.getReadFiles().clear();
        }

        MessageEntity lastMsg = messageService.findLastMessage(cid).orElse(null);
        if (lastMsg == null) {
            return null;
        }

        Long activeAssistantMsgId;
        if (MessageRole.USER == lastMsg.getRole()) {
            // 防止再次执行，由于前面已经消费过，这里仍安全通过并再次重置以防万一
            context.setIterationCount(0);
            context.setConsecutiveUnknownTools(0);
            context.getReadFiles().clear();
            activeAssistantMsgId = createAssistant(context, MessageStatus.RUNNING);
        } else if (MessageRole.ASSISTANT == lastMsg.getRole()) {
            activeAssistantMsgId = lastMsg.getId();
            markAssistantRunning(context, activeAssistantMsgId);
        } else if (MessageRole.TOOL == lastMsg.getRole() || MessageRole.SYSTEM == lastMsg.getRole() || MessageRole.COMPRESSED == lastMsg.getRole()) {
            activeAssistantMsgId = createAssistant(context, MessageStatus.RUNNING);
        } else if (MessageRole.BRANCH == lastMsg.getRole()) {
            activeAssistantMsgId = createAssistant(context, MessageStatus.RUNNING);
        } else {
            log.warn("无法对齐 Loop 消息状态: cid={}, role={}", cid, lastMsg.getRole());
            return null;
        }

        context.setActiveAssistantMsgId(activeAssistantMsgId);
        return activeAssistantMsgId;
    }

    /**
     * 将当前活跃的 ASSISTANT 消息标记为最终自然语言回答。
     */
    public void markAssistantSuccess(AgentContext context, CuteChatResponse response) {
        updateAssistant(context, MessageStatus.SUCCESS, response);
    }

    /**
     * 持久化本轮模型发起工具调用时的 ASSISTANT 消息。
     * 工具执行由其他组件负责，这里只记录模型可见的助手轮次。
     */
    public void markAssistantToolCalls(AgentContext context, CuteChatResponse response) {
        updateAssistant(context, MessageStatus.SUCCESS, response);
    }

    /**
     * 当 ReAct 迭代次数超过配置上限时，将当前 ASSISTANT 消息置为失败。
     */
    public void markIterationLimitExceeded(AgentContext context) {
        String warningMsg = "\n\n**[警告] 已达到最大迭代上限，运行已停止以防止失控。**";
        context.publishEvent(AgentEventFactory.createThinkingStream(context, warningMsg, false, true));
        updateAssistant(context, MessageStatus.FAILED, warningMsg, null, null);
    }

    /**
     * 用户中断后，将当前 ASSISTANT 消息置为取消。
     */
    public void markInterrupted(AgentContext context, InterruptedException e) {
        log.warn("Agent Loop 被中断: {}", e.getMessage());
        String cancelMsg = "[已取消] 当前 Agent 运行已停止。";
        updateAssistant(context, MessageStatus.CANCELED, cancelMsg, null, null);
        context.publishEvent(AgentEventFactory.createThinkingStream(context, "\n\n**[已取消] 当前 Agent 运行已停止。**", false, true));
    }

    /**
     * 连续出现无效工具调用后，将当前 ASSISTANT 消息置为失败。
     */
    public void markMeltdown(AgentContext context) {
        String warningMsg = "[熔断保护] 模型连续发起无效工具调用，运行已停止。";
        updateAssistant(context, MessageStatus.FAILED, warningMsg, null, null);
    }

    /**
     * Loop 发生未预期异常时，将当前 ASSISTANT 消息置为失败，并发布错误事件。
     */
    public void markException(AgentContext context, Exception e) {
        log.error("Agent Loop 执行失败", e);
        String errorMsg = "[对话异常] Agent 执行失败: " + e.getMessage();
        updateAssistant(context, MessageStatus.FAILED, errorMsg, null, null);
    }

    /**
     * 记录人工拒绝工具调用的结果，不执行该工具。
     */
    public void rejectTool(AgentContext context, MessageEntity toolMsg) {
        MessageEntity updateRejected = UpdateEntity.of(MessageEntity.class);
        updateRejected.setId(toolMsg.getId());
        updateRejected.setRole(MessageRole.TOOL);
        updateRejected.setStatus(MessageStatus.REJECTED);
        updateRejected.setContent("{\"error\": \"Permission denied by user.\"}");
        context.publishEvent(AgentEventFactory.createMessageUpdate(context, updateRejected));
    }

    private Long createAssistant(AgentContext context, MessageStatus status) {
        MessageEntity astMsg = MessageEntity.builder()
                .cid(context.getCid())
                .role(MessageRole.ASSISTANT)
                .content("")
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();
        context.publishEvent(AgentEventFactory.createMessageCreate(context, astMsg));
        return astMsg.getId();
    }

    private void markPendingInputSuccess(AgentContext context, MessageEntity inputMsg) {
        if (MessageStatus.PENDING != inputMsg.getStatus()) {
            return;
        }
        MessageEntity update = UpdateEntity.of(MessageEntity.class);
        update.setId(inputMsg.getId());
        update.setRole(inputMsg.getRole());
        update.setStatus(MessageStatus.SUCCESS);
        update.setContent(inputMsg.getContent());
        context.publishEvent(AgentEventFactory.createMessageUpdate(context, update));
    }

    private void markAssistantRunning(AgentContext context, Long activeAssistantMsgId) {
        if (activeAssistantMsgId == null) {
            return;
        }
        MessageEntity updateAst = UpdateEntity.of(MessageEntity.class);
        updateAst.setId(activeAssistantMsgId);
        updateAst.setRole(MessageRole.ASSISTANT);
        updateAst.setStatus(MessageStatus.RUNNING);
        updateAst.setContent("");
        context.publishEvent(AgentEventFactory.createMessageUpdate(context, updateAst));
    }

    private void updateAssistant(AgentContext context, MessageStatus status, String content, String reasoningContent, String toolCallsJson) {
        Long activeAssistantMsgId = context.getActiveAssistantMsgId();
        if (activeAssistantMsgId == null) {
            log.warn("跳过 ASSISTANT 消息更新，activeAssistantMsgId 为空: cid={}, status={}", context.getCid(), status);
            return;
        }
        MessageEntity update = UpdateEntity.of(MessageEntity.class);
        update.setId(activeAssistantMsgId);
        update.setRole(MessageRole.ASSISTANT);
        update.setStatus(status);
        update.setContent(content);
        update.setReasoningContent(reasoningContent);
        update.setToolCalls(toolCallsJson);
        context.publishEvent(AgentEventFactory.createMessageUpdate(context, update));
    }

    private void updateAssistant(AgentContext context, MessageStatus status, CuteChatResponse response) {
        Long activeAssistantMsgId = context.getActiveAssistantMsgId();
        if (activeAssistantMsgId == null) {
            log.warn("跳过 ASSISTANT 消息更新，activeAssistantMsgId 为空: cid={}, status={}", context.getCid(), status);
            return;
        }
        MessageEntity update = UpdateEntity.of(MessageEntity.class);
        update.setId(activeAssistantMsgId);
        update.setRole(MessageRole.ASSISTANT);
        update.setStatus(status);
        if (response != null) {
            update.setContent(response.getContent());
            update.setReasoningContent(response.getReasoningContent());
            if (response.getToolCalls() != null && !response.getToolCalls().isEmpty()) {
                update.setToolCalls(JSON.toJSONString(response.getToolCalls()));
            }
            if (response.getUsage() != null) {
                update.setInputTokens(response.getUsage().getInputTokens());
                update.setOutputTokens(response.getUsage().getOutputTokens());
                update.setCachedTokens(response.getUsage().getCachedTokens());
            }
            update.setExecutionDurationMs(response.getExecutionDurationMs());
        }
        context.publishEvent(AgentEventFactory.createMessageUpdate(context, update));
    }
}
