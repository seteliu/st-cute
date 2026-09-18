package com.stioc.cute.engine.event;

import com.stioc.cute.engine.event.types.AgentEvent;
import com.stioc.cute.engine.event.types.AgentEventType;
import com.stioc.cute.engine.event.types.ListenerTier;
import com.stioc.cute.engine.event.types.StreamChunkPayload;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessagePatch;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;

/**
 * 引擎第三层：流式内容缓冲维护监听器（NOTIFICATION，异步串行）。
 * <p>
 * 专责维护 AgentContext 上的思考流/正文流缓存（StreamBufferHolder）：
 * <ul>
 *   <li>流式事件：带 messageId 且有实际内容时追加缓存；归属新 messageId 时自动清空重写</li>
 *   <li>ASSISTANT 消息终态更新（SUCCESS/FAILED/CANCELED）：按 messageId 清除缓存，
 *       此后由 DB 全量数据兜底展示</li>
 * </ul>
 * 本监听器固定 priority=0（先于宿主同层监听器执行），宿主第三层监听器
 * （如 WebSocket 网络外推）无需再关心缓存维护，仅消费事件自身载荷外推即可。
 * 写-写串行由通知层单线程（notify-serial）天然保证，快照读取由缓存内部锁防撕裂。
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
        if (type == AgentEventType.AGENT_THINKING_STREAM || type == AgentEventType.AGENT_CONTENT_STREAM) {
            handleStreamChunk(event, type);
        } else if (type == AgentEventType.MESSAGE_UPDATE) {
            handleMessageTerminal(event);
        }
    }

    /**
     * 流式切片：带 messageId 且有实际内容时追加进对应缓冲桶。
     * 纯空信号包（无文本）与工具日志流不触碰缓存。
     */
    private void handleStreamChunk(AgentEvent event, AgentEventType type) {
        if (!(event.getPayload() instanceof StreamChunkPayload chunk)) {
            return;
        }
        Long messageId = chunk.getMessageId();
        String text = chunk.getText();
        if (messageId == null || text == null || text.isEmpty()) {
            return;
        }
        boolean isReasoning = type == AgentEventType.AGENT_THINKING_STREAM;
        event.getAgentContext().appendStreamChunk(isReasoning, messageId, text);
    }

    /**
     * 消息终态：ASSISTANT 消息进入 SUCCESS/FAILED/CANCELED 时按 messageId 清除缓存。
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

        if (messageId != null && MessageRole.ASSISTANT == role
                && (MessageStatus.SUCCESS == status || MessageStatus.FAILED == status || MessageStatus.CANCELED == status)) {
            event.getAgentContext().clearStreamBuffers(messageId);
        }
    }
}
