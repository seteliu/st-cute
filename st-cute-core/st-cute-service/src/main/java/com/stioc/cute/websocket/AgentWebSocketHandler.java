package com.stioc.cute.websocket;

import com.stioc.cute.engine.common.JsonKit;
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
        // 连接建立即登记进全量连接集：未绑定任何 cid 的连接也能天然接收全局广播
        // （会话列表增删、项目/配置变更等），后续按需通过 PING 帧绑定具体会话
        WebSocketSessionManager.trackConnection(session);
        log.info("WebSocket 物理连接已建立, wsSessionId: {}, 当前全量连接数: {}",
                session.getId(), WebSocketSessionManager.allSessionsSize());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payloadStr = message.getPayload();
        log.debug("收到 WebSocket 消息: {}", payloadStr);

        try {
            WebSocketEvent event = JsonKit.parseObject(payloadStr, WebSocketEvent.class);
            if (event == null || event.getType() == null) {
                log.warn("收到非法 WebSocket 数据包: {}", payloadStr);
                return;
            }

            // 动态关联并绑定物理连接到业务会话 cid 上（累积绑定：一个连接可同时归属多个 cid）。
            // 是否在切换时解绑旧会话由前端决策：PING 帧可携带可选的 unbindCid 先解绑再绑定，
            // 防止切走会话的流式帧继续推给本连接形成洪峰；多会话并行场景省略 unbindCid 即可保留多个绑定
            if (event.getUnbindCid() != null && event.getUnbindCid() != 0L) {
                WebSocketSessionManager.unbindSession(event.getUnbindCid(), session);
            }
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

        String jsonString = JsonKit.toJson(pongEvent);
        // 心跳 PONG 统一经发送装饰器发出：与事件推送共享同一连接的发送缓冲队列，
        // 避免 PONG 与事件消息并发直写同一物理连接造成 WS 协议帧交错
        WebSocketSession decorator = WebSocketSessionManager.wrapSession(session);
        if (decorator.isOpen()) {
            decorator.sendMessage(new TextMessage(jsonString));
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
