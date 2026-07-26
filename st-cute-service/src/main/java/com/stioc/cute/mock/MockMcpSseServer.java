package com.stioc.cute.mock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring 管理的 MCP 模拟服务，用于处理 SSE (Server-Sent Events) 长连接。
 */
@Slf4j
@Component
public class MockMcpSseServer {

    private final MockMcpHandler handler = new MockMcpHandler();
    private final Map<String, SseEmitter> sseSessions = new ConcurrentHashMap<>();

    /**
     * 注册一个新的 SSE 客户端长连接
     *
     * @param emitter Spring 的 SseEmitter 实例
     * @param baseUrl 服务的绝对根 URL
     * @return 分配的会话 ID (sessionId)
     */
    public String registerSseClient(SseEmitter emitter, String baseUrl) {
        String sessionId = UUID.randomUUID().toString();
        sseSessions.put(sessionId, emitter);

        // 注册连接销毁回调，以防内存泄漏
        emitter.onCompletion(() -> sseSessions.remove(sessionId));
        emitter.onTimeout(() -> sseSessions.remove(sessionId));
        emitter.onError(e -> sseSessions.remove(sessionId));

        try {
            // 根据 MCP SSE 规范，建立连接后必须立即向客户端推送包含 message POST 端点的 endpoint 事件
            // 使用绝对 URL 以防客户端路径解析问题
            emitter.send(SseEmitter.event()
                    .name("endpoint")
                    .data(baseUrl + "/api/mock/mcp/message?sessionId=" + sessionId));
        } catch (IOException e) {
            log.error("推送 SSE 初始 endpoint 事件异常, sessionId: {}", sessionId, e);
            emitter.completeWithError(e);
            sseSessions.remove(sessionId);
        }

        return sessionId;
    }

    /**
     * 处理特定 SSE 会话发送过来的 JSON-RPC 消息，并通过 SSE 异步推送响应
     *
     * @param sessionId   会话 ID
     * @param requestBody JSON-RPC 请求报文
     */
    public void handleSseMessage(String sessionId, String requestBody) {
        SseEmitter emitter = sseSessions.get(sessionId);
        if (emitter == null) {
            log.warn("未找到活跃的 MCP SSE 会话, sessionId: {}", sessionId);
            return;
        }

        handler.handleMessage(requestBody, responseJson -> {
            try {
                // 根据 MCP SSE 规范，所有的 JSON-RPC 响应需作为名为 'message' 的事件推送回客户端
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(responseJson));
            } catch (IOException e) {
                log.error("通过 SSE 推送 JSON-RPC 响应异常, sessionId: {}", sessionId, e);
                emitter.completeWithError(e);
                sseSessions.remove(sessionId);
            }
        });
    }
}
