package com.stioc.cute.engine.llm;

import com.stioc.cute.engine.common.JsonKit;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.llm.types.CuteChatResponse;
import com.stioc.cute.engine.llm.types.CuteMessage;
import com.stioc.cute.engine.llm.types.CuteMessageRole;
import com.stioc.cute.engine.llm.types.CutePrompt;
import com.stioc.cute.engine.llm.types.CuteToolCall;
import com.stioc.cute.engine.llm.types.CuteToolDefinition;
import com.stioc.cute.engine.llm.types.CuteUsage;
import com.stioc.cute.engine.llm.types.ProviderProtocol;
import com.stioc.cute.engine.testkit.FakeLlmServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三协议客户端与假 LLM 服务端的契约测试（同一套用例跑三种协议）。
 * <p>
 * 这套用例是 L2 的核心资产：它同时扮演两个角色——既验证三种协议客户端的解析正确性，
 * 又反向校准 {@link FakeLlmServer} 的保真度（假服务端若与真实供应商帧格式有偏差，用例不会通过）。
 * L4 真连阶段将以同一套断言跑真实供应商，据差异反推假服务端的失真点。
 * </p>
 * <p>
 * 覆盖的契约四件套：call 冒烟 / stream 增量拼接 / tool_call 回合 / 错误重试。
 * </p>
 */
class LlmProtocolContractTest {

    /**
     * 按协议直构客户端（interceptor 传 null：绕开日志拦截器对 Content-Type 的额外依赖）
     */
    private static CuteChat clientFor(FakeLlmServer server, ProviderProtocol protocol) {
        String baseUrl = server.baseUrl() + "/v1";
        return switch (protocol) {
            case OPENAI -> new CuteChatForOpenAi(baseUrl, "test-key", "fake-model", 0.0, false, null);
            case OPENAI_RESPONSE -> new CuteChatForOpenAiResponse(baseUrl, "test-key", "fake-model", 0.0, false, null);
            case ANTHROPIC -> new CuteChatForAnthropic(baseUrl, "test-key", "fake-model", 0.0, false, null);
        };
    }

    private static CutePrompt prompt(String userText) {
        List<CuteMessage> messages = new ArrayList<>();
        messages.add(CuteMessage.builder().role(CuteMessageRole.SYSTEM).content("你是测试助手。").build());
        messages.add(CuteMessage.builder().role(CuteMessageRole.USER).content(userText).build());
        return CutePrompt.builder().messages(messages).build();
    }

    // ──────────────────────────────────────────────
    // 契约一：非流式 call 冒烟
    // ──────────────────────────────────────────────

    /**
     * 三协议的非流式调用都应返回正文与 usage（思考按协议分别校验）
     */
    @ParameterizedTest
    @EnumSource(ProviderProtocol.class)
    void callReturnsContentReasoningAndUsage(ProviderProtocol protocol) throws IOException {
        try (FakeLlmServer server = FakeLlmServer.start(protocol)) {
            server.enqueue(FakeLlmServer.Turn.text("非流式回答").reasoning("非流式思考").usage(11L, 6L, 3L));

            CuteChatResponse response = clientFor(server, protocol).call(prompt("你好"));

            assertEquals("非流式回答", response.getContent(), protocol + " 非流式正文解析错误");
            assertEquals("非流式思考", response.getReasoningContent(), protocol + " 非流式思考解析错误");
            assertNotNull(response.getUsage(), protocol + " 非流式 usage 不应缺失");
            assertEquals(6L, response.getUsage().getOutputTokens(), protocol + " 输出 token 解析错误");
            assertEquals(3L, response.getUsage().getCachedTokens(), protocol + " 缓存 token 解析错误");
            // Anthropic 把「未命中缓存的输入」与「命中缓存的输入」拆分返回，客户端累加为总输入
            long expectedInput = protocol == ProviderProtocol.ANTHROPIC ? 14L : 11L;
            assertEquals(expectedInput, response.getUsage().getInputTokens(), protocol + " 输入 token 解析错误");
        }
    }

    /**
     * 三协议的请求必须落在各自协议的路径上
     */
    @ParameterizedTest
    @EnumSource(ProviderProtocol.class)
    void callHitsProtocolSpecificPath(ProviderProtocol protocol) throws IOException {
        try (FakeLlmServer server = FakeLlmServer.start(protocol)) {
            server.enqueue(FakeLlmServer.Turn.text("ok"));

            clientFor(server, protocol).call(prompt("路径检查"));

            String headers = server.requestHeaders().get(0);
            // 注意 HTTP 头名不区分大小写，JDK HttpServer 会把头名归一为首字母大写形式，
            // 故此处统一以小写文本做包含判定，避免断言脆断于头名大小写差异
            String lowerHeaders = headers.toLowerCase();
            assertTrue(headers.contains("POST " + server.expectedPath()),
                    protocol + " 请求路径应为 " + server.expectedPath() + "，实际请求头: " + headers);
            assertTrue(lowerHeaders.contains("user-agent: st-cute-code-agent"), "应携带统一 User-Agent");
            if (protocol == ProviderProtocol.ANTHROPIC) {
                assertTrue(lowerHeaders.contains("x-api-key: test-key"),
                        "Anthropic 应使用 x-api-key 头，实际: " + headers);
                assertTrue(lowerHeaders.contains("anthropic-version: 2023-06-01"),
                        "Anthropic 应携带协议版本头，实际: " + headers);
            } else {
                assertTrue(lowerHeaders.contains("authorization: bearer test-key"),
                        "应使用 Bearer 认证头，实际: " + headers);
            }
        }
    }

    /**
     * 三协议的非流式请求体都应携带模型名、messages/input 结构，且 stream 为 false
     */
    @ParameterizedTest
    @EnumSource(ProviderProtocol.class)
    void callSendsWellFormedRequestBody(ProviderProtocol protocol) throws IOException {
        try (FakeLlmServer server = FakeLlmServer.start(protocol)) {
            server.enqueue(FakeLlmServer.Turn.text("ok"));

            clientFor(server, protocol).call(prompt("请求体检查"));

            JSONObject body = JsonKit.parseObject(server.lastRequestBody());
            assertEquals("fake-model", body.getString("model"), protocol + " 应携带模型名");
            assertTrue(!body.getBooleanValue("stream"), protocol + " 非流式调用 stream 应为 false");
            switch (protocol) {
                case OPENAI -> assertNotNull(body.getJSONArray("messages"), "OpenAI 应使用 messages 字段");
                case OPENAI_RESPONSE -> assertNotNull(body.getJSONArray("input"), "Responses 协议应使用 input 字段");
                case ANTHROPIC -> {
                    assertNotNull(body.getJSONArray("messages"), "Anthropic 应使用 messages 字段");
                    assertTrue(body.containsKey("max_tokens"), "Anthropic 的 max_tokens 为必传字段");
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // 契约二：流式增量拼接
    // ──────────────────────────────────────────────

    /**
     * 三协议的流式调用都应把多片增量拼回完整正文，并正确分离思考流
     */
    @ParameterizedTest
    @EnumSource(ProviderProtocol.class)
    void streamConcatenatesIncrementalChunks(ProviderProtocol protocol) throws IOException {
        try (FakeLlmServer server = FakeLlmServer.start(protocol)) {
            server.chunkSize(2)
                    .enqueue(FakeLlmServer.Turn.text("流式完整正文").reasoning("流式思考过程").usage(9L, 4L, 1L));

            List<CuteChatResponse> received = consumeStream(clientFor(server, protocol), "流式你好");

            String content = received.stream()
                    .map(CuteChatResponse::getContent)
                    .filter(java.util.Objects::nonNull)
                    .reduce("", String::concat);
            String reasoning = received.stream()
                    .map(CuteChatResponse::getReasoningContent)
                    .filter(java.util.Objects::nonNull)
                    .reduce("", String::concat);

            assertEquals("流式完整正文", content, protocol + " 流式正文增量拼接错误");
            assertEquals("流式思考过程", reasoning, protocol + " 流式思考增量拼接错误");

            CuteUsage usage = received.stream()
                    .map(CuteChatResponse::getUsage)
                    .filter(java.util.Objects::nonNull)
                    .reduce((a, b) -> b)
                    .orElse(null);
            assertNotNull(usage, protocol + " 流式 usage 不应缺失");
            long expectedInput = protocol == ProviderProtocol.ANTHROPIC ? 10L : 9L;
            assertEquals(expectedInput, usage.getInputTokens(), protocol + " 流式输入 token 解析错误");
        }
    }

    /**
     * 流式请求体必须携带 stream=true
     */
    @ParameterizedTest
    @EnumSource(ProviderProtocol.class)
    void streamSendsStreamFlag(ProviderProtocol protocol) throws IOException {
        try (FakeLlmServer server = FakeLlmServer.start(protocol)) {
            server.enqueue(FakeLlmServer.Turn.text("ok"));

            consumeStream(clientFor(server, protocol), "stream 标记检查");

            JSONObject body = JsonKit.parseObject(server.lastRequestBody());
            assertTrue(body.getBooleanValue("stream"), protocol + " 流式请求 stream 必须为 true");
        }
    }

    /**
     * Anthropic 的输出用量只在 message_delta 中给出，缺失该事件时不应凭空产出 usage
     * （此断言固化了「假服务端必须补 message_delta」这一保真要求）
     */
    @Test
    void anthropicRequiresMessageDeltaForOutputUsage() throws IOException {
        try (FakeLlmServer server = FakeLlmServer.start(ProviderProtocol.ANTHROPIC)) {
            server.enqueue(FakeLlmServer.Turn.text("只有文字").usage(9L, 4L, 0L));

            List<CuteChatResponse> received = consumeStream(
                    clientFor(server, ProviderProtocol.ANTHROPIC), "用量检查");

            CuteUsage usage = received.stream()
                    .map(CuteChatResponse::getUsage)
                    .filter(java.util.Objects::nonNull)
                    .reduce((a, b) -> b)
                    .orElse(null);
            assertNotNull(usage, "假服务端已补 message_delta，usage 应存在");
            assertEquals(4L, usage.getOutputTokens(), "输出 token 应来自 message_delta");
        }
    }

    /**
     * 干净 EOF（无结束标记）时客户端应正常收尾并返回已收到的内容，而不是抛异常或挂死
     */
    @ParameterizedTest
    @EnumSource(ProviderProtocol.class)
    void toleratesEofWithoutTerminator(ProviderProtocol protocol) throws IOException {
        try (FakeLlmServer server = FakeLlmServer.start(protocol)) {
            server.enqueue(FakeLlmServer.Turn.text("半截响应").mode(FakeLlmServer.Mode.STREAM_TRUNCATE));

            List<CuteChatResponse> received = consumeStream(clientFor(server, protocol), "断流检查");

            String content = received.stream()
                    .map(CuteChatResponse::getContent)
                    .filter(java.util.Objects::nonNull)
                    .reduce("", String::concat);
            assertEquals("半截响应", content, protocol + " 断流后已收到的增量应保留");
        }
    }

    // ──────────────────────────────────────────────
    // 契约三：tool_call 回合
    // ──────────────────────────────────────────────

    /**
     * 三协议的流式工具调用都应还原出 id / name / 完整 arguments（参数分片必须拼接）
     */
    @ParameterizedTest
    @EnumSource(ProviderProtocol.class)
    void streamReassemblesToolCalls(ProviderProtocol protocol) throws IOException {
        try (FakeLlmServer server = FakeLlmServer.start(protocol)) {
            server.enqueue(FakeLlmServer.Turn.toolCall("call_1", "read_file", "{\"path\":\"a.txt\"}"));

            List<CuteChatResponse> received = consumeStream(clientFor(server, protocol), "读文件");

            List<CuteToolCall> toolCalls = received.stream()
                    .map(CuteChatResponse::getToolCalls)
                    .filter(java.util.Objects::nonNull)
                    .flatMap(List::stream)
                    .toList();

            assertEquals(1, toolCalls.size(), protocol + " 应还原出 1 个工具调用");
            CuteToolCall call = toolCalls.get(0);
            assertEquals("call_1", call.getId(), protocol + " 工具调用 id 还原错误");
            assertEquals("read_file", call.getName(), protocol + " 工具名还原错误");
            assertEquals("{\"path\":\"a.txt\"}", call.getArguments(), protocol + " 参数分片拼接错误");
        }
    }

    /**
     * 三协议的非流式工具调用也应正确解析
     */
    @ParameterizedTest
    @EnumSource(ProviderProtocol.class)
    void callParsesToolCalls(ProviderProtocol protocol) throws IOException {
        try (FakeLlmServer server = FakeLlmServer.start(protocol)) {
            server.enqueue(FakeLlmServer.Turn.toolCall("call_9", "write_file", "{\"path\":\"b.txt\"}"));

            CuteChatResponse response = clientFor(server, protocol).call(prompt("写文件"));

            assertNotNull(response.getToolCalls(), protocol + " 非流式工具调用不应缺失");
            assertEquals(1, response.getToolCalls().size());
            assertEquals("call_9", response.getToolCalls().get(0).getId());
            assertEquals("write_file", response.getToolCalls().get(0).getName());
            assertEquals("{\"path\":\"b.txt\"}", response.getToolCalls().get(0).getArguments());
        }
    }

    /**
     * 工具定义应随请求下发（模型据此决定是否调用），且三协议的字段结构各自正确
     */
    @Test
    void sendsToolDefinitionsInRequest() throws IOException {
        List<CuteToolDefinition> tools = List.of(CuteToolDefinition.builder()
                .name("read_file")
                .description("读取文件")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}")
                .build());

        for (ProviderProtocol protocol : ProviderProtocol.values()) {
            try (FakeLlmServer server = FakeLlmServer.start(protocol)) {
                server.enqueue(FakeLlmServer.Turn.text("ok"));

                CutePrompt prompt = CutePrompt.builder()
                        .messages(List.of(CuteMessage.builder()
                                .role(CuteMessageRole.USER).content("你好").build()))
                        .options(com.stioc.cute.engine.llm.types.CuteChatOptions.builder()
                                .model("fake-model")
                                .temperature(0.0)
                                .tools(tools)
                                .build())
                        .build();

                clientFor(server, protocol).call(prompt);

                JSONObject body = JsonKit.parseObject(server.lastRequestBody());
                JSONArray toolArr = body.getJSONArray("tools");
                assertNotNull(toolArr, protocol + " 请求体应携带 tools 定义");
                assertEquals(1, toolArr.size(), protocol + " 应下发 1 个工具定义");
                JSONObject first = toolArr.getJSONObject(0);
                switch (protocol) {
                    case OPENAI -> {
                        assertEquals("function", first.getString("type"));
                        assertEquals("read_file", first.getJSONObject("function").getString("name"));
                    }
                    case OPENAI_RESPONSE -> {
                        // Responses 协议的工具定义是扁平结构，无 function 包装
                        assertEquals("function", first.getString("type"));
                        assertEquals("read_file", first.getString("name"));
                    }
                    case ANTHROPIC -> {
                        assertEquals("read_file", first.getString("name"));
                        assertNotNull(first.getJSONObject("input_schema"),
                                "Anthropic 工具定义应使用 input_schema");
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // 契约四：错误与重试
    // ──────────────────────────────────────────────

    /**
     * 非 2xx 响应应抛出携带状态码的异常（三协议通用）
     */
    @ParameterizedTest
    @EnumSource(ProviderProtocol.class)
    void throwsOnHttpError(ProviderProtocol protocol) throws IOException {
        try (FakeLlmServer server = FakeLlmServer.start(protocol)) {
            server.enqueue(FakeLlmServer.Turn.text("x").mode(FakeLlmServer.Mode.HTTP_500));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> clientFor(server, protocol).call(prompt("触发 500")));
            assertTrue(ex.getMessage().contains("500"),
                    protocol + " 异常信息应包含状态码，实际: " + ex.getMessage());
        }
    }

    /**
     * 流中错误帧：三协议行为必须一致——均抛出 {@link SseErrorFrameException} 中断流。
     * <p>
     * 严禁被各协议自身的解析兜底 catch 吞掉：一旦吞掉，上游报错会被伪装成「正常但空」的响应，
     * 进而在上层被误标为 SUCCESS 终态（这正是 {@link SseErrorFrameException} 文档警告的场景）。
     * </p>
     * <p>
     * 历史沿革：RESPONSES 协议曾把该错误帧吞掉并仅记日志，当时以断言固化为「三协议差异」；
     * 该行为已修正为与另两协议统一，本用例随之收紧为「三协议一致」。
     * </p>
     */
    @ParameterizedTest
    @EnumSource(ProviderProtocol.class)
    void streamErrorFrameAlwaysThrows(ProviderProtocol protocol) throws IOException {
        try (FakeLlmServer server = FakeLlmServer.start(protocol)) {
            server.enqueue(FakeLlmServer.Turn.text("x").mode(FakeLlmServer.Mode.STREAM_ERROR_FRAME));

            assertThrows(SseErrorFrameException.class,
                    () -> consumeStream(clientFor(server, protocol), "错误帧"),
                    protocol + " 的错误帧必须上抛 SseErrorFrameException，不得被静默吞掉");
        }
    }

    /**
     * 装饰器应真实重试：前两次 HTTP 500、第三次成功时，最终应返回成功结果
     */
    @ParameterizedTest
    @EnumSource(ProviderProtocol.class)
    void retryWrapperRecoversFromHttpErrors(ProviderProtocol protocol) throws IOException {
        try (FakeLlmServer server = FakeLlmServer.start(protocol)) {
            server.enqueue(FakeLlmServer.Turn.text("x").mode(FakeLlmServer.Mode.HTTP_500))
                    .enqueue(FakeLlmServer.Turn.text("x").mode(FakeLlmServer.Mode.HTTP_500))
                    .enqueue(FakeLlmServer.Turn.text("重试成功"));

            CuteChat retrying = new CuteChatRetryWrapper(clientFor(server, protocol), new RetryPolicyProvider() {
                @Override
                public int getRetryCount() {
                    return 3;
                }

                @Override
                public int getRetryIntervalSec() {
                    return 0;
                }
            });

            CuteChatResponse response = retrying.call(prompt("重试检查"));

            assertEquals("重试成功", response.getContent(), protocol + " 重试后应返回成功结果");
            assertEquals(3, server.requestCount(), protocol + " 应为 1 次初始调用 + 2 次重试");
        }
    }

    /**
     * 重试耗尽后者应抛出说明重试次数的异常
     */
    @Test
    void retryWrapperThrowsAfterExhaustion() throws IOException {
        try (FakeLlmServer server = FakeLlmServer.start(ProviderProtocol.OPENAI)) {
            server.repeatLastTurn(false)
                    .enqueue(FakeLlmServer.Turn.text("x").mode(FakeLlmServer.Mode.HTTP_500));

            CuteChat retrying = new CuteChatRetryWrapper(clientFor(server, ProviderProtocol.OPENAI),
                    new RetryPolicyProvider() {
                        @Override
                        public int getRetryCount() {
                            return 2;
                        }

                        @Override
                        public int getRetryIntervalSec() {
                            return 0;
                        }
                    });

            RuntimeException ex = assertThrows(RuntimeException.class, () -> retrying.call(prompt("必然失败")));
            assertTrue(ex.getMessage().contains("重试 2 次仍未成功"), "实际: " + ex.getMessage());
            assertEquals(3, server.requestCount(), "应执行 1 次初始调用 + 2 次重试");
        }
    }

    /**
     * 未知协议必须快速失败（不得静默降级）
     */
    @Test
    void failsFastOnUnknownProtocol() {
        assertNull(ProviderProtocol.fromName("NOT_A_PROTOCOL"));
        assertEquals(ProviderProtocol.OPENAI, ProviderProtocol.fromName("  openai  "),
                "协议名解析应容忍大小写与首尾空格");
    }

    /**
     * 消费完整流并汇总为响应列表
     */
    private static List<CuteChatResponse> consumeStream(CuteChat chat, String userText) {
        List<CuteChatResponse> received = new ArrayList<>();
        chat.streamConsume(prompt(userText), stream -> stream.forEach(chunk -> {
            if (chunk != null) {
                received.add(chunk);
            }
        }));
        return received;
    }
}
