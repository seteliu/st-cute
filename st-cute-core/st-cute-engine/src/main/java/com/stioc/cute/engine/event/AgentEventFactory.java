package com.stioc.cute.engine.event;

import com.stioc.cute.engine.event.types.AgentEvent;
import com.stioc.cute.engine.event.types.AgentEventType;
import com.stioc.cute.engine.event.types.StreamChunkPayload;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessagePatch;

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
     * 创建会话属性更新类型事件（差量载荷：仅携带变化字段）
     */
    public static AgentEvent createConversationUpdate(AgentContext context, ConversationPatch payload) {
        return build(context, AgentEventType.CONVERSATION_UPDATE, payload);
    }

    /**
     * 创建消息落库创建类型事件
     */
    public static AgentEvent createMessageCreate(AgentContext context, Message entity) {
        return build(context, AgentEventType.MESSAGE_CREATE, entity);
    }

    /**
     * 创建消息状态/内容更新类型事件（差量载荷：仅携带变化字段）
     */
    public static AgentEvent createMessageUpdate(AgentContext context, MessagePatch payload) {
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
    public static AgentEvent createMessageDelete(AgentContext context, Message payload) {
        return build(context, AgentEventType.MESSAGE_DELETE, payload);
    }

    /**
     * 创建大模型思考过程流式输出片段事件
     */
    public static AgentEvent createThinkingStream(AgentContext context, Long messageId, String chunkContent) {
        return build(context, AgentEventType.AGENT_THINKING_STREAM, new StreamChunkPayload(messageId, chunkContent));
    }

    /**
     * 创建「思考流清空」控制信号事件（透明重试重放前发出，指示下游丢弃已累积的思考内容）
     */
    public static AgentEvent createThinkingStreamClear(AgentContext context, Long messageId) {
        return build(context, AgentEventType.AGENT_THINKING_STREAM, StreamChunkPayload.clear(messageId));
    }

    /**
     * 创建大模型正文流式输出片段事件
     */
    public static AgentEvent createContentStream(AgentContext context, Long messageId, String chunkContent) {
        return build(context, AgentEventType.AGENT_CONTENT_STREAM, new StreamChunkPayload(messageId, chunkContent));
    }

    /**
     * 创建「正文流清空」控制信号事件（透明重试重放前发出，指示下游丢弃已累积的正文内容）
     */
    public static AgentEvent createContentStreamClear(AgentContext context, Long messageId) {
        return build(context, AgentEventType.AGENT_CONTENT_STREAM, StreamChunkPayload.clear(messageId));
    }

    /**
     * 创建工具运行日志控制台增量输出流事件
     *
     * @param messageId 工具消息 ID（与助手流统一按消息 ID 归属）
     * @param text      增量日志文本
     */
    public static AgentEvent createToolLogStream(AgentContext context, Long messageId, String text) {
        return build(context, AgentEventType.TOOL_LOG_STREAM, new StreamChunkPayload(messageId, text));
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
