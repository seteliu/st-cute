package com.stioc.cute.engine.loop.core;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.event.AgentEventListener;
import com.stioc.cute.engine.event.types.AgentEvent;
import com.stioc.cute.engine.event.types.AgentEventType;
import com.stioc.cute.engine.event.types.ListenerTier;
import com.stioc.cute.engine.event.types.StreamChunkPayload;
import com.stioc.cute.engine.llm.RetryPolicyProvider;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.testkit.EngineFixture;
import com.stioc.cute.engine.testkit.EngineTestHarness;
import com.stioc.cute.engine.testkit.FakeLlmServer;
import com.stioc.cute.engine.testkit.FakeTool;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「流中途失败 → 透明重试成功」路径的复现与防线测试。
 * <p>
 * 复现场景：服务端在<b>已经推送了部分思考内容</b>之后掐断连接，框架经
 * {@link com.stioc.cute.engine.llm.CuteChatRetryWrapper} 无缝重试并最终成功。
 * 这是生产环境「上游限流/网络抖动把流切断」的真实形态，也是重试重发已推送内容的唯一可靠触发点。
 * </p>
 * <p>
 * 本测试锁定两条语义：
 * <ol>
 *   <li><b>内容不重复</b>：重试成功后的思考流/正文流累计结果应与「一次成功」等价，
 *       不得把失败前已推送的片段再叠一遍（重复会让用户看到两遍相同推理，并让落库内容膨胀）；</li>
 *   <li><b>事件流可安全渲染</b>：重试必然重发已推送内容，而前端消费是累加语义，
 *       故重试需伴随一次性「流重置」信号，否则前端会把重发内容当作新内容继续累加。</li>
 * </ol>
 * </p>
 */
class RetryMidStreamDuplicateTest {

    /**
     * 完整思考文本（字符数刻意做成可辨识的短串，重复一遍即刻暴露）
     */
    private static final String FULL_THINKING = "思考片段甲。思考片段乙。";

    /**
     * 完整正文文本
     */
    private static final String FULL_CONTENT = "最终回答正文。";

    /**
     * 零间隔重试策略：避免用例真实等待
     */
    private static RetryPolicyProvider retryPolicy(int retryCount) {
        return new RetryPolicyProvider() {
            @Override
            public int getRetryCount() {
                return retryCount;
            }

            @Override
            public int getRetryIntervalSec() {
                return 0;
            }
        };
    }

    /**
     * 通知层流式事件采集器：按「前端消费语义」模拟渲染层的累加与清空处理。
     * <p>
     * 关键点：收到清空信号（{@code type=CLEAR}）时丢弃本条流已累积内容，
     * 与前端 {@code Home.vue#handleChatStream} 的处理完全一致——本采集器因此可作为
     * 「前端拼接结果」的替身，用于断言端到端不重复。
     * </p>
     * <p>
     * 同时保留「原始事件流」视角（不做清空处理的全量片段），用于钉住
     * 「服务端原始事件流确实仍带重放」这一事实。极速模式（默认）下流式事件经通知车道
     * 异步消费，故断言前需等待其收敛。
     * </p>
     */
    private static final class StreamCollector implements AgentEventListener {

        /** 按前端消费语义累积的思考流（遇 CLEAR 清空） */
        private final StringBuilder frontendThinking = new StringBuilder();

        /** 按前端消费语义累积的正文流（遇 CLEAR 清空） */
        private final StringBuilder frontendContent = new StringBuilder();

        /** 原始事件流：不做清空处理的全量思考片段（体现重放） */
        private final List<String> rawThinkingChunks = new CopyOnWriteArrayList<>();

        /** 观察到的清空信号次数 */
        private final AtomicInteger clearSignals = new AtomicInteger();

        @Override
        public ListenerTier getTier() {
            return ListenerTier.NOTIFICATION;
        }

        @Override
        public int getPriority() {
            return 10;
        }

        @Override
        public void onEvent(AgentEvent event) {
            if (event == null || event.getType() == null) {
                return;
            }
            if (!(event.getPayload() instanceof StreamChunkPayload chunk)) {
                return;
            }
            boolean isThinking = event.getType() == AgentEventType.AGENT_THINKING_STREAM;
            boolean isContent = event.getType() == AgentEventType.AGENT_CONTENT_STREAM;
            if (!isThinking && !isContent) {
                return;
            }

            // 清空信号：模拟前端丢弃本条流已累积内容（需前置判定，信号不带文本）
            if (chunk.isClear()) {
                clearSignals.incrementAndGet();
                if (isThinking) {
                    frontendThinking.setLength(0);
                } else {
                    frontendContent.setLength(0);
                }
                return;
            }

            String text = chunk.getText();
            if (text == null || text.isEmpty()) {
                return;
            }
            if (isThinking) {
                rawThinkingChunks.add(text);
                frontendThinking.append(text);
            } else {
                frontendContent.append(text);
            }
        }

        /** 前端消费语义下的思考内容 */
        String thinking() {
            return frontendThinking.toString();
        }

        /** 前端消费语义下的正文内容 */
        String content() {
            return frontendContent.toString();
        }

        /** 原始事件流拼接结果（不做清空处理，体现重放） */
        String rawThinking() {
            return String.join("", rawThinkingChunks);
        }

        /** 观察到的清空信号次数 */
        int clearSignalCount() {
            return clearSignals.get();
        }
    }

    /**
     * 【核心复现】流中途掐断 → 重试成功：思考内容不得重复、不得膨胀。
     */
    @Test
    void thinkingMustNotDuplicateWhenRetrySucceedsAfterPartialOutput() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder()
                .retryPolicyProvider(retryPolicy(2))
                .build()) {
            // 帧粒度 1：让思考被拆成多片，前几帧即可产出可见内容
            fixture.llm().chunkSize(1)
                    // 第 1 次：先正常产出思考与正文，再注入错误帧中断（真实触发重试重发）
                    .enqueue(FakeLlmServer.Turn.text(FULL_CONTENT)
                            .reasoning(FULL_THINKING)
                            .mode(FakeLlmServer.Mode.STREAM_ERROR_AFTER_PARTIAL))
                    // 第 2 次：完整成功
                    .enqueue(FakeLlmServer.Turn.text(FULL_CONTENT).reasoning(FULL_THINKING));

            fixture.submitAndAwait("触发流中途失败");

            assertEquals(2, fixture.llm().requestCount(),
                    "应为「1 次失败 + 1 次重试成功」共 2 次请求");

            Message assistant = EngineTestHarness.lastMessageOfRole(fixture.engine(),
                    fixture.cid(), MessageRole.ASSISTANT);
            assertNotNull(assistant, "重试成功后必须落到助手消息");
            assertEquals(MessageStatus.SUCCESS, assistant.getStatus(), "重试应在耗尽前恢复成功");

            String reasoning = assistant.getReasoningContent();
            assertNotNull(reasoning, "思考内容不得为空");
            assertEquals(FULL_THINKING, reasoning,
                    "重试成功后思考内容应恰好一份（出现两遍即为重复缺陷），实际: " + reasoning);
        }
    }

    /**
     * 【核心复现】同上，校验正文在重试后同样不得重复。
     */
    @Test
    void contentMustNotDuplicateWhenRetrySucceedsAfterPartialOutput() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder()
                .retryPolicyProvider(retryPolicy(2))
                .build()) {
            fixture.llm().chunkSize(1)
                    .enqueue(FakeLlmServer.Turn.text(FULL_CONTENT)
                            .reasoning(FULL_THINKING)
                            .mode(FakeLlmServer.Mode.STREAM_ERROR_AFTER_PARTIAL))
                    .enqueue(FakeLlmServer.Turn.text(FULL_CONTENT).reasoning(FULL_THINKING));

            fixture.submitAndAwait("触发流中途失败");

            Message assistant = EngineTestHarness.lastMessageOfRole(fixture.engine(),
                    fixture.cid(), MessageRole.ASSISTANT);
            assertNotNull(assistant);
            assertEquals(MessageStatus.SUCCESS, assistant.getStatus());
            assertEquals(FULL_CONTENT, assistant.getContent(),
                    "重试成功后正文应恰好一份（出现两遍即为重复缺陷），实际: " + assistant.getContent());
        }
    }

    /**
     * 【覆盖工具调用】工具调用参数的最终内容必须完整且可解析。
     * <p>
     * <b>本用例并非「重复缺陷」的防线，原因值得记录</b>：三协议客户端都只在流<b>正常结束</b>时
     * 才发出工具调用汇总帧（{@code emitSummaryIfNeeded} 挂在 {@code [DONE]}/{@code message_stop} 之后），
     * 流中途失败时异常直接抛出、汇总帧根本不发出。因此失败前累积在客户端内部的参数会被整体丢弃，
     * 重试重发不会造成重复——这层保护来自客户端的「流末汇总」设计，而非消费方的累加器复位。
     * </p>
     * <p>
     * 本用例的实际价值是锁住用户可见结果：重试成功后工具入参恰好一份且仍是合法 JSON。
     * 若将来把工具调用改为「逐片增量发出」，该设计保护即失效、重复会真实出现（表现为入参 JSON 破损），
     * 本用例届时会失败并给出提示。
     * </p>
     */
    @Test
    void toolCallArgumentsMustStayIntactWhenRetrySucceedsAfterPartialOutput() throws IOException {
        String argumentsJson = "{\"command\":\"echo hello\",\"timeout\":1000}";

        try (EngineFixture fixture = EngineFixture.builder()
                .retryPolicyProvider(retryPolicy(2))
                .tool(FakeTool.readOnly("read_file"))
                .build()) {
            fixture.llm().chunkSize(1)
                    .enqueue(FakeLlmServer.Turn.toolCall("call_retry", "read_file", argumentsJson)
                            .mode(FakeLlmServer.Mode.STREAM_ERROR_AFTER_PARTIAL))
                    .enqueue(FakeLlmServer.Turn.toolCall("call_retry", "read_file", argumentsJson));

            fixture.submit("触发工具调用途中失败");

            EngineTestHarness.awaitCondition(() -> {
                Message tool = EngineTestHarness.toolMessage(fixture.engine(), fixture.cid(), "call_retry");
                return tool != null && tool.getStatus() != MessageStatus.PENDING;
            }, "重试后的工具消息落库并离开 PENDING");

            Message tool = EngineTestHarness.toolMessage(fixture.engine(), fixture.cid(), "call_retry");
            assertNotNull(tool, "工具消息必须存在");

            JSONObject desc = JSON.parseObject(tool.getToolCalls());
            String actualArgs = desc.getString("arguments");
            assertEquals(argumentsJson, actualArgs,
                    "工具入参应恰好一份且完整，实际: " + actualArgs);

            // 强校验：入参必须是合法 JSON（重复拼接会破坏结构）
            JSONObject parsed = JSON.parseObject(actualArgs);
            assertEquals("echo hello", parsed.getString("command"), "入参 JSON 应可正常解析且内容正确");
        }
    }

    /**
     * 【事实存档】服务端<b>原始事件流</b>在透明重试下仍会重放已推送片段（不做清空处理的全量拼接会重复）。
     * <p>
     * 这是一个事实描述用例，不是缺陷防线：重放本身是重试的必然结果，
     * 清空信号（{@code type=CLEAR}）只是让下游有能力丢弃它。
     * 若将来有人误以为「服务端已彻底不重放」而据此简化下游逻辑，本用例会失败提醒。
     * </p>
     */
    @Test
    void rawStreamEventsStillReflowContentOnRetry() throws IOException {
        StreamCollector collector = new StreamCollector();
        try (EngineFixture fixture = EngineFixture.builder()
                .retryPolicyProvider(retryPolicy(2))
                .eventListener(collector)
                .build()) {
            fixture.llm().chunkSize(1)
                    .enqueue(FakeLlmServer.Turn.text(FULL_CONTENT)
                            .reasoning(FULL_THINKING)
                            .mode(FakeLlmServer.Mode.STREAM_ERROR_AFTER_PARTIAL))
                    .enqueue(FakeLlmServer.Turn.text(FULL_CONTENT).reasoning(FULL_THINKING));

            fixture.submitAndAwait("触发流中途失败");
            awaitStreamSettled(collector);

            assertEquals(FULL_THINKING + FULL_THINKING, collector.rawThinking(),
                    "原始事件流应为「失败前片段 + 重试重发片段」（重放是重试的必然结果），实际: "
                            + collector.rawThinking());
        }
    }

    /**
     * 【核心保证】前端消费语义下重试后不重复：清空信号必须生效，重放内容不得被继续累加。
     * <p>
     * 本用例模拟前端 {@code Home.vue#handleChatStream} 的消费语义（遇 CLEAR 丢弃已累积内容），
     * 断言端到端的三个结果一致且各恰好一份：
     * <ul>
     *   <li>前端拼接结果 = 一次成功应有的内容；</li>
     *   <li>落库内容 = 一次成功应有的内容；</li>
     *   <li>过程中确实收到过清空信号（否则本用例的「干净」来自别的机制，需重新评估）。</li>
     * </ul>
     * 若将来清空信号被误删或前端忽略它，本用例会立刻失败。
     * </p>
     */
    @Test
    void frontendAggregationStaysCleanThanksToClearSignal() throws IOException {
        StreamCollector collector = new StreamCollector();
        try (EngineFixture fixture = EngineFixture.builder()
                .retryPolicyProvider(retryPolicy(2))
                .eventListener(collector)
                .build()) {
            fixture.llm().chunkSize(1)
                    .enqueue(FakeLlmServer.Turn.text(FULL_CONTENT)
                            .reasoning(FULL_THINKING)
                            .mode(FakeLlmServer.Mode.STREAM_ERROR_AFTER_PARTIAL))
                    .enqueue(FakeLlmServer.Turn.text(FULL_CONTENT).reasoning(FULL_THINKING));

            fixture.submitAndAwait("触发流中途失败");

            Message assistant = EngineTestHarness.lastMessageOfRole(fixture.engine(),
                    fixture.cid(), MessageRole.ASSISTANT);
            assertNotNull(assistant);
            awaitStreamSettled(collector);

            // 1. 清空信号确实发出过：思考流 + 正文流各一条
            assertEquals(2, collector.clearSignalCount(),
                    "重试时应发出成对的清空信号（思考流 + 正文流各一条），实际: " + collector.clearSignalCount());

            // 2. 前端消费语义下的拼接结果恰好一份，且与落库一致
            assertEquals(FULL_THINKING, collector.thinking(),
                    "前端思考流拼接结果应恰好一份，实际: " + collector.thinking());
            assertEquals(FULL_CONTENT, collector.content(),
                    "前端正文流拼接结果应恰好一份，实际: " + collector.content());

            // 3. 与落库内容一致（端到端闭合）
            assertEquals(assistant.getReasoningContent(), collector.thinking(),
                    "前端思考流应与落库思考内容一致");
            assertEquals(assistant.getContent(), collector.content(),
                    "前端正文流应与落库正文内容一致");
        }
    }

    /**
     * 等待流式事件消费收敛：采集到的思考长度连续多次采样保持不变
     */
    private static void awaitStreamSettled(StreamCollector collector) {
        AtomicInteger lastLength = new AtomicInteger(-1);
        AtomicInteger stableHits = new AtomicInteger(0);
        EngineTestHarness.awaitCondition(() -> {
            int current = collector.thinking().length();
            if (current == lastLength.get()) {
                return stableHits.incrementAndGet() >= 3;
            }
            lastLength.set(current);
            stableHits.set(0);
            return false;
        }, "流式事件消费收敛（思考长度稳定）");
    }
}
