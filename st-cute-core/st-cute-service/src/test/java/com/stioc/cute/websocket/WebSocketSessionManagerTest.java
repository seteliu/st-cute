package com.stioc.cute.websocket;

import com.stioc.cute.testkit.FakeWebSocketSession;
import com.stioc.cute.testkit.WsTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebSocketSessionManager} 静态注册表行为测试。
 * <p>
 * 覆盖：register/unbind 累积绑定与解绑、unbind 不影响全量连接、sendEvent 定向送达、
 * 发送失败主动剔除（连接从全量集与 cid 桶同时消失）、空桶回收、broadcast 每连接一份。
 * </p>
 */
class WebSocketSessionManagerTest extends WsTestSupport.WsAdapter {

    @AfterEach
    void sweepRegistry() {
        sweep();
    }

    @Test
    @DisplayName("registerSession：cid 桶累积绑定，getSessions 返回该桶全部连接")
    void registerAccumulatesIntoCidBucket() {
        FakeWebSocketSession first = newTrackedSession(100L);
        FakeWebSocketSession second = newTrackedSession(100L);

        assertEquals(2, WebSocketSessionManager.getSessions(100L).size());
    }

    @Test
    @DisplayName("一个连接可同时绑定多个 cid（多会话并行订阅），unbind 单个 cid 不影响其余绑定")
    void oneSessionBindsMultipleCids() {
        FakeWebSocketSession session = newTrackedSession();
        WebSocketSessionManager.registerSession(100L, session);
        WebSocketSessionManager.registerSession(200L, session);

        assertEquals(1, WebSocketSessionManager.getSessions(100L).size());
        assertEquals(1, WebSocketSessionManager.getSessions(200L).size());

        // 解绑 100：仅该桶移除，200 的绑定与全量连接登记保留
        WebSocketSessionManager.unbindSession(100L, session);
        assertTrue(WebSocketSessionManager.getSessions(100L).isEmpty());
        assertEquals(1, WebSocketSessionManager.getSessions(200L).size());
    }

    @Test
    @DisplayName("unbindSession：桶内最后一个连接移除后空桶被回收（getSessions 返回空集）")
    void unbindRecyclesEmptyBucket() {
        FakeWebSocketSession session = newTrackedSession(100L);
        WebSocketSessionManager.unbindSession(100L, session);

        assertTrue(WebSocketSessionManager.getSessions(100L).isEmpty());
    }

    @Test
    @DisplayName("sendEvent：仅定向送达该 cid 桶内的连接，其他 cid 与未绑定连接不收")
    void sendEventDeliversOnlyToBoundCid() {
        FakeWebSocketSession bound = newTrackedSession(100L);
        FakeWebSocketSession otherCid = newTrackedSession(200L);
        FakeWebSocketSession globalOnly = newTrackedGlobalSession();

        WebSocketSessionManager.sendEvent(100L, "{\"type\":\"S2C_X\"}");

        assertEquals(1, bound.receivedTexts().size());
        assertTrue(otherCid.receivedTexts().isEmpty());
        assertTrue(globalOnly.receivedTexts().isEmpty());
    }

    @Test
    @DisplayName("broadcast：全量连接集内每连接一份（含未绑定 cid 的连接）")
    void broadcastDeliversOncePerPhysicalConnection() {
        // 模拟真实连接生命周期：建连即进全量集（trackConnection），PING 后再绑定 cid（registerSession）
        FakeWebSocketSession bound = newTrackedGlobalSession();
        WebSocketSessionManager.registerSession(100L, bound);
        FakeWebSocketSession globalOnly = newTrackedGlobalSession();
        FakeWebSocketSession plain = newTrackedSession();

        WebSocketSessionManager.broadcast("{\"type\":\"S2C_BROADCAST\"}");

        assertEquals(1, bound.receivedTexts().size());
        assertEquals(1, globalOnly.receivedTexts().size());
        // 未进全量连接集也未绑定任何 cid 的连接不收广播
        assertTrue(plain.receivedTexts().isEmpty());
    }

    @Test
    @DisplayName("sendEvent 发送失败：连接从全量集与 cid 桶被主动剔除（死连接清理）")
    void sendFailurePrunesSessionEverywhere() {
        FakeWebSocketSession dead = newTrackedSession(100L);
        FakeWebSocketSession healthy = newTrackedSession(100L);

        dead.setFailOnSend(true);
        WebSocketSessionManager.sendEvent(100L, "{\"type\":\"S2C_X\"}");

        // 死连接被剔除，健康连接仍登记且收到帧
        assertEquals(1, WebSocketSessionManager.getSessions(100L).size());
        assertEquals(1, healthy.receivedTexts().size());
        assertTrue(WebSocketSessionManager.getSessions(100L).stream()
                .noneMatch(s -> s.getId().equals(dead.getId())));
    }

    @Test
    @DisplayName("broadcast 发送失败：连接同样被全量剔除")
    void broadcastFailurePrunesGlobalRegistration() {
        FakeWebSocketSession dead = newTrackedGlobalSession();
        dead.setFailOnSend(true);

        WebSocketSessionManager.broadcast("{\"type\":\"S2C_BROADCAST\"}");

        assertEquals(0, WebSocketSessionManager.allSessionsSize());
    }

    @Test
    @DisplayName("unregisterSession：连接关闭时全量清除其一切登记（全量集 + 所有 cid 桶）")
    void unregisterPurgesEverywhere() {
        FakeWebSocketSession session = newTrackedSession();
        WebSocketSessionManager.registerSession(100L, session);
        WebSocketSessionManager.registerSession(200L, session);
        WebSocketSessionManager.trackConnection(session);

        WebSocketSessionManager.unregisterSession(session);

        assertEquals(0, WebSocketSessionManager.allSessionsSize());
        assertTrue(WebSocketSessionManager.getSessions(100L).isEmpty());
        assertTrue(WebSocketSessionManager.getSessions(200L).isEmpty());
    }

    @Test
    @DisplayName("防御：null/0 cid 与 null 载荷不抛异常且不送达")
    void defensiveNullAndZeroCid() {
        FakeWebSocketSession session = newTrackedSession(100L);

        assertTrue(WebSocketSessionManager.getSessions(null).isEmpty());
        WebSocketSessionManager.sendEvent(null, "{\"type\":\"S2C_X\"}");
        WebSocketSessionManager.sendEvent(0L, "{\"type\":\"S2C_X\"}");
        WebSocketSessionManager.sendEvent(100L, null);

        assertTrue(session.receivedTexts().isEmpty());
    }
}
