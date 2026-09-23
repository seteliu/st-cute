package com.stioc.cute.websocket;

import com.stioc.cute.testkit.FakeWebSocketSession;
import com.stioc.cute.testkit.WsTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentWebSocketHandler} 生命周期与心跳协议测试（零容器伪连接）。
 * <p>
 * 覆盖：连接建立即入全量集合（未绑定 cid 也能收广播）、PING→PONG 心跳应答、
 * PING 携带 cid/unbindCid 的绑定迁移语义、多 cid 累积绑定、非法数据包吞掉不抛异常、
 * 连接关闭全量登记清除。
 * </p>
 */
class AgentWebSocketHandlerTest extends WsTestSupport.WsAdapter {

    private final AgentWebSocketHandler handler = new AgentWebSocketHandler();

    @AfterEach
    void sweepRegistry() {
        sweep();
    }

    /**
     * 构造 PING 帧 JSON（与前端心跳协议对齐：type=PING，可选携带 cid、unbindCid 与 parentCid）
     */
    private String pingJson(Long cid, Long unbindCid) {
        return pingJson(cid, unbindCid, null);
    }

    /**
     * 构造 PING 帧 JSON 全参版本
     */
    private String pingJson(Long cid, Long unbindCid, Long parentCid) {
        StringBuilder sb = new StringBuilder("{\"type\":\"PING\"");
        if (cid != null) {
            sb.append(",\"cid\":").append(cid);
        }
        if (unbindCid != null) {
            sb.append(",\"unbindCid\":").append(unbindCid);
        }
        if (parentCid != null) {
            sb.append(",\"parentCid\":").append(parentCid);
        }
        sb.append("}");
        return sb.toString();
    }

    @Test
    @DisplayName("连接建立即入全量连接集：未绑定 cid 也能接收全局广播")
    void afterConnectionEstablishedTracksIntoGlobalSet() {
        FakeWebSocketSession session = newTrackedSession();
        handler.afterConnectionEstablished(session);

        // 全量连接数为 1，且广播能送达这条未绑定 cid 的连接
        assertEquals(1, WebSocketSessionManager.allSessionsSize());
        WebSocketSessionManager.broadcast("{\"type\":\"S2C_PROJECT_CREATED\"}");
        session.awaitReceived(1);
        assertTrue(session.receivedEvents().getFirst().getType().contains("PROJECT_CREATED"));
    }

    @Test
    @DisplayName("PING 心跳应答 PONG，并回填 cid 与 parentCid")
    void pingRepliesPongWithCid() throws Exception {
        FakeWebSocketSession session = newTrackedSession();
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(pingJson(7L, null, 20L)));

        assertReceivedType(session, "PONG");
        WebSocketEvent pong = session.receivedEvents().stream()
                .filter(e -> "PONG".equals(e.getType()))
                .findFirst().orElseThrow();
        assertEquals(7L, pong.getCid());
        assertEquals(20L, pong.getParentCid());
    }

    @Test
    @DisplayName("PING 携带 unbindCid：先解绑旧会话再绑定新会话（绑定迁移）")
    void pingWithUnbindMigratesBinding() throws Exception {
        FakeWebSocketSession session = newTrackedSession();
        handler.afterConnectionEstablished(session);

        // 先绑定 cid=100
        handler.handleTextMessage(session, new TextMessage(pingJson(100L, null)));
        // 再以 unbindCid=100 + cid=200 切换
        handler.handleTextMessage(session, new TextMessage(pingJson(200L, 100L)));

        // 旧 cid 桶已清空，新 cid 桶持有该连接
        assertTrue(WebSocketSessionManager.getSessions(100L).isEmpty());
        assertEquals(1, WebSocketSessionManager.getSessions(200L).size());
    }

    @Test
    @DisplayName("多 cid 累积绑定：不携带 unbindCid 时旧绑定保留")
    void pingWithoutUnbindKeepsAccumulatedBindings() throws Exception {
        FakeWebSocketSession session = newTrackedSession();
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(pingJson(100L, null)));
        handler.handleTextMessage(session, new TextMessage(pingJson(200L, null)));

        // 两桶各持 1 条连接：一个物理连接同时订阅两个会话
        assertEquals(1, WebSocketSessionManager.getSessions(100L).size());
        assertEquals(1, WebSocketSessionManager.getSessions(200L).size());
    }

    @Test
    @DisplayName("非法数据包（JSON 破损/类型缺失）被吞掉：连接与既有绑定不受影响")
    void malformedPayloadIsSwallowed() throws Exception {
        FakeWebSocketSession session = newTrackedSession();
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(pingJson(100L, null)));
        session.awaitReceived(1);
        session.clearReceived();

        handler.handleTextMessage(session, new TextMessage("{not-json"));
        handler.handleTextMessage(session, new TextMessage("{\"eventId\":\"x\"}"));

        // 非法帧不触发任何 PONG 应答，绑定保留
        assertNotReceivedType(session, "PONG");
        assertEquals(1, WebSocketSessionManager.getSessions(100L).size());
    }

    @Test
    @DisplayName("连接关闭：全量连接集与 cid 绑定彻底清除")
    void afterConnectionClosedPurgesEverywhere() throws Exception {
        FakeWebSocketSession session = newTrackedSession();
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(pingJson(100L, null)));

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertEquals(0, WebSocketSessionManager.allSessionsSize());
        assertTrue(WebSocketSessionManager.getSessions(100L).isEmpty());
    }

    @Test
    @DisplayName("非 PING 类型帧仅做绑定处理，不外推响应帧")
    void nonPingTypeOnlyBindsWithoutReply() throws Exception {
        FakeWebSocketSession session = newTrackedSession();
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"SOME_FUTURE_TYPE\",\"cid\":300}"));

        assertNothingReceived(session);
        assertEquals(1, WebSocketSessionManager.getSessions(300L).size());
    }
}
