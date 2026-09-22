package com.stioc.cute.engine.event;

import com.stioc.cute.engine.common.StreamBufferType;
import com.stioc.cute.engine.event.types.AgentEvent;
import com.stioc.cute.engine.event.types.AgentEventType;
import com.stioc.cute.engine.event.types.ListenerTier;
import com.stioc.cute.engine.event.types.StreamChunkPayload;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessagePatch;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;

/**
 * 引擎第三层：流式内容缓冲维护监听器（NOTIFICATION）。
 * <p>
 * 专责维护 AgentContext 上的流式内容缓存（StreamBufferHolder）：
 * <ul>
 *   <li>流式事件：带 messageId 且有实际内容时追加缓存；归属新 messageId 时自动清空重写</li>
 *   <li>ASSISTANT 消息终态更新（SUCCESS/FAILED/CANCELED）：按 messageId 清除思考/正文缓存，
 *       此后由 DB 全量数据兜底展示</li>
 *   <li>TOOL 消息终态更新：清除工具日志缓存并推进水位线，拦截后台进程的迟到日志</li>
 * </ul>
 * 本监听器固定 priority=0（先于宿主同层监听器执行），宿主第三层监听器
 * （如 WebSocket 网络外推）无需再关心缓存维护，仅消费事件自身载荷外推即可。
 * <p>
 * 线程模型：网络型流式事件（思考流/正文流）在非极速模式下由循环线程同步直调、在极速模式
 * （默认）下与其他第三层事件同样经同会话车道串行消费。两种模式下，《追加在先、清空在后》
 * 的顺序均由事件发布顺序与「同会话恒落同一车道」共同保证。
 * 工具日志流的写入方为子进程 stdout 读取线程（非车道线程），与清空方不同线程，
 * 靠桶内 synchronized 保证读写不撕裂；终态后的迟到 chunk 由水位线统一拦截。
 * </p>
 */
public class EngineNotificationListener implements AgentEventListener {

    @Override
    public ListenerTier getTier() {
        return ListenerTier.NOTIFICATION;
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (event == null || event.getType() == null || event.getAgentContext() == null) {
            return;
        }

        AgentEventType type = event.getType();
        if (type == AgentEventType.AGENT_THINKING_STREAM || type == AgentEventType.AGENT_CONTENT_STREAM
                || type == AgentEventType.TOOL_LOG_STREAM) {
            handleStreamChunk(event, type);
        } else if (type == AgentEventType.MESSAGE_UPDATE) {
            handleMessageTerminal(event);
        }
    }

    /**
     * 流式切片：带消息 ID 且有实际内容时追加进对应缓冲桶；
     * 清空信号（{@code type=CLEAR}）则丢弃该消息已累积的缓存，从零重新累积。
     * <p>
     * 清空判断必须前置于「空文本即忽略」的过滤：清空信号本身不携带文本，
     * 若按常规空包处理会被静默跳过，导致运行中刷新页面时回填到重试前的重复内容。
     * </p>
     */
    private void handleStreamChunk(AgentEvent event, AgentEventType type) {
        if (!(event.getPayload() instanceof StreamChunkPayload chunk)) {
            return;
        }
        Long messageId = chunk.getId();
        StreamBufferType bufferType = switch (type) {
            case AGENT_THINKING_STREAM -> StreamBufferType.THINKING;
            case AGENT_CONTENT_STREAM -> StreamBufferType.CONTENT;
            default -> StreamBufferType.TOOL_LOG;
        };

        // 清空信号：丢弃已累积内容（透明重试重放前发出），仅作用于本条流
        if (chunk.isClear()) {
            event.getAgentContext().clearStreamChunk(bufferType, messageId);
            return;
        }

        String text = chunk.getText();
        if (messageId == null || text == null || text.isEmpty()) {
            return;
        }
        event.getAgentContext().appendStreamChunk(bufferType, messageId, text);
    }

    /**
     * 消息终态：ASSISTANT 进入终态时清除思考/正文缓存，TOOL 进入终态时清除工具日志缓存并推进水位线。
     */
    private void handleMessageTerminal(AgentEvent event) {
        Long messageId = null;
        MessageRole role = null;
        MessageStatus status = null;

        if (event.getPayload() instanceof MessagePatch patch) {
            messageId = patch.getId();
            role = patch.get(Message::getRole);
            status = patch.get(Message::getStatus);
        } else if (event.getPayload() instanceof Message messageEntity) {
            messageId = messageEntity.getId();
            role = messageEntity.getRole();
            status = messageEntity.getStatus();
        }

        if (messageId == null) {
            return;
        }

        boolean isTerminal = MessageStatus.SUCCESS == status || MessageStatus.FAILED == status
                || MessageStatus.CANCELED == status || MessageStatus.REJECTED == status;
        if (!isTerminal) {
            return;
        }

        if (MessageRole.ASSISTANT == role) {
            // 助手终态：清除思考/正文缓存，此后由 DB 全量数据兜底展示
            event.getAgentContext().clearStreamBuffers(messageId);
        } else if (MessageRole.TOOL == role) {
            // 工具终态：清除日志缓存并推进水位线，拦截后台进程在终态后的迟到输出
            event.getAgentContext().clearToolLogStream(messageId);
        }
    }
}
