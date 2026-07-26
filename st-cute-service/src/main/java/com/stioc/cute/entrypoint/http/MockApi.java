package com.stioc.cute.entrypoint.http;

import com.stioc.cute.mock.MockMcpSseServer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 模拟 MCP 服务的 HTTP API 控制器。
 * 提供标准的 SSE 模式连接通道与消息接收端口，并统一接管 OAuth 发现探测。
 */
@Slf4j
@RestController
public class MockApi {

    @Resource
    private MockMcpSseServer mockMcpSseServer;

    /**
     * 建立客户端到服务端的 SSE 消息订阅通道
     *
     * @return 返回 SseEmitter 用于推送事件
     */
    @GetMapping("/api/mock/mcp/sse")
    public SseEmitter sse(jakarta.servlet.http.HttpServletRequest request) {
        log.info("接收到客户端 MCP SSE 连接建立请求");
        // 0L 表示不设置超时时间，长连接保持开启
        SseEmitter emitter = new SseEmitter(0L);

        // 构造当前服务器的绝对根 URL，例如 http://localhost:8080
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();
        String baseUrl = scheme + "://" + serverName + ":" + serverPort;

        mockMcpSseServer.registerSseClient(emitter, baseUrl);
        return emitter;
    }

    /**
     * 接收客户端的 JSON-RPC 请求报文
     *
     * @param sessionId   与当前客户端 SSE 通道的会话 ID 绑定
     * @param requestBody 原始 of JSON-RPC 请求文本
     * @return 返回 202 Accepted 状态，指示消息已接收，响应将通过 SSE 异步推送
     */
    @PostMapping("/api/mock/mcp/message")
    public ResponseEntity<Void> receiveMessage(
            @RequestParam String sessionId,
            @RequestBody String requestBody) {
        log.debug("接收到 MCP 客户端消息, sessionId: {}, 报文: {}", sessionId, requestBody);
        mockMcpSseServer.handleSseMessage(sessionId, requestBody);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
