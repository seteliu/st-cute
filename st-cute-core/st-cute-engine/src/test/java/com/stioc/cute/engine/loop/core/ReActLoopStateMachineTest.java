package com.stioc.cute.engine.loop.core;

import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.testkit.EngineFixture;
import com.stioc.cute.engine.testkit.EngineTestHarness;
import com.stioc.cute.engine.testkit.FakeLlmServer;
import com.stioc.cute.engine.testkit.ScriptedToolGuard;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReAct 循环状态机的异常与终态路径测试。
 * <p>
 * 循环的失败收场方式直接决定用户看到什么：熔断要给出明确警告、异常要落到 FAILED、
 * 弱网要能重试成功而不是白等一次失败。这些路径在正常流程里几乎不触发，只能靠假服务端
 * 主动编排（错误帧 / HTTP 500 / 幻觉工具名）来覆盖。
 * </p>
 */
class ReActLoopStateMachineTest {

    /**
     * 连续三轮请求不存在的工具应触发熔断保护：助手消息落 FAILED 且带熔断提示
     */
    @Test
    void meltsDownAfterRepeatedUnknownTools() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            // 剧本耗尽后重复末条：持续请求未注册工具
            fixture.llm().enqueue(FakeLlmServer.Turn.toolCall("call_x", "no_such_tool", "{}"));

            fixture.submitAndAwait("请调用不存在的工具");

            Message assistant = EngineTestHarness.lastMessageOfRole(fixture.engine(),
                    fixture.cid(), MessageRole.ASSISTANT);
            assertNotNull(assistant);
            assertEquals(MessageStatus.FAILED, assistant.getStatus(), "熔断后助手消息应为 FAILED 终态");
            assertTrue(assistant.getContent().contains("熔断"),
                    "助手消息应携带熔断警告，实际内容: " + assistant.getContent());

            assertTrue(fixture.llm().requestCount() >= 3,
                    "熔断阈值是连续 3 轮未知工具，LLM 至少应被调用 3 次，实际: " + fixture.llm().requestCount());
        }
    }

    /**
     * 熔断只在「连续」未知工具时生效：中间穿插一次正常工具应重置计数，不发生熔断
     */
    @Test
    void resetsMeltdownCounterOnValidToolCall() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder()
                .tool(com.stioc.cute.engine.testkit.FakeTool.readOnly("read_file"))
                .build()) {
            fixture.llm()
                    .enqueue(FakeLlmServer.Turn.toolCall("call_1", "no_such_tool", "{}"))
                    .enqueue(FakeLlmServer.Turn.toolCall("call_2", "read_file", "{}"))
                    .enqueue(FakeLlmServer.Turn.toolCall("call_3", "no_such_tool", "{}"))
                    .enqueue(FakeLlmServer.Turn.toolCall("call_4", "no_such_tool", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("最终收尾。"));

            fixture.submitAndAwait("夹杂正常工具");

            Message lastAssistant = EngineTestHarness.lastMessageOfRole(fixture.engine(),
                    fixture.cid(), MessageRole.ASSISTANT);
            assertEquals(MessageStatus.SUCCESS, lastAssistant.getStatus(),
                    "中间穿插合法工具后计数应重置，不得熔断");
            assertEquals("最终收尾。", lastAssistant.getContent());
        }
    }

    /**
     * 流中错误帧导致本次调用彻底失败（重试 0 次）时，循环应落异常终态而非挂死
     */
    @Test
    void failsLoopWhenStreamErrorsWithoutRetry() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            fixture.llm().enqueue(FakeLlmServer.Turn.text("不该出现")
                    .mode(FakeLlmServer.Mode.STREAM_ERROR_FRAME));

            fixture.submitAndAwait("触发错误帧");

            Message assistant = EngineTestHarness.lastMessageOfRole(fixture.engine(),
                    fixture.cid(), MessageRole.ASSISTANT);
            assertNotNull(assistant, "异常路径也必须落到助手消息终态，不能出现悬挂消息");
            assertEquals(MessageStatus.FAILED, assistant.getStatus(),
                    "错误帧使调用失败，助手消息应为 FAILED");

            // 循环必须彻底收口，否则前端会永远转圈
            assertTrue(EngineTestHarness.isLoopIdle(fixture.engine(), fixture.cid()));
        }
    }

    /**
     * 建连即 HTTP 500 时，重试策略应真实生效：首次失败 + 重试 2 次后成功
     */
    @Test
    void recoversFromTransientFailureByRetry() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder()
                // 重试间隔设为 0 秒，避免用例真实等待；次数 2 表示最多额外重试 2 次
                .retryPolicyProvider(new com.stioc.cute.engine.llm.RetryPolicyProvider() {
                    @Override
                    public int getRetryCount() {
                        return 2;
                    }

                    @Override
                    public int getRetryIntervalSec() {
                        return 0;
                    }
                })
                .build()) {
            fixture.llm()
                    .enqueue(FakeLlmServer.Turn.text("x").mode(FakeLlmServer.Mode.HTTP_500))
                    .enqueue(FakeLlmServer.Turn.text("x").mode(FakeLlmServer.Mode.HTTP_500))
                    .enqueue(FakeLlmServer.Turn.text("重试后成功。"));

            fixture.submitAndAwait("经受两次失败");

            Message assistant = EngineTestHarness.lastMessageOfRole(fixture.engine(),
                    fixture.cid(), MessageRole.ASSISTANT);
            assertEquals(MessageStatus.SUCCESS, assistant.getStatus(), "重试应在耗尽前恢复成功");
            assertEquals("重试后成功。", assistant.getContent());
            assertEquals(3, fixture.llm().requestCount(), "1 次初始调用 + 2 次重试，共 3 次请求");
        }
    }

    /**
     * 重试耗尽后应落到失败终态，且请求次数与重试次数严格一致（不多不少）
     */
    @Test
    void failsAfterRetriesExhausted() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder()
                .retryPolicyProvider(new com.stioc.cute.engine.llm.RetryPolicyProvider() {
                    @Override
                    public int getRetryCount() {
                        return 1;
                    }

                    @Override
                    public int getRetryIntervalSec() {
                        return 0;
                    }
                })
                .build()) {
            // 剧本只有一条 HTTP 500 且关闭末条重复：后续请求直接 500
            fixture.llm().repeatLastTurn(false)
                    .enqueue(FakeLlmServer.Turn.text("x").mode(FakeLlmServer.Mode.HTTP_500));

            fixture.submitAndAwait("必然失败");

            assertEquals(2, fixture.llm().requestCount(), "重试 1 次即共 2 次请求");

            Message assistant = EngineTestHarness.lastMessageOfRole(fixture.engine(),
                    fixture.cid(), MessageRole.ASSISTANT);
            assertEquals(MessageStatus.FAILED, assistant.getStatus());
        }
    }

    /**
     * 上下文超窗时应触发压缩链路：产生 COMPRESSED 消息、旧消息被打上模型不可见标记
     */
    @Test
    void compressesContextWhenWindowExceeded() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder()
                // 窗口压到极小：一次压缩调用 + 一次正常推理，即可稳定越过 85% 压缩门限
                .contextSize(100)
                .build()) {
            // 压缩调用走非流式 call()，正常推理走流式，二者按请求顺序各取一条台词
            fixture.llm()
                    .enqueue(FakeLlmServer.Turn.text("【状态检查点与摘要】上下文已压缩。"))
                    .enqueue(FakeLlmServer.Turn.text("压缩后的正式回答。"));

            // 足够长的用户输入，确保历史 token 越过极小窗口的门限
            String longInput = "请阅读以下材料并总结要点：".repeat(30);
            fixture.submitAndAwait(longInput);

            Message compressed = EngineTestHarness.lastMessageOfRole(fixture.engine(),
                    fixture.cid(), MessageRole.COMPRESSED);
            assertNotNull(compressed, "超窗后必须产生 COMPRESSED 压缩记忆消息");
            assertEquals(MessageStatus.SUCCESS, compressed.getStatus());
            assertTrue(compressed.getContent().contains("System Memory Summary"),
                    "压缩消息正文应携带系统记忆摘要，实际: " + compressed.getContent());

            boolean anyArchived = EngineTestHarness.allMessages(fixture.engine(), fixture.cid()).stream()
                    .anyMatch(m -> Boolean.FALSE.equals(m.getVisibleToModel()));
            assertTrue(anyArchived, "归档段消息应被置为对大模型不可见");
        }
    }
}
