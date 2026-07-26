package com.stioc.cute.entrypoint.websocket;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.UUID;

/**
 * WebSocket 消息处理器，主要负责心跳检测与连接动态绑定保活
 */
@Slf4j
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket 物理连接已建立, wsSessionId: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payloadStr = message.getPayload();
        log.debug("收到 WebSocket 消息: {}", payloadStr);

        try {
            WebSocketEvent event = JSON.parseObject(payloadStr, WebSocketEvent.class);
            if (event == null || event.getType() == null) {
                log.warn("收到非法 WebSocket 数据包: {}", payloadStr);
                return;
            }

            // 动态关联并绑定物理连接到业务会话 cid 上
            if (event.getCid() != null && event.getCid() != 0L) {
                WebSocketSessionManager.registerSession(event.getCid(), session);
            }

            // 心跳检测 PING 响应 PONG
            if ("PING".equalsIgnoreCase(event.getType())) {
                sendHeartbeatPong(session, event);
                return;
            }

            log.debug("接收到 WebSocket 未处理的事件类型: {}", event.getType());
        } catch (Exception e) {
            log.error("处理 WebSocket 消息异常: {}", e.getMessage(), e);
        }
    }

    private void sendHeartbeatPong(WebSocketSession session, WebSocketEvent pingEvent) throws IOException {
        WebSocketEvent pongEvent = WebSocketEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .cid(pingEvent.getCid())
                .parentCid(pingEvent.getParentCid())
                .timestamp(System.currentTimeMillis())
                .type("PONG")
                .payload(new JSONObject())
                .build();

        String jsonString = JSON.toJSONString(pongEvent);
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(jsonString));
            }
        }
        log.debug("已回复心跳 PONG");
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket 连接发生异常: {}, wsSessionId: {}", exception.getMessage(), session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket 连接已断开, wsSessionId: {}, 状态码: {}", session.getId(), status.getCode());
        WebSocketSessionManager.unregisterSession(session);
    }
}
