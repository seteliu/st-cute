package com.stioc.cute.agent.event;

import com.stioc.cute.agent.access.AgentContext;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.message.access.MessageVo;
import com.stioc.cute.message.access.MessageEntity;

import java.util.List;
import java.util.Map;

import com.stioc.cute.conversation.access.ConversationEntity;

/**
 * 智能体事件对象统一创建静态工厂类
 * 负责组装带有强类型上下文和统一时间戳的 AgentEvent 实例
 */
public class AgentEventFactory {

    /**
     * 创建会话创建类型事件
     */
    public static AgentEvent createConversationCreate(AgentContext context, Object payload) {
        return build(context, AgentEventType.CONVERSATION_CREATE, payload);
    }

    /**
     * 创建会话属性更新类型事件
     */
    public static AgentEvent createConversationUpdate(AgentContext context, ConversationEntity payload) {
        return build(context, AgentEventType.CONVERSATION_UPDATE, payload);
    }

    /**
     * 创建消息落库创建类型事件
     */
    public static AgentEvent createMessageCreate(AgentContext context, MessageEntity entity) {
        return build(context, AgentEventType.MESSAGE_CREATE, entity);
    }

    /**
     * 创建消息状态/内容更新类型事件
     */
    public static AgentEvent createMessageUpdate(AgentContext context, MessageEntity payload) {
        return build(context, AgentEventType.MESSAGE_UPDATE, payload);
    }

    /**
     * 创建会话物理删除事件
     */
    public static AgentEvent createConversationDelete(AgentContext context, Long cid) {
        return build(context, AgentEventType.CONVERSATION_DELETE, cid);
    }

    /**
     * 创建消息删除事件
     */
    public static AgentEvent createMessageDelete(AgentContext context, MessageEntity payload) {
        return build(context, AgentEventType.MESSAGE_DELETE, payload);
    }

    /**
     * 创建大模型思考或正文流式输出片段事件
     */
    public static AgentEvent createThinkingStream(AgentContext context, String chunkContent, boolean isReasoning, boolean isEnd, Long messageId) {
        JSONObject payload = new JSONObject();
        payload.put("content", chunkContent);
        payload.put("isReasoning", isReasoning);
        payload.put("isEnd", isEnd);
        if (messageId != null) {
            payload.put("messageId", messageId);
        }

        AgentEventType type = isReasoning ? AgentEventType.AGENT_THINKING_STREAM : AgentEventType.AGENT_CONTENT_STREAM;
        return build(context, type, payload);
    }

    /**
     * 创建大模型流式片段事件（默认无具体消息 ID 绑定）
     */
    public static AgentEvent createThinkingStream(AgentContext context, String chunkContent, boolean isReasoning, boolean isEnd) {
        return createThinkingStream(context, chunkContent, isReasoning, isEnd, null);
    }

    /**
     * 创建工具运行日志控制台增量输出流事件
     */
    public static AgentEvent createToolLogStream(AgentContext context, String toolCallId, String text) {
        return build(context, AgentEventType.TOOL_LOG_STREAM, Map.of(
                "id", toolCallId,
                "text", text
        ));
    }

    /**
     * 创建自定义事件
     */
    public static AgentEvent createCustom(AgentContext context, AgentEventType type, Object payload) {
        return build(context, type, payload);
    }

    /**
     * 构建核心事件包装对象
     */
    private static AgentEvent build(AgentContext context, AgentEventType type, Object payload) {
        return AgentEvent.builder()
                .agentContext(context)
                .timestamp(System.currentTimeMillis())
                .type(type)
                .payload(payload)
                .build();
    }
}
