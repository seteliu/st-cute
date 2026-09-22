package com.stioc.cute.engine.testkit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.llm.types.CuteToolCall;
import com.stioc.cute.engine.llm.types.Provider;
import com.stioc.cute.engine.llm.types.ProviderProtocol;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 离线假 LLM 服务端：用 JDK 内置 {@link HttpServer} 手写三协议 SSE 帧，让引擎闭环在零外网下真实跑通。
 * <p>
 * 选型理由（见固化决策）：不引 {@code mockwebserver3-junit5}（其 5.x KMP 坐标不在 Boot BOM 托管内），
 * 且手写 SSE 才能精确编排断流、半截 JSON、错误帧、HTTP 500 等异常形态——这些正是重试与容错路径的唯一触发手段。
 * </p>
 * <p>
 * 服务端按「剧本（脚本台词）」工作：每次收到请求按序弹出下一条台词；剧本耗尽时默认重复最后一条
 * （便于「多轮 ReAct」用例一次编排、多次复用），也可配置为耗尽即返回 HTTP 500。
 * </p>
 * <p>
 * 三协议的帧格式差异是本类必须精确对齐的关键事实（任一处理错都会导致用例假绿或假红）：
 * <ul>
 *   <li><b>OPENAI</b>：逐行 {@code data:} JSON，每行必须是完整 JSON（客户端不支持 SSE 多行 data 拼接）；
 *       以 {@code data: [DONE]} 结束；usage 需在 {@code choices} 为空数组的帧里给出；</li>
 *   <li><b>OPENAI_RESPONSE</b>：事件类型优先取 JSON 顶层 {@code type}，故 {@code event:} 行可省；
 *       正文用 {@code response.output_text.delta}，思考用 {@code response.reasoning_text.delta}，
 *       工具用 {@code response.output_item.added} + {@code response.function_call_arguments.delta}，
 *       用量在 {@code response.completed.response.usage}；</li>
 *   <li><b>ANTHROPIC</b>：<b>完全依赖 {@code event:} 行</b>；以 {@code event: message_stop} 结束
 *       （该协议不识别 {@code [DONE]}）；用量分两处——输入在 {@code message_start.message.usage}，
 *       输出在 {@code message_delta.usage.output_tokens}，缺后者则客户端完全不产出 usage。</li>
 * </ul>
 * </p>
 */
public class FakeLlmServer implements AutoCloseable {

    /**
     * 测试基建日志器：测试模块不引 Lombok 日志注解，直接用 slf4j-api（引擎测试期无日志实现，输出为空操作）
     */
    private static final Logger LOG = LoggerFactory.getLogger(FakeLlmServer.class);

    /**
     * 台词播报形态：正常流 / 中途错误帧 / 建连即 500 / 未收尾即断流
     */
    public enum Mode {
        /**
         * 正常流式：完整帧 + 正常结束标记
         */
        STREAM_OK,

        /**
         * 流中途抛错误帧（三协议一致：客户端均抛 {@link com.stioc.cute.engine.llm.SseErrorFrameException} 中断流）。
         * <p>零产出即失败：一帧内容都不给，重试重发不构成重复，用于验证「重试能力本身」。</p>
         */
        STREAM_ERROR_FRAME,

        /**
         * 建连阶段即返回 HTTP 500（三协议通用，是触发重试最可靠的手段）
         */
        HTTP_500,

        /**
         * 写完部分帧后直接断流（不发结束标记）：客户端按 EOF 正常收尾并告警，不触发重试
         */
        STREAM_TRUNCATE,

        /**
         * 已产出部分内容后再注入错误帧：先正常推送若干片思考/正文，再写入错误帧中断本次调用。
         * <p>
         * 与 {@link #STREAM_ERROR_FRAME} 的关键差异：后者一帧内容都不产出即失败，重试重发不构成重复；
         * 本模式是「流已产出可见内容后失败」——生产环境中上游限流切断、连接被中断（日志里的
         * {@code OpenAI 流式 API 报错: terminated}）即属此类，是验证「透明重试重发已推送内容」
         * 是否安全的关键形态。选用错误帧而非强杀连接：前者是确定性异常传播，后者受容器
         * 响应完成语义影响、时序不可控。
         * </p>
         */
        STREAM_ERROR_AFTER_PARTIAL
    }

    /**
     * 一条台词：一次请求应产生的模型响应
     */
    public static final class Turn {

        private String content;
        private String reasoning;
        private List<CuteToolCall> toolCalls = List.of();
        private Long inputTokens = 10L;
        private Long outputTokens = 5L;
        private Long cachedTokens = 0L;
        private Mode mode = Mode.STREAM_OK;

        private Turn() {
        }

        public static Turn text(String content) {
            Turn turn = new Turn();
            turn.content = content;
            return turn;
        }

        public static Turn toolCall(String callId, String toolName, String argumentsJson) {
            Turn turn = new Turn();
            turn.content = "";
            turn.toolCalls = List.of(CuteToolCall.builder()
                    .id(callId).name(toolName).arguments(argumentsJson).build());
            return turn;
        }

        public static Turn content(String content) {
            Turn turn = new Turn();
            turn.content = content;
            return turn;
        }

        public Turn reasoning(String reasoning) {
            this.reasoning = reasoning;
            return this;
        }

        public Turn toolCalls(List<CuteToolCall> toolCalls) {
            this.toolCalls = toolCalls != null ? toolCalls : List.of();
            return this;
        }

        public Turn addToolCall(String callId, String toolName, String argumentsJson) {
            List<CuteToolCall> merged = new ArrayList<>(this.toolCalls);
            merged.add(CuteToolCall.builder().id(callId).name(toolName).arguments(argumentsJson).build());
            this.toolCalls = merged;
            return this;
        }

        public Turn usage(Long input, Long output, Long cached) {
            this.inputTokens = input;
            this.outputTokens = output;
            this.cachedTokens = cached;
            return this;
        }

        public Turn mode(Mode mode) {
            this.mode = mode;
            return this;
        }
    }

    /**
     * 协议（决定帧格式与请求路径）
     */
    private final ProviderProtocol protocol;

    /**
     * 内嵌 HttpServer
     */
    private final HttpServer server;

    /**
     * 剧本：按请求次序消费
     */
    private final List<Turn> script = new CopyOnWriteArrayList<>();

    /**
     * 剧本耗尽时是否重复最后一条台词（默认 true，便于多轮循环用例）
     */
    private volatile boolean repeatLastTurn = true;

    /**
     * 正文/思考/工具参数每帧切分粒度（字符数），用于真实压测客户端的增量拼接逻辑
     */
    private volatile int chunkSize = 3;

    /**
     * 帧间延迟（毫秒）：配合取消类用例营造确定的「流仍在传输」窗口
     */
    private volatile long frameDelayMs = 0L;

    /**
     * 已收到的请求数
     */
    private final AtomicInteger requestCount = new AtomicInteger();

    /**
     * 已收到的请求体原文（供请求体结构断言）
     */
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();

    /**
     * 已收到的请求头快照
     */
    private final List<String> requestHeaders = new CopyOnWriteArrayList<>();

    private FakeLlmServer(ProviderProtocol protocol) throws IOException {
        this.protocol = protocol;
        // 端口 0：由系统分配空闲端口，避免并行测试端口冲突
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "fake-llm-server");
            t.setDaemon(true);
            return t;
        }));
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    /**
     * 创建指定协议的假服务端（默认监听随机空闲端口）
     */
    public static FakeLlmServer start(ProviderProtocol protocol) throws IOException {
        return new FakeLlmServer(protocol);
    }

    /**
     * 追加一条台词
     */
    public FakeLlmServer enqueue(Turn turn) {
        script.add(turn);
        return this;
    }

    /**
     * 清空并重设剧本
     */
    public FakeLlmServer script(List<Turn> turns) {
        script.clear();
        if (turns != null) {
            script.addAll(turns);
        }
        return this;
    }

    /**
     * 剧本耗尽时是否重复最后一条
     */
    public FakeLlmServer repeatLastTurn(boolean repeat) {
        this.repeatLastTurn = repeat;
        return this;
    }

    /**
     * 设置流式增量切片粒度（字符数）
     */
    public FakeLlmServer chunkSize(int chunkSize) {
        this.chunkSize = Math.max(1, chunkSize);
        return this;
    }

    /**
     * 设置帧间延迟（毫秒）：营造「流仍在传输」的确定时间窗口，供用户取消等中间态用例使用
     */
    public FakeLlmServer frameDelayMs(long frameDelayMs) {
        this.frameDelayMs = Math.max(0L, frameDelayMs);
        return this;
    }

    /**
     * 服务基址（形如 {@code http://127.0.0.1:12345}）
     */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 构造指向本服务的供应商配置（baseUrl 已含 /v1，路径由协议后缀自动拼接）
     */
    public Provider provider() {
        return providerBuilder().build();
    }

    /**
     * 供应商配置构建器：上下文窗口等参数由用例按需定制（压缩链路依赖窗口大小）
     */
    public Provider.ProviderBuilder providerBuilder() {
        return Provider.builder()
                .group("fake-local")
                .protocol(protocol.name())
                .baseUrl(baseUrl() + "/v1")
                .apiKey("fake-test-key")
                .modelName("fake-model")
                .temperature(0.0)
                .contextSize(200000);
    }

    /**
     * 协议后缀拼出的完整请求路径（如 /v1/chat/completions），供断言请求落点
     */
    public String expectedPath() {
        return "/v1" + protocol.getPathSuffix();
    }

    /**
     * 已收到的请求数
     */
    public int requestCount() {
        return requestCount.get();
    }

    /**
     * 已收到的请求体原文快照
     */
    public List<String> requestBodies() {
        return Collections.unmodifiableList(requestBodies);
    }

    /**
     * 最近一次请求体（无请求时返回 null）
     */
    public String lastRequestBody() {
        return requestBodies.isEmpty() ? null : requestBodies.get(requestBodies.size() - 1);
    }

    /**
     * 已收到的请求头快照（每项形如 {@code 请求行 + 各头行}）
     */
    public List<String> requestHeaders() {
        return Collections.unmodifiableList(requestHeaders);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ──────────────────────────────────────────────
    // 请求处理
    // ──────────────────────────────────────────────

    private void handle(HttpExchange exchange) throws IOException {
        int index = requestCount.getAndIncrement();
        String body = readBody(exchange.getRequestBody());
        requestBodies.add(body);
        requestHeaders.add(dumpHeaders(exchange));

        Turn turn = resolveTurn(index);
        if (turn == null) {
            writePlain(exchange, 500, "{\"error\":{\"message\":\"假 LLM 服务端剧本已耗尽\"}}");
            return;
        }
        if (turn.mode == Mode.HTTP_500) {
            writePlain(exchange, 500, "{\"error\":{\"message\":\"假服务端模拟的建连失败\"}}");
            return;
        }

        boolean streaming = body.contains("\"stream\":true") || body.contains("\"stream\": true");
        if (streaming) {
            writeSse(exchange, turn);
        } else {
            writePlain(exchange, 200, renderNonStream(turn));
        }
    }

    /**
     * 取出本次请求对应的台词（剧本耗尽时按开关重复末条或返回 null）
     */
    private Turn resolveTurn(int index) {
        if (index < script.size()) {
            return script.get(index);
        }
        if (repeatLastTurn && !script.isEmpty()) {
            return script.get(script.size() - 1);
        }
        return null;
    }

    private String readBody(InputStream in) throws IOException {
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String dumpHeaders(HttpExchange exchange) {
        StringBuilder sb = new StringBuilder();
        sb.append(exchange.getRequestMethod()).append(' ').append(exchange.getRequestURI()).append('\n');
        exchange.getRequestHeaders().forEach((k, v) -> sb.append(k).append(": ").append(String.join(",", v)).append('\n'));
        return sb.toString();
    }

    private void writePlain(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * 渲染并写出 SSE 流（chunked 传输，逐帧 flush）
     */
    private void writeSse(HttpExchange exchange, Turn turn) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        // 长度 0 表示 chunked：不预先确定长度，逐帧 flush 才有真实流式语义
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream out = exchange.getResponseBody()) {
            try {
                for (String frame : renderSseFrames(turn)) {
                    if (frame == null) {
                        continue;
                    }
                    out.write(frame.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    if (frameDelayMs > 0) {
                        sleepQuietly(frameDelayMs);
                    }
                }
                if (turn.mode == Mode.STREAM_TRUNCATE) {
                    // 故意不写结束标记：客户端按读尽 EOF 收尾
                    return;
                }
                if (turn.mode == Mode.STREAM_ERROR_FRAME || turn.mode == Mode.STREAM_ERROR_AFTER_PARTIAL) {
                    // 错误帧（或内容后的错误帧）写完后直接结束，客户端解析该帧时抛异常
                    out.flush();
                    return;
                }
                out.flush();
            } catch (IOException e) {
                // 客户端在流传输中途取消连接（用户中断用例的预期路径），静默收场即可
                LOG.debug("假 LLM 服务端写帧中断（客户端可能已取消连接）: {}", e.getMessage());
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ──────────────────────────────────────────────
    // SSE 帧渲染（按协议）
    // ──────────────────────────────────────────────

    /**
     * 渲染该协议的完整帧序列（每帧含 SSE 分隔空行）
     */
    private List<String> renderSseFrames(Turn turn) {
        return switch (protocol) {
            case OPENAI -> renderOpenAi(turn);
            case OPENAI_RESPONSE -> renderOpenAiResponse(turn);
            case ANTHROPIC -> renderAnthropic(turn);
        };
    }

    /**
     * OpenAI Chat Completions 帧序列
     */
    private List<String> renderOpenAi(Turn turn) {
        List<String> frames = new ArrayList<>();
        frames.add(openAiData("{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}"));

        if (turn.mode == Mode.STREAM_ERROR_FRAME) {
            // 零产出即失败：一帧内容都不给
            frames.add(openAiData("{\"error\":{\"message\":\"假服务端注入的流中错误帧\",\"type\":\"server_error\"}}"));
            return frames;
        }

        for (String piece : split(turn.reasoning)) {
            frames.add(openAiData("{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\""
                    + escape(piece) + "\"}}]}"));
        }
        for (String piece : split(turn.content)) {
            frames.add(openAiData("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                    + escape(piece) + "\"}}]}"));
        }

        // 工具调用：首片带 id/name，参数切两片以真实走通客户端的分片追加逻辑
        int toolIndex = 0;
        for (CuteToolCall call : turn.toolCalls) {
            String args = call.getArguments() != null ? call.getArguments() : "{}";
            String head = args.length() > 1 ? args.substring(0, 1) : args;
            String tail = args.length() > 1 ? args.substring(1) : "";
            frames.add(openAiData("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":"
                    + toolIndex + ",\"id\":\"" + escape(call.getId()) + "\",\"type\":\"function\",\"function\":{\"name\":\""
                    + escape(call.getName()) + "\",\"arguments\":\"" + escape(head) + "\"}}]}}]}"));
            if (!tail.isEmpty()) {
                frames.add(openAiData("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":"
                        + toolIndex + ",\"function\":{\"arguments\":\"" + escape(tail) + "\"}}]}}]}"));
            }
            toolIndex++;
        }

        // usage 帧：choices 为空数组
        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", turn.inputTokens);
        usage.put("completion_tokens", turn.outputTokens);
        if (turn.cachedTokens != null && turn.cachedTokens > 0) {
            JSONObject details = new JSONObject();
            details.put("cached_tokens", turn.cachedTokens);
            usage.put("prompt_tokens_details", details);
        }
        frames.add(openAiData("{\"choices\":[],\"usage\":" + usage.toJSONString() + "}"));

        if (turn.mode == Mode.STREAM_ERROR_AFTER_PARTIAL) {
            // 内容与工具调用参数均已产出后再失败：本帧之后客户端抛 SseErrorFrameException，
            // 触发透明重试并重发以上全部内容，覆盖思考/正文/工具参数三个累加器的复位语义
            frames.add(openAiData("{\"error\":{\"message\":\"假服务端在内容产出后中断流\",\"type\":\"server_error\"}}"));
            return frames;
        }

        frames.add("data: [DONE]\n\n");
        return frames;
    }

    /**
     * OpenAI Responses 帧序列（事件类型以 JSON 顶层 type 为准）
     */
    private List<String> renderOpenAiResponse(Turn turn) {
        List<String> frames = new ArrayList<>();
        if (turn.mode == Mode.STREAM_ERROR_FRAME) {
            // 三协议一致：错误帧交由客户端抛 SseErrorFrameException 中断流（不再被静默吞掉）
            frames.add(openAiResponseEvent("error",
                    "{\"type\":\"error\",\"error\":{\"message\":\"假服务端注入的流中错误帧\"}}"));
            return frames;
        }
        for (String piece : split(turn.reasoning)) {
            frames.add(openAiResponseEvent("response.reasoning_text.delta",
                    "{\"type\":\"response.reasoning_text.delta\",\"delta\":\"" + escape(piece) + "\"}"));
        }
        for (String piece : split(turn.content)) {
            frames.add(openAiResponseEvent("response.output_text.delta",
                    "{\"type\":\"response.output_text.delta\",\"delta\":\"" + escape(piece) + "\"}"));
        }
        int outputIndex = 1;
        for (CuteToolCall call : turn.toolCalls) {
            String args = call.getArguments() != null ? call.getArguments() : "{}";
            frames.add(openAiResponseEvent("response.output_item.added",
                    "{\"type\":\"response.output_item.added\",\"output_index\":" + outputIndex
                            + ",\"item\":{\"type\":\"function_call\",\"id\":\"fc_" + escape(call.getId())
                            + "\",\"call_id\":\"" + escape(call.getId()) + "\",\"name\":\"" + escape(call.getName())
                            + "\",\"arguments\":\"\"}}"));
            frames.add(openAiResponseEvent("response.function_call_arguments.delta",
                    "{\"type\":\"response.function_call_arguments.delta\",\"output_index\":" + outputIndex
                            + ",\"item_id\":\"fc_" + escape(call.getId()) + "\",\"call_id\":\"" + escape(call.getId())
                            + "\",\"delta\":\"" + escape(args) + "\"}"));
            outputIndex++;
        }
        JSONObject usage = new JSONObject();
        usage.put("input_tokens", turn.inputTokens);
        usage.put("output_tokens", turn.outputTokens);
        if (turn.cachedTokens != null && turn.cachedTokens > 0) {
            JSONObject details = new JSONObject();
            details.put("cached_tokens", turn.cachedTokens);
            usage.put("input_tokens_details", details);
        }
        frames.add(openAiResponseEvent("response.completed",
                "{\"type\":\"response.completed\",\"response\":{\"usage\":" + usage.toJSONString() + "}}"));

        if (turn.mode == Mode.STREAM_ERROR_AFTER_PARTIAL) {
            // 内容与工具参数均已产出后再失败（与 OpenAI 侧同源语义），触发透明重试
            frames.add(openAiResponseEvent("error",
                    "{\"type\":\"error\",\"error\":{\"message\":\"假服务端在内容产出后中断流\"}}"));
            return frames;
        }

        frames.add("data: [DONE]\n\n");
        return frames;
    }

    /**
     * Anthropic Messages 帧序列（事件类型完全依赖 event: 行）
     */
    private List<String> renderAnthropic(Turn turn) {
        List<String> frames = new ArrayList<>();
        JSONObject startUsage = new JSONObject();
        startUsage.put("input_tokens", turn.inputTokens);
        startUsage.put("cache_read_input_tokens", turn.cachedTokens != null ? turn.cachedTokens : 0L);
        startUsage.put("cache_creation_input_tokens", 0L);
        frames.add(anthropicEvent("message_start",
                "{\"type\":\"message_start\",\"message\":{\"usage\":" + startUsage.toJSONString() + "}}"));

        if (turn.mode == Mode.STREAM_ERROR_FRAME) {
            frames.add(anthropicEvent("error",
                    "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"假服务端注入的流中错误帧\"}}"));
            return frames;
        }

        // 文本块（索引 0）
        frames.add(anthropicEvent("content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
        for (String piece : split(turn.reasoning)) {
            frames.add(anthropicEvent("content_block_delta",
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\""
                            + escape(piece) + "\"}}"));
        }
        for (String piece : split(turn.content)) {
            frames.add(anthropicEvent("content_block_delta",
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\""
                            + escape(piece) + "\"}}"));
        }
        frames.add(anthropicEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));

        // 工具块（索引自 1 起）
        int blockIndex = 1;
        for (CuteToolCall call : turn.toolCalls) {
            String args = call.getArguments() != null ? call.getArguments() : "{}";
            frames.add(anthropicEvent("content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":" + blockIndex
                            + ",\"content_block\":{\"type\":\"tool_use\",\"id\":\"" + escape(call.getId())
                            + "\",\"name\":\"" + escape(call.getName()) + "\",\"input\":{}}}"));
            // 参数切两片，真实走通 input_json_delta 的追加逻辑
            String head = args.length() > 1 ? args.substring(0, 1) : args;
            String tail = args.length() > 1 ? args.substring(1) : "";
            frames.add(anthropicEvent("content_block_delta",
                    "{\"type\":\"content_block_delta\",\"index\":" + blockIndex
                            + ",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"" + escape(head) + "\"}}"));
            if (!tail.isEmpty()) {
                frames.add(anthropicEvent("content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":" + blockIndex
                                + ",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"" + escape(tail) + "\"}}"));
            }
            frames.add(anthropicEvent("content_block_stop",
                    "{\"type\":\"content_block_stop\",\"index\":" + blockIndex + "}"));
            blockIndex++;
        }

        // 输出用量只能在 message_delta 里给出
        JSONObject deltaUsage = new JSONObject();
        deltaUsage.put("output_tokens", turn.outputTokens);
        frames.add(anthropicEvent("message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\""
                        + (turn.toolCalls.isEmpty() ? "end_turn" : "tool_use") + "\"},\"usage\":"
                        + deltaUsage.toJSONString() + "}"));

        if (turn.mode == Mode.STREAM_ERROR_AFTER_PARTIAL) {
            // 内容与工具参数均已产出后再失败（与另两协议同源语义）：不发 message_stop，直接注入 error 帧
            frames.add(anthropicEvent("error",
                    "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"假服务端在内容产出后中断流\"}}"));
            return frames;
        }

        frames.add(anthropicEvent("message_stop", "{\"type\":\"message_stop\"}"));
        return frames;
    }

    private String openAiData(String json) {
        return "data: " + json + "\n\n";
    }

    private String openAiResponseEvent(String eventName, String json) {
        return "event: " + eventName + "\n" + "data: " + json + "\n\n";
    }

    private String anthropicEvent(String eventName, String json) {
        return "event: " + eventName + "\n" + "data: " + json + "\n\n";
    }

    // ──────────────────────────────────────────────
    // 非流式响应渲染（按协议）
    // ──────────────────────────────────────────────

    private String renderNonStream(Turn turn) {
        return switch (protocol) {
            case OPENAI -> renderOpenAiNonStream(turn);
            case OPENAI_RESPONSE -> renderOpenAiResponseNonStream(turn);
            case ANTHROPIC -> renderAnthropicNonStream(turn);
        };
    }

    private String renderOpenAiNonStream(Turn turn) {
        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", turn.content);
        if (turn.reasoning != null) {
            message.put("reasoning_content", turn.reasoning);
        }
        if (!turn.toolCalls.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (CuteToolCall call : turn.toolCalls) {
                JSONObject tc = new JSONObject();
                tc.put("id", call.getId());
                tc.put("type", "function");
                JSONObject fn = new JSONObject();
                fn.put("name", call.getName());
                fn.put("arguments", call.getArguments());
                tc.put("function", fn);
                arr.add(tc);
            }
            message.put("tool_calls", arr);
        }
        JSONObject root = new JSONObject();
        JSONArray choices = new JSONArray();
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("message", message);
        choices.add(choice);
        root.put("choices", choices);
        root.put("usage", openAiUsage(turn));
        return root.toJSONString();
    }

    private JSONObject openAiUsage(Turn turn) {
        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", turn.inputTokens);
        usage.put("completion_tokens", turn.outputTokens);
        JSONObject details = new JSONObject();
        details.put("cached_tokens", turn.cachedTokens != null ? turn.cachedTokens : 0L);
        usage.put("prompt_tokens_details", details);
        return usage;
    }

    private String renderOpenAiResponseNonStream(Turn turn) {
        JSONArray output = new JSONArray();
        if (turn.reasoning != null && !turn.reasoning.isEmpty()) {
            JSONObject reasoningItem = new JSONObject();
            reasoningItem.put("type", "reasoning");
            reasoningItem.put("content", turn.reasoning);
            output.add(reasoningItem);
        }
        if (turn.content != null && !turn.content.isEmpty()) {
            JSONObject msgItem = new JSONObject();
            msgItem.put("type", "message");
            msgItem.put("role", "assistant");
            JSONArray parts = new JSONArray();
            JSONObject textPart = new JSONObject();
            textPart.put("type", "output_text");
            textPart.put("text", turn.content);
            parts.add(textPart);
            msgItem.put("content", parts);
            output.add(msgItem);
        }
        for (CuteToolCall call : turn.toolCalls) {
            JSONObject item = new JSONObject();
            item.put("type", "function_call");
            item.put("id", "fc_" + call.getId());
            item.put("call_id", call.getId());
            item.put("name", call.getName());
            item.put("arguments", call.getArguments());
            output.add(item);
        }
        JSONObject root = new JSONObject();
        root.put("output", output);
        JSONObject usage = new JSONObject();
        usage.put("input_tokens", turn.inputTokens);
        usage.put("output_tokens", turn.outputTokens);
        JSONObject details = new JSONObject();
        details.put("cached_tokens", turn.cachedTokens != null ? turn.cachedTokens : 0L);
        usage.put("input_tokens_details", details);
        root.put("usage", usage);
        return root.toJSONString();
    }

    private String renderAnthropicNonStream(Turn turn) {
        JSONArray contentArr = new JSONArray();
        if (turn.reasoning != null && !turn.reasoning.isEmpty()) {
            JSONObject thinking = new JSONObject();
            thinking.put("type", "thinking");
            thinking.put("thinking", turn.reasoning);
            contentArr.add(thinking);
        }
        if (turn.content != null && !turn.content.isEmpty()) {
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", turn.content);
            contentArr.add(text);
        }
        for (CuteToolCall call : turn.toolCalls) {
            JSONObject toolUse = new JSONObject();
            toolUse.put("type", "tool_use");
            toolUse.put("id", call.getId());
            toolUse.put("name", call.getName());
            toolUse.put("input", JSONObject.parse(call.getArguments() != null ? call.getArguments() : "{}"));
            contentArr.add(toolUse);
        }
        JSONObject root = new JSONObject();
        root.put("content", contentArr);
        JSONObject usage = new JSONObject();
        usage.put("input_tokens", turn.inputTokens);
        usage.put("output_tokens", turn.outputTokens);
        usage.put("cache_read_input_tokens", turn.cachedTokens != null ? turn.cachedTokens : 0L);
        root.put("usage", usage);
        return root.toJSONString();
    }

    // ──────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────

    /**
     * 按固定粒度把文本切成若干片（空文本返回空列表；保证不切出空串）
     */
    private List<String> split(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> pieces = new ArrayList<>();
        int size = chunkSize;
        for (int i = 0; i < text.length(); i += size) {
            pieces.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return pieces;
    }

    /**
     * JSON 字符串转义（帧体手工拼接，必须自行转义避免破坏 JSON 结构）
     */
    private String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
