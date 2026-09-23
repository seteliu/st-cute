package com.stioc.cute.websocket;

import com.stioc.cute.testkit.FakeWebSocketSession;
import com.stioc.cute.testkit.WsTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebSocketBroadcast} 全局广播契约测试。
 * <p>
 * 覆盖：广播事件类型映射、绑定与未绑定连接各收一份、type=null 静默返回、
 * 帧结构契约（eventId/timestamp/type 字段存在）。
 * </p>
 */
class WebSocketBroadcastTest extends WsTestSupport.WsAdapter {

    private final WebSocketBroadcast broadcast = new WebSocketBroadcast();

    @AfterEach
    void sweepRegistry() {
        sweep();
    }

    @Test
    @DisplayName("广播送达全量连接集内每条物理连接各一份（含未绑定 cid 的连接）")
    void broadcastReachesAllPhysicalConnections() {
        // 模拟真实连接生命周期：建连即进全量集（trackConnection），PING 后再绑定 cid（registerSession）
        FakeWebSocketSession bound = newTrackedGlobalSession();
        WebSocketSessionManager.registerSession(100L, bound);
        FakeWebSocketSession unbound = newTrackedGlobalSession();

        broadcast.broadcast(WebSocketBroadcast.EventType.PROJECT_CREATED, "demo-project");

        assertReceivedCount(bound, 1);
        assertReceivedCount(unbound, 1);
        assertEquals("S2C_PROJECT_CREATED", bound.receivedEvents().getFirst().getType());
    }

    @Test
    @DisplayName("广播帧结构契约：eventId / timestamp / type / payload 俱备")
    void broadcastFrameContract() {
        FakeWebSocketSession session = newTrackedGlobalSession();

        broadcast.broadcast(WebSocketBroadcast.EventType.CONFIG_UPDATED, 100L, "cfg");

        WebSocketEvent event = session.receivedEvents().getFirst();
        assertEquals("S2C_CONFIG_UPDATED", event.getType());
        assertEquals(100L, event.getCid());
        assertNotNull(event.getEventId());
        assertFalse(event.getEventId().isEmpty());
        assertNotNull(event.getTimestamp());
        assertTrue(event.getTimestamp() > 0);
    }

    @Test
    @DisplayName("type=null 静默返回：不发送任何帧也不抛异常")
    void nullTypeIsSilentlyIgnored() {
        FakeWebSocketSession session = newTrackedGlobalSession();

        broadcast.broadcast(null, "payload");

        assertNothingReceived(session);
    }

    @Test
    @DisplayName("payload 序列化异常不外抛：广播方静默吞掉（其余连接不受影响）")
    void payloadSerializationFailureIsSwallowed() {
        FakeWebSocketSession session = newTrackedGlobalSession();

        // 脆弱点声明：自引用对象依赖 fastjson2 对循环引用抛 JSONException 的现有行为，
        // 该行为无库级契约保证——若未来升级后序列化成功，帧会发出导致 assertNothingReceived
        // 失败报警（不会静默失真），届时需更换异常触发载体
        Object cyclic = new Object[1];
        ((Object[]) cyclic)[0] = cyclic;

        broadcast.broadcast(WebSocketBroadcast.EventType.PROJECT_DELETED, cyclic);

        assertNothingReceived(session);
    }
}
