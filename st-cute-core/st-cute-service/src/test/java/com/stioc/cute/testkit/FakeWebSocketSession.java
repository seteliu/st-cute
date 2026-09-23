package com.stioc.cute.testkit;

import com.stioc.cute.engine.common.JsonKit;
import com.stioc.cute.websocket.WebSocketEvent;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket 测试伪连接：零容器单测的物理层替身。
 * <p>
 * 实现完整 {@link WebSocketSession} 接口，核心行为：
 * <ul>
 *   <li>{@link #getAttributes()} 返回真实 Map——{@code WebSocketSessionManager.wrapSession}
 *       的装饰器缓存依赖该属性表，伪连接必须保真</li>
 *   <li>{@link #sendMessage(TextMessage)} 把帧文本记录进列表供事后断言，可开关「发送即抛异常」
 *       模式模拟死连接/慢连接被装饰器关闭的场景</li>
 *   <li>{@link #close()} 置 open=false，登记断连状态</li>
 * </ul>
 * 断言辅助：{@link #receivedEvents()} 直接反序列化为 {@link WebSocketEvent} 列表，
 * {@link #awaitReceived(int)} 轮询等待帧到达（PONG 经装饰器缓冲队列发出，存在异步窗口）。
 * </p>
 */
public class FakeWebSocketSession implements WebSocketSession {

    /**
     * 全局唯一自增序号：保证每个伪连接 getId() 不同（静态注册表按连接 ID 匹配清理）
     */
    private static final AtomicLong SEQ = new AtomicLong();

    private final String id = "fake-ws-" + SEQ.incrementAndGet();
    private final Map<String, Object> attributes = new HashMap<>();
    private final List<String> received = new CopyOnWriteArrayList<>();

    /**
     * 发送即抛异常开关：模拟连接已死/发送失败，验证注册表的主动剔除清理逻辑
     */
    private volatile boolean failOnSend = false;

    /**
     * 连接开闭状态：close() 后 isOpen() 返回 false
     */
    private volatile boolean open = true;

    /**
     * 已收帧原始文本快照（不可变视图，断言用）
     */
    public List<String> receivedTexts() {
        return List.copyOf(received);
    }

    /**
     * 已收帧反序列化后的 WebSocketEvent 视图
     */
    public List<WebSocketEvent> receivedEvents() {
        return received.stream()
                .map(t -> JsonKit.parseObject(t, WebSocketEvent.class))
                .toList();
    }

    /**
     * 轮询等待至少收到 n 帧后返回（装饰器发送可能异步入队）。
     * <p>10ms 间隔轮询而非自旋：超时窗口最长 5 秒，自旋会持续烧满一个核。</p>
     *
     * @param n 期望帧数
     * @throws AssertionError 超时（5 秒）仍未收到
     */
    public void awaitReceived(int n) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (received.size() < n) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("等待 WebSocket 帧超时: 期望 " + n + " 帧, 实际 " + received.size() + " 帧");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待 WebSocket 帧被中断: 期望 " + n + " 帧, 实际 " + received.size() + " 帧", e);
            }
        }
    }

    /**
     * 清空已收帧记录（同一伪连接跨用例复用时重置）
     */
    public void clearReceived() {
        received.clear();
    }

    /**
     * 开启/关闭「发送即抛异常」模式：开启后 sendMessage 抛 IOException，
     * 模拟已断死连接，用于验证发送失败后的主动剔除逻辑
     */
    public void setFailOnSend(boolean failOnSend) {
        this.failOnSend = failOnSend;
    }

    // ──────────────────────────────────────────────
    // WebSocketSession 接口实现
    // ──────────────────────────────────────────────

    @Override
    public String getId() {
        return id;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) throws IOException {
        if (failOnSend) {
            throw new IOException("伪连接模拟发送失败");
        }
        if (message instanceof TextMessage textMessage) {
            received.add(textMessage.getPayload());
        }
        // 其余帧类型（ping/pong/binary）测试场景不消费，静默忽略
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() throws IOException {
        this.open = false;
    }

    @Override
    public void close(CloseStatus status) throws IOException {
        this.open = false;
    }

    // ──────────────────────────────────────────────
    // 以下接口成员测试场景不消费，返回空实现
    // ──────────────────────────────────────────────

    @Override
    public URI getUri() {
        return URI.create("ws://localhost/ws");
    }

    @Override
    public HttpHeaders getHandshakeHeaders() {
        return new HttpHeaders();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return new InetSocketAddress("127.0.0.1", 9661);
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return new InetSocketAddress("127.0.0.1", 54321);
    }

    @Override
    public List<WebSocketExtension> getExtensions() {
        return List.of();
    }

    @Override
    public String getAcceptedProtocol() {
        return "";
    }

    @Override
    public int getTextMessageSizeLimit() {
        return 8192;
    }

    @Override
    public void setTextMessageSizeLimit(int messageSizeLimit) {
        // 不消费
    }

    @Override
    public int getBinaryMessageSizeLimit() {
        return 8192;
    }

    @Override
    public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        // 不消费
    }

    @Override
    public Principal getPrincipal() {
        return null;
    }
}
