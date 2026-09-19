package com.stioc.cute.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * 管理当前 WebSocket 对话及智能体生命周期上下文中的会话状态映射
 * <p>
 * 所有物理连接在注册时统一经 {@link ConcurrentWebSocketSessionDecorator} 包装：
 * 内部提供每连接发送缓冲队列与发送超时上限，彻底消除调用线程（引擎通知线程等）
 * 因慢客户端 TCP 缓冲区满而被同步 IO 长时间阻塞的问题；超限的慢连接会被装饰器
 * 自动关闭，配合本类的失败主动移除策略完成死连接清理。
 * </p>
 */
@Slf4j
public class WebSocketSessionManager {

    /**
     * 单条消息发送超时上限（毫秒）：超时即判定为慢连接，装饰器自动关闭该会话
     */
    private static final int SEND_TIME_LIMIT_MS = 5_000;

    /**
     * 每连接发送缓冲区大小上限（字节）：积压超限判定为慢连接，装饰器自动关闭该会话
     */
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;

    /**
     * 装饰器实例在 session attributes 中的存储键：
     * 保证同一物理连接全局唯一装饰器实例（发送缓冲不分裂），心跳 PONG 等直发场景复用同一装饰器
     */
    private static final String DECORATOR_ATTRIBUTE_KEY = "WS_SESSION_DECORATOR";

    /**
     * 业务 cid 对应的长连活跃物理 WebSocket 会话连接集合 Map 映射（集合内为装饰器实例）
     */
    private static final Map<Long, Set<WebSocketSession>> activeSessions = new ConcurrentHashMap<>();

    /**
     * 注册/关联业务会话与物理 WebSocketSession
     * <p>注册时对物理连接做发送装饰器包装（幂等：同一连接重复注册复用既有装饰器）。</p>
     */
    public static void registerSession(Long cid, WebSocketSession session) {
        if (cid != null && session != null) {
            activeSessions.computeIfAbsent(cid, k -> new CopyOnWriteArraySet<>()).add(wrapSession(session));
            log.debug("注册 WebSocket 业务对话会话映射: cid -> {}, wsSessionId -> {}, 当前连接数: {}",
                    cid, session.getId(), activeSessions.get(cid).size());
        }
    }

    /**
     * 根据物理 WebSocketSession 注销已建立的映射
     */
    public static void unregisterSession(WebSocketSession session) {
        if (session != null) {
            String wsSessionId = session.getId();
            activeSessions.forEach((cid, sessionSet) -> {
                boolean removed = sessionSet.removeIf(s -> wsSessionId.equals(s.getId()));
                if (removed) {
                    log.info("注销 WebSocket 业务对话会话映射: cid -> {}, wsSessionId -> {}, 剩余连接数: {}",
                            cid, wsSessionId, sessionSet.size());
                }
            });
            // 清理空的 Set，防止内存溢出
            activeSessions.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }
    }

    /**
     * 获取业务 cid 对应的所有活跃长连 WebSocketSession 集合
     */
    public static Set<WebSocketSession> getSessions(Long cid) {
        if (cid == null) {
            return Collections.emptySet();
        }
        Set<WebSocketSession> sessions = activeSessions.get(cid);
        return sessions != null ? sessions : Collections.emptySet();
    }

    /**
     * 广播发送原始 JSON 消息至对应 cid 的所有活跃物理 WebSocketSession 连接。
     * <p>发送经装饰器缓冲队列异步化：发送失败或慢连接超限被关闭的会话，立即从集合中主动移除，
     * 防止死连接残留累积。</p>
     */
    public static void sendEvent(Long cid, String textJson) {
        if (cid == null || cid == 0L || textJson == null) {
            return;
        }
        Set<WebSocketSession> sessions = getSessions(cid);
        if (sessions.isEmpty()) {
            return;
        }
        for (WebSocketSession s : sessions) {
            sendAndPruneOnFailure(cid, s, textJson, false);
        }
    }

    /**
     * 广播发送原始 JSON 消息至所有活跃的物理 WebSocketSession 连接
     */
    public static void broadcast(String textJson) {
        if (textJson == null) {
            return;
        }
        activeSessions.values().forEach(sessions -> sessions.forEach(s -> sendAndPruneOnFailure(null, s, textJson, true)));
    }

    /**
     * 经装饰器发送单条消息，失败（连接异常/发送超时被关闭）时主动从集合中移除该连接
     *
     * @param cid          归属业务会话 ID（广播场景无归属传 null）
     * @param session      装饰器包装的会话实例
     * @param textJson     消息 JSON 文本
     * @param isBroadcast  是否为全局广播场景（仅用于日志区分）
     */
    private static void sendAndPruneOnFailure(Long cid, WebSocketSession session, String textJson, boolean isBroadcast) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(textJson));
        } catch (Exception e) {
            log.warn("通过 WebSocket 发送{}消息到物理会话失败，主动移除该连接: cid={}, sessionId={}, 异常={}",
                    isBroadcast ? "全局广播" : "", cid, session.getId(), e.getMessage());
            pruneSession(cid, session);
        }
    }

    /**
     * 从归属 cid 的集合中移除指定连接（CopyOnWriteArraySet 支持并发安全删除）
     */
    private static void pruneSession(Long cid, WebSocketSession session) {
        if (cid != null) {
            Set<WebSocketSession> sessions = activeSessions.get(cid);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    activeSessions.remove(cid, sessions);
                }
            }
            return;
        }
        // 广播场景未知归属：全局扫描按会话 ID 移除
        String wsSessionId = session.getId();
        activeSessions.forEach((key, sessionSet) -> sessionSet.removeIf(s -> wsSessionId.equals(s.getId())));
        activeSessions.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /**
     * 获取（或惰性创建并缓存）物理连接对应的发送装饰器实例。
     * <p>装饰器缓存在 session attributes 中，保证同一物理连接全生命周期内复用同一实例，
     * 所有写入路径（事件推送、心跳 PONG）共享同一条发送缓冲队列，避免并发写连接。</p>
     */
    public static WebSocketSession wrapSession(WebSocketSession session) {
        Object cached = session.getAttributes().get(DECORATOR_ATTRIBUTE_KEY);
        if (cached instanceof WebSocketSession decorator) {
            return decorator;
        }
        WebSocketSession decorator = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT);
        session.getAttributes().put(DECORATOR_ATTRIBUTE_KEY, decorator);
        return decorator;
    }
}
