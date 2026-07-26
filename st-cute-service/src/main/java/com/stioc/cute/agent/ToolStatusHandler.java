package com.stioc.cute.agent;

import com.stioc.cute.agent.access.AgentContext;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.agent.event.AgentEventFactory;
import com.stioc.cute.agent.event.AgentEventType;
import com.mybatisflex.core.util.UpdateEntity;
import com.stioc.cute.message.access.MessageEntity;
import com.stioc.cute.message.access.MessageRole;
import com.stioc.cute.message.access.MessageService;
import com.stioc.cute.message.access.MessageStatus;
import com.stioc.cute.llm.CuteToolCall;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具状态落库与事件发布处理器（Spring 单例，无状态）。
 * 写操作仍走事件（CREATE/UPDATE_REQUEST）；读查询经 MessageService 按 callId 定位行，
 * 彻底替代原先的内存映射缓存，规避重启丢映射与悬挂 TOOL 行问题。
 */
@Component
public class ToolStatusHandler {

    @Resource
    private MessageService messageService;

    /**
     * 工具被大模型召回并创建时的回调
     */
    public void onToolCreated(AgentContext context, CuteToolCall call) {
        onToolCreated(context, call, null);
    }

    /**
     * 工具被大模型召回并创建时的回调，通过事件抛出创建请求。初始状态统一为 PENDING。
     */
    public void onToolCreated(AgentContext context, CuteToolCall call, Long parentAssistantMsgId) {
        MessageStatus initialStatus = MessageStatus.PENDING;

        // 构造存盘的工具描述 JSONObject
        JSONObject toolDesc = new JSONObject();
        toolDesc.put("id", call.getId());
        toolDesc.put("name", call.getName());
        toolDesc.put("arguments", call.getArguments());

        // 构造尚未落库的消息实体（callId 作为 callId↔messageId 的稳定关联键，写入 call_id 列）
        MessageEntity toolEntity = MessageEntity.builder()
                .cid(context.getCid())
                .role(MessageRole.TOOL)
                .content("")
                .status(initialStatus)
                .toolCalls(toolDesc.toJSONString())
                .callId(call.getId())
                .parentMessageId(parentAssistantMsgId)
                .createdAt(LocalDateTime.now())
                .build();

        // 抛出同步创建数据库指令事件，由监听器链完成数据库落库
        context.publishEvent(AgentEventFactory.createMessageCreate(context, toolEntity));
    }

    /**
     * 工具执行状态更新时的回调
     */
    public void onStatusUpdated(AgentContext context, String toolCallId, MessageStatus status, String resultPayload) {
        onStatusUpdated(context, toolCallId, status, resultPayload, null);
    }

    /**
     * 工具执行状态更新时的回调，支持保留折叠前的完整日志
     */
    public void onStatusUpdated(AgentContext context, String toolCallId, MessageStatus status, String resultPayload, String beforeCompactContent) {
        MessageEntity toolMsg = messageService.findToolMessage(context.getCid(), toolCallId);
        if (toolMsg == null) {
            context.publishEvent(AgentEventFactory.createMessageCreate(context,
                    buildMissingToolMessage(context, toolCallId, resultPayload)));
            return;
        }

        MessageEntity update = UpdateEntity.of(MessageEntity.class);
        update.setId(toolMsg.getId());
        update.setRole(MessageRole.TOOL);
        update.setStatus(status);
        update.setContent(resultPayload);
        update.setBeforeCompactContent(beforeCompactContent);
        context.publishEvent(AgentEventFactory.createMessageUpdate(context, update));
    }

    private MessageEntity buildMissingToolMessage(AgentContext context, String toolCallId, String resultPayload) {
        JSONObject toolDesc = new JSONObject();
        toolDesc.put("id", toolCallId);
        toolDesc.put("name", "unknown");
        toolDesc.put("arguments", "{}");

        String content = resultPayload != null
                ? resultPayload
                : "{\"error\": \"Tool message was missing before status update.\"}";
        return MessageEntity.builder()
                .cid(context.getCid())
                .role(MessageRole.TOOL)
                .content(content)
                .status(MessageStatus.FAILED)
                .toolCalls(toolDesc.toJSONString())
                .callId(toolCallId)
                .parentMessageId(context.getActiveAssistantMsgId())
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * 取消当前会话所有尚未运行完成的工具，防范悬挂状态数据。
     * 经 MessageService 查库定位悬挂 TOOL 行（PENDING/RUNNING/WAITING_APPROVAL），即便重启后也能清理孤儿。
     */
    public void cancelAllRemainingTools(AgentContext context, String reason) {
        String errorPayload = "{\"error\": \"" + (reason != null ? reason : "运行中断或熔断") + "\"}";
        List<MessageEntity> inflight = messageService.findInflightToolMessages(context.getCid());
        for (MessageEntity m : inflight) {
            MessageEntity update = UpdateEntity.of(MessageEntity.class);
            update.setId(m.getId());
            update.setRole(MessageRole.TOOL);
            update.setStatus(MessageStatus.CANCELED);
            update.setContent(errorPayload);
            context.publishEvent(AgentEventFactory.createMessageUpdate(context, update));
        }
    }
}
