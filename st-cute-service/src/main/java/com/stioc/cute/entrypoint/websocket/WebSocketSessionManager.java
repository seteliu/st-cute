package com.stioc.cute.entrypoint.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * 管理当前 WebSocket 对话及智能体生命周期上下文中的会话状态映射
 */
@Slf4j
public class WebSocketSessionManager {

    /**
     * 业务 cid 对应的长连活跃物理 WebSocket 会话连接集合 Map 映射
     */
    private static final Map<Long, Set<WebSocketSession>> activeSessions = new ConcurrentHashMap<>();

    /**
     * 注册/关联业务会话与物理 WebSocketSession
     */
    public static void registerSession(Long cid, WebSocketSession session) {
        if (cid != null && session != null) {
            activeSessions.computeIfAbsent(cid, k -> new CopyOnWriteArraySet<>()).add(session);
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
        if (cid == null) return Collections.emptySet();
        Set<WebSocketSession> sessions = activeSessions.get(cid);
        return sessions != null ? sessions : Collections.emptySet();
    }

    /**
     * 广播发送原始 JSON 消息至对应 cid 的所有活跃物理 WebSocketSession 连接
     * 自动处理空值、物理连接状态判断、并发 synchronized 发送并具备极高容错性（忽略并捕获所有发送异常）
     */
    public static void sendEvent(Long cid, String textJson) {
        if (cid == null || cid == 0L || textJson == null) {
            return;
        }
        Set<WebSocketSession> sessions = getSessions(cid);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (WebSocketSession s : sessions) {
            if (s != null && s.isOpen()) {
                synchronized (s) {
                    try {
                        if (s.isOpen()) {
                            s.sendMessage(new TextMessage(textJson));
                        }
                    } catch (Exception e) {
                        log.warn("通过 WebSocket 发送消息到物理会话失败: cid={}, sessionId={}, 异常={}", cid, s.getId(), e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 广播发送原始 JSON 消息至所有活跃的物理 WebSocketSession 连接
     */
    public static void broadcast(String textJson) {
        if (textJson == null) {
            return;
        }
        activeSessions.values().forEach(sessions -> {
            if (sessions != null) {
                for (WebSocketSession s : sessions) {
                    if (s != null && s.isOpen()) {
                        synchronized (s) {
                            try {
                                if (s.isOpen()) {
                                    s.sendMessage(new TextMessage(textJson));
                                }
                            } catch (Exception e) {
                                log.warn("通过 WebSocket 发送全局广播消息到物理会话失败: sessionId={}, 异常={}", s.getId(), e.getMessage());
                            }
                        }
                    }
                }
            }
        });
    }
}
