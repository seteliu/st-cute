package com.stioc.cute.websocket;

import com.stioc.cute.engine.event.types.AgentEvent;
import com.stioc.cute.engine.event.AgentEventListener;
import com.stioc.cute.engine.event.types.AgentEventType;
import com.stioc.cute.engine.event.types.ListenerTier;

import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.message.types.MessageVo;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessagePatch;
import com.stioc.cute.engine.common.JsonKit;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 第三层：WebSocket 物理网络外推监听器 (Order = 3)
 * 职责：非阻塞式（静默异常），将核心业务事件/请求变动翻译映射为前端约定的 WebSocket 物理传输协议帧（S2C_xxx）推送出去。
 * <p>
 * 纯外推定位：流式内容的缓存维护（思考流/正文流缓冲桶）已收口至引擎内置的
 * EngineNotificationListener（同层 priority=0，先于本监听器执行），
 * 本监听器不再承担任何缓存读写职责。
 * </p>
 */
@Component
@Slf4j
public class RuntimeEventListenerWebSocket implements AgentEventListener {

    private static final String BROADCAST_TYPE = "_BROADCAST_";

    @Resource
    @Lazy
    private AgentEngine agentEngine;
    @Resource
    private WebSocketBroadcast webSocketBroadcast;

    @Override
    public ListenerTier getTier() {
        return ListenerTier.NOTIFICATION;
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (event == null || event.getType() == null) {
            return;
        }

        // 1. 映射并拦截无需外推的内部控制命令
        String wsType = mapToWsType(event.getType());
        if (wsType == null) {
            return;
        }

        // 2. 会话新增与更新为虚拟广播类型，统一委托广播契约全局外推，不再走 sendWsFrame 定向处理
        if (BROADCAST_TYPE.equals(wsType)) {
            handleConversationBroadcast(event);
            return;
        }

        Object payload = event.getPayload();

        // 3. 局部微调各定向事件的 payload 载荷对象
        if (event.getType() == AgentEventType.MESSAGE_UPDATE) {
            Long msgId = null;
            Message messageEntity = null;
            if (payload instanceof MessagePatch patch) {
                msgId = patch.getId();
            } else if (payload instanceof Message entity) {
                msgId = entity.getId();
                messageEntity = entity;
            }
            if (msgId != null) {
                // 消息更新：查出数据库中最新最完整的实体并转为 VO 传输
                Message latestMsg = agentEngine.getMessageStore().getById(msgId);
                Message finalMsg = latestMsg != null ? latestMsg : messageEntity;
                if (finalMsg != null && Boolean.FALSE.equals(finalMsg.getVisibleToUser())) {
                    return;
                }
                if (finalMsg != null) {
                    payload = MessageVo.fromEntity(finalMsg);
                }
            }
        } else if (event.getType() == AgentEventType.MESSAGE_CREATE) {
            if (payload instanceof Message messageEntity) {
                if (Boolean.FALSE.equals(messageEntity.getVisibleToUser())) {
                    return;
                }
                // 消息创建：直接转为 VO 传输，免去无意义查库开销
                payload = MessageVo.fromEntity(messageEntity);
            }
        }

        // 4. 统一在最末尾进行网络帧外推
        sendWsFrame(event, wsType, payload);
    }

    /**
     * 处理会话级别的新建、更新与删除全局广播（驱动各端会话列表增加新项、改名、更新时间、running 状态监控、物理删除等）
     */
    private void handleConversationBroadcast(AgentEvent event) {
        Object payload = event.getPayload();
        if (event.getType() == AgentEventType.CONVERSATION_CREATE) {
            if (payload instanceof Conversation entity) {
                webSocketBroadcast.broadcast(WebSocketBroadcast.EventType.CONVERSATION_CREATED, entity.getId(), entity);
            }
        } else if (event.getType() == AgentEventType.CONVERSATION_UPDATE) {
            Long cid = null;
            if (payload instanceof ConversationPatch patch) {
                cid = patch.getId();
            } else if (payload instanceof Conversation entity) {
                cid = entity.getId();
            } else if (event.getAgentContext() != null) {
                cid = event.getAgentContext().getCid();
            }
            if (cid != null) {
                // 会话更新（包括改名、运行态、Token、权限等）：查出最新完整实体进行全局广播
                Conversation latest = agentEngine.getConversationStore().getById(cid);
                if (latest != null) {
                    webSocketBroadcast.broadcast(WebSocketBroadcast.EventType.CONVERSATION_UPDATED, cid, latest);
                }
            }
        } else if (event.getType() == AgentEventType.CONVERSATION_DELETE) {
            Long cid = null;
            if (payload instanceof Long id) {
                cid = id;
            } else if (event.getAgentContext() != null) {
                cid = event.getAgentContext().getCid();
            }
            if (cid != null) {
                webSocketBroadcast.broadcast(WebSocketBroadcast.EventType.CONVERSATION_DELETED, cid, cid);
            }
        }
    }

    private void sendWsFrame(AgentEvent event, String wsType, Object payload) {
        try {
            // 没有 AgentContext 直接 return，不兜底 cid
            if (event == null || event.getAgentContext() == null) {
                log.warn("RuntimeEventListenerWebSocket: 缺少 AgentContext，放弃外推 wsType={}", wsType);
                return;
            }

            Long cid = event.getAgentContext().getCid();
            Long parentCid = event.getAgentContext().getParentCid();
            boolean isSubAgent = event.getAgentContext().isSubAgent();

            WebSocketEvent wsEvent = WebSocketEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .cid(cid)
                    .parentCid(parentCid)
                    .timestamp(event.getTimestamp())
                    .type(wsType)
                    .payload(payload)
                    .build();

            Long targetCid = isSubAgent ? parentCid : cid;
            if (targetCid != null) {
                WebSocketSessionManager.sendEvent(targetCid, JsonKit.toJson(wsEvent));
            }
        } catch (Exception e) {
            log.error("WebSocket 物理端口翻译外推事件出错, wsType={}, cid={}",
                    wsType, event.getAgentContext() != null ? event.getAgentContext().getCid() : null, e);
        }
    }

    private String mapToWsType(AgentEventType type) {
        return switch (type) {
            case MESSAGE_CREATE -> "S2C_MESSAGE_CREATED";
            case MESSAGE_UPDATE -> "S2C_MESSAGE_UPDATED";
            case CONVERSATION_CREATE, CONVERSATION_UPDATE, CONVERSATION_DELETE -> BROADCAST_TYPE;
            case AGENT_THINKING_STREAM -> "S2C_THINKING_STREAM";
            case AGENT_CONTENT_STREAM -> "S2C_CONTENT_STREAM";
            case TOOL_LOG_STREAM -> "S2C_TOOL_LOG_STREAM";
            case MESSAGE_DELETE -> "S2C_MESSAGE_DELETED";
            default -> null; // 内部控制命令，静默不推送
        };
    }

}
