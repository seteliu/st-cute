package com.stioc.cute.engine.loop.core;

import com.stioc.cute.engine.common.JsonKit;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.testkit.EngineFixture;
import com.stioc.cute.engine.testkit.EngineTestHarness;
import com.stioc.cute.engine.testkit.FakeLlmServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完整 ReAct 闭环集成测试。
 * <p>
 * 本类用真实引擎 + 内存存储 + 本机假 LLM 服务端跑通「提交消息 → 状态机对齐 → 流式推理 → 落库收口」
 * 全链路，验证的是真正的时序语义（异步拉起、事件链回填、等待屏障判定），而非被 mock 抹平后的假绿。
 * </p>
 */
class ReActLoopTest {

    /**
     * 无工具调用的单轮推理：应落一条 SUCCESS 助手消息、一条 SUCCESS 用户消息，且循环收口
     */
    @Test
    void completesSingleTurnWithoutTools() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            fixture.llm().enqueue(FakeLlmServer.Turn.text("这是一次完整回答。")
                    .usage(12L, 7L, 0L));

            fixture.submitAndAwait("你好");

            List<Message> messages = EngineTestHarness.allMessages(fixture.engine(), fixture.cid());
            assertEquals(2, messages.size(), "一轮无工具推理应恰好落 USER + ASSISTANT 两条消息");

            Message user = messages.get(0);
            assertEquals(MessageRole.USER, user.getRole());
            assertEquals(MessageStatus.SUCCESS, user.getStatus(), "挂起的用户输入应被消费为 SUCCESS");

            Message assistant = messages.get(1);
            assertEquals(MessageRole.ASSISTANT, assistant.getRole());
            assertEquals(MessageStatus.SUCCESS, assistant.getStatus());
            assertEquals("这是一次完整回答。", assistant.getContent(), "流式增量应被完整拼接");

            Conversation conversation = fixture.conversations().getById(fixture.cid());
            assertEquals(0, conversation.getLoopRunning(), "循环收尾后 loopRunning 必须归零");
        }
    }

    /**
     * 流式增量必须被逐片拼接，且思考流与正文流分别汇聚（不串流）
     */
    @Test
    void concatenatesStreamedChunksAndSeparatesReasoning() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            fixture.llm().chunkSize(2)
                    .enqueue(FakeLlmServer.Turn.text("正文内容").reasoning("先想一步"));

            fixture.submitAndAwait("请回答");

            Message assistant = EngineTestHarness.lastMessageOfRole(fixture.engine(),
                    fixture.cid(), MessageRole.ASSISTANT);
            assertNotNull(assistant);
            assertEquals("正文内容", assistant.getContent(), "多片正文增量应还原为完整文本");
            assertEquals("先想一步", assistant.getReasoningContent(), "思考流应与正文流分离汇聚");
        }
    }

    /**
     * 请求必须真实落在协议路径上，且请求体符合 OpenAI Chat 协议结构
     */
    @Test
    void sendsOpenAiCompatibleRequest() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            fixture.llm().enqueue(FakeLlmServer.Turn.text("ok"));

            fixture.submitAndAwait("请求体检查");

            assertEquals("/v1/chat/completions", fixture.llm().expectedPath());

            String body = fixture.llm().lastRequestBody();
            assertNotNull(body, "假服务端应收到请求体");
            JSONObject json = JsonKit.parseObject(body);
            assertEquals("fake-model", json.getString("model"));
            assertTrue(json.getBooleanValue("stream"), "ReAct 循环走流式调用，stream 必须为 true");
            assertTrue(json.containsKey("messages"), "请求体必须携带 messages");

            JSONArray messages = json.getJSONArray("messages");
            assertEquals("system", messages.getJSONObject(0).getString("role"),
                    "引擎恒定把系统提示词置于历史首条");
            assertEquals("user", messages.getJSONObject(messages.size() - 1).getString("role"));

            String headers = fixture.llm().requestHeaders().get(0);
            assertTrue(headers.contains("Authorization: Bearer fake-test-key"),
                    "必须携带 Bearer 认证头，实际请求头: " + headers);
        }
    }

    /**
     * 多轮 ReAct：模型连续两轮发起工具调用后自然收尾，循环应自动推进而非停滞
     */
    @Test
    void advancesThroughMultipleToolRounds() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder()
                .tool(com.stioc.cute.engine.testkit.FakeTool.readOnly("read_file"))
                .build()) {
            fixture.llm()
                    .enqueue(FakeLlmServer.Turn.toolCall("call_1", "read_file", "{\"text\":\"a\"}"))
                    .enqueue(FakeLlmServer.Turn.toolCall("call_2", "read_file", "{\"text\":\"b\"}"))
                    .enqueue(FakeLlmServer.Turn.text("两轮工具都跑完了。"));

            fixture.submitAndAwait("连续调用两次工具");

            assertEquals(3, fixture.llm().requestCount(), "两轮工具 + 一次收尾推理，共三次 LLM 调用");

            List<Message> tools = EngineTestHarness.messagesOfRole(fixture.engine(),
                    fixture.cid(), MessageRole.TOOL);
            assertEquals(2, tools.size(), "两轮工具应各落一条 TOOL 消息");
            for (Message tool : tools) {
                assertEquals(MessageStatus.SUCCESS, tool.getStatus(), "工具应执行成功");
            }

            Message lastAssistant = EngineTestHarness.lastMessageOfRole(fixture.engine(),
                    fixture.cid(), MessageRole.ASSISTANT);
            assertEquals("两轮工具都跑完了。", lastAssistant.getContent());
            assertEquals(MessageStatus.SUCCESS, lastAssistant.getStatus());
        }
    }
}
