package com.stioc.cute.agent.event;

import com.stioc.cute.conversation.access.ConversationEntity;
import com.stioc.cute.message.access.MessageVo;
import com.stioc.cute.message.access.MessageEntity;
import com.stioc.cute.repository.ConversationMapper;
import com.stioc.cute.repository.MessageMapper;
import com.alibaba.fastjson2.JSON;
import com.stioc.cute.entrypoint.websocket.WebSocketEvent;
import com.stioc.cute.entrypoint.websocket.WebSocketSessionManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 第三层：WebSocket 物理网络外推监听器 (Order = 3)
 * 职责：非阻塞式（静默异常），将核心业务事件/请求变动翻译映射为前端约定的 WebSocket 物理传输协议帧（S2C_xxx）推送出去。
 */
@Component
@Slf4j
public class EventListenerWebSocket implements AgentEventListener {

    @Resource
    private ConversationMapper conversationMapper;
    @Resource
    private MessageMapper messageMapper;

    @Override
    public ListenerTier getTier() {
        return ListenerTier.NOTIFICATION;
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (event == null || event.getType() == null) {
            return;
        }

        // 1. 在最前面映射并拦截无需外推的内部控制命令
        String wsType = mapToWsType(event.getType());
        if (wsType == null) {
            return;
        }

        Object payload = event.getPayload();

        // 2. 局部微调各事件的 payload 载荷对象
        if (event.getType() == AgentEventType.CONVERSATION_UPDATE) {
            if (payload instanceof ConversationEntity entity) {
                // 会话更新：查出数据库中最新最完整的实体
                ConversationEntity latest = conversationMapper.selectOneById(entity.getId());
                if (latest != null) {
                    payload = latest;
                }
            }
        } else if (event.getType() == AgentEventType.MESSAGE_UPDATE) {
            if (payload instanceof MessageEntity messageEntity) {
                // 消息更新：查出数据库中最新最完整的实体并转为 VO 传输
                MessageEntity latestMsg = messageMapper.selectOneById(messageEntity.getId());
                MessageEntity finalMsg = latestMsg != null ? latestMsg : messageEntity;
                if (Boolean.FALSE.equals(finalMsg.getVisibleToUser())) {
                    return;
                }
                payload = MessageVo.fromEntity(finalMsg, true);
            }
        } else if (event.getType() == AgentEventType.MESSAGE_CREATE) {
            if (payload instanceof MessageEntity messageEntity) {
                if (Boolean.FALSE.equals(messageEntity.getVisibleToUser())) {
                    return;
                }
                // 消息创建：直接转为 VO 传输，免去无意义查库开销
                payload = MessageVo.fromEntity(messageEntity, true);
            }
        }

        // 3. 统一在最末尾进行网络帧外推
        sendWsFrame(event, wsType, payload);
    }

    private void sendWsFrame(AgentEvent event, String wsType, Object payload) {
        try {
            WebSocketEvent wsEvent = WebSocketEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .cid(event.getAgentContext().getCid())
                    .parentCid(event.getAgentContext().getParentCid())
                    .timestamp(event.getTimestamp())
                    .type(wsType)
                    .payload(payload)
                    .build();

            Long targetCid = event.getAgentContext().getParentCid() != null && event.getAgentContext().getParentCid() != 0L
                    ? event.getAgentContext().getParentCid()
                    : event.getAgentContext().getCid();

            WebSocketSessionManager.sendEvent(targetCid, JSON.toJSONString(wsEvent));
        } catch (Exception e) {
            log.error("WebSocket 物理端口翻译外推事件出错, wsType={}, cid={}", wsType, event.getAgentContext().getCid(), e);
        }
    }

    private String mapToWsType(AgentEventType type) {
        return switch (type) {
            case MESSAGE_CREATE -> "S2C_MESSAGE_CREATED";
            case MESSAGE_UPDATE -> "S2C_MESSAGE_UPDATED";
            case CONVERSATION_CREATE -> "S2C_CONVERSATION_CREATED";
            case CONVERSATION_UPDATE -> "S2C_CONVERSATION_UPDATED";
            case AGENT_THINKING_STREAM -> "S2C_THINKING_STREAM";
            case AGENT_CONTENT_STREAM -> "S2C_CONTENT_STREAM";
            case TOOL_LOG_STREAM -> "S2C_TOOL_LOG_STREAM";
            case CONVERSATION_DELETE -> "S2C_CONVERSATION_DELETED";
            case MESSAGE_DELETE -> "S2C_MESSAGE_DELETED";
            default -> null; // 内部控制命令，静默不推送
        };
    }

}
