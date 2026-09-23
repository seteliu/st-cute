package com.stioc.cute.testkit;

import com.stioc.cute.websocket.WebSocketEvent;
import com.stioc.cute.websocket.WebSocketSessionManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket 单测公共支撑接口：静态注册表登记与清扫 + 帧断言辅助。
 * <p>
 * {@code WebSocketSessionManager} 的连接登记全部落在静态字段（全量连接集 + cid 映射），
 * surefire 同 JVM 复用线程与类状态下，任何残留都会跨测试类泄漏污染。因此 ws 测试的第一纪律：
 * 每个用例接触过的伪连接统一经本接口登记，{@code @AfterEach} 中调用 {@link #sweep()} 全量注销清扫。
 * </p>
 * <p>
 * 用法：测试类继承 {@code WsTestSupport.WsAdapter}（提供登记表实例实现），
 * 并在 {@code @AfterEach} 中调用 {@link #sweep()}。
 * </p>
 */
public interface WsTestSupport {

    /**
     * 本测试接触过的伪连接登记表
     */
    List<FakeWebSocketSession> touchedSessions();

    /**
     * 登记一个既有伪连接：后续 {@link #sweep()} 统一注销
     */
    default FakeWebSocketSession track(FakeWebSocketSession session) {
        touchedSessions().add(session);
        return session;
    }

    /**
     * 新建并登记一个伪连接（仅登记，不触达静态注册表）
     */
    default FakeWebSocketSession newTrackedSession() {
        return track(new FakeWebSocketSession());
    }

    /**
     * 新建、登记并绑定到指定业务会话 cid 的伪连接
     */
    default FakeWebSocketSession newTrackedSession(Long cid) {
        FakeWebSocketSession session = track(new FakeWebSocketSession());
        WebSocketSessionManager.registerSession(cid, session);
        return session;
    }

    /**
     * 新建、登记并进全量连接集的伪连接（不绑定 cid，模拟仅收全局广播的连接）
     */
    default FakeWebSocketSession newTrackedGlobalSession() {
        FakeWebSocketSession session = track(new FakeWebSocketSession());
        WebSocketSessionManager.trackConnection(session);
        return session;
    }

    /**
     * 清扫：注销本测试登记的全部伪连接，清空登记表。
     * <p>实现类必须在 {@code @AfterEach} 中调用本方法。</p>
     */
    default void sweep() {
        for (FakeWebSocketSession session : touchedSessions()) {
            WebSocketSessionManager.unregisterSession(session);
        }
        touchedSessions().clear();
    }

    // ──────────────────────────────────────────────
    // 帧断言辅助
    // ──────────────────────────────────────────────

    /**
     * 断言伪连接收到指定类型的帧（至少一帧命中即通过；无帧时先短暂等待一帧再判定）
     */
    default void assertReceivedType(FakeWebSocketSession session, String type) {
        if (session.receivedTexts().isEmpty()) {
            session.awaitReceived(1);
        }
        List<WebSocketEvent> events = session.receivedEvents();
        boolean hit = events.stream().anyMatch(e -> type.equals(e.getType()));
        assertTrue(hit, "期望收到类型为 " + type + " 的帧, 实际收到: " + describeEvents(events));
    }

    /**
     * 断言伪连接未收到指定类型的帧
     */
    default void assertNotReceivedType(FakeWebSocketSession session, String type) {
        List<WebSocketEvent> events = session.receivedEvents();
        boolean hit = events.stream().anyMatch(e -> type.equals(e.getType()));
        assertFalse(hit, "期望不收到类型为 " + type + " 的帧, 实际收到: " + describeEvents(events));
    }

    /**
     * 断言伪连接一帧未收
     */
    default void assertNothingReceived(FakeWebSocketSession session) {
        assertTrue(session.receivedTexts().isEmpty(), "期望不收到任何帧, 实际收到: "
                + describeEvents(session.receivedEvents()));
    }

    /**
     * 断言伪连接收到恰好 n 帧
     */
    default void assertReceivedCount(FakeWebSocketSession session, int n) {
        assertEquals(n, session.receivedTexts().size(),
                "帧数不符: " + describeEvents(session.receivedEvents()));
    }

    /**
     * 描述已收帧类型清单（失败消息用）
     */
    default String describeEvents(List<WebSocketEvent> events) {
        StringBuilder sb = new StringBuilder("[");
        for (WebSocketEvent e : events) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(e != null && e.getType() != null ? e.getType() : "unparsed");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 常用适配基类：提供登记表实例实现，测试类继承它即可获得全部默认方法
     */
    abstract class WsAdapter implements WsTestSupport {

        private final List<FakeWebSocketSession> touched = new CopyOnWriteArrayList<>();

        @Override
        public List<FakeWebSocketSession> touchedSessions() {
            return touched;
        }
    }
}
