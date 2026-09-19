package com.stioc.cute.engine.llm;

import com.stioc.cute.engine.llm.types.CuteChatResponse;
import com.stioc.cute.engine.llm.types.CutePrompt;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CuteChatRetryWrapper} 透明重试装饰器单元测试。
 * <p>
 * 重试策略直接决定弱网/限流场景下用户是否白等一次失败。本测试以可控失败次数的
 * 假客户端为桩，覆盖「成功即返回、失败重试、重试耗尽抛错」三条路径。
 * 重试间隔统一设为 0 秒，避免测试真实等待。
 * </p>
 */
class CuteChatRetryWrapperTest {

    /**
     * 假客户端：前 failTimes 次调用抛异常，之后返回固定响应
     */
    private static final class FlakyChat implements CuteChat {

        private final int failTimes;
        private int callCount;
        private int streamCount;

        private FlakyChat(int failTimes) {
            this.failTimes = failTimes;
        }

        @Override
        public CuteChatResponse call(CutePrompt prompt) {
            callCount++;
            if (callCount <= failTimes) {
                throw new RuntimeException("模拟第 " + callCount + " 次失败");
            }
            return CuteChatResponse.builder().content("成功").build();
        }

        @Override
        public void streamConsume(CutePrompt prompt, Consumer<Stream<CuteChatResponse>> consumer) {
            streamCount++;
            if (streamCount <= failTimes) {
                throw new RuntimeException("模拟流式第 " + streamCount + " 次失败");
            }
            consumer.accept(Stream.of(CuteChatResponse.builder().content("流式成功").build()));
        }
    }

    /**
     * 零间隔重试策略：仅定制次数，间隔固定为 0 秒以避免测试真实等待
     */
    private static RetryPolicyProvider zeroInterval(int retryCount) {
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

    private final CutePrompt prompt = CutePrompt.builder().messages(List.of()).build();

    /**
     * 首次即成功时不得重试，且结果原样透传
     */
    @Test
    void returnsImmediatelyOnSuccess() {
        FlakyChat delegate = new FlakyChat(0);
        CuteChat wrapper = new CuteChatRetryWrapper(delegate, zeroInterval(3));

        assertEquals("成功", wrapper.call(prompt).getContent());
        assertEquals(1, delegate.callCount, "成功路径不得产生额外调用");
    }

    /**
     * 失败后应按策略重试，最终成功返回
     */
    @Test
    void retriesUntilSuccess() {
        FlakyChat delegate = new FlakyChat(2);
        CuteChat wrapper = new CuteChatRetryWrapper(delegate, zeroInterval(3));

        assertEquals("成功", wrapper.call(prompt).getContent());
        assertEquals(3, delegate.callCount, "应重试至第 3 次成功（初始 1 次 + 重试 2 次）");
    }

    /**
     * 重试次数耗尽后应抛出异常，且不吞掉最后一次失败原因
     */
    @Test
    void throwsWhenRetriesExhausted() {
        FlakyChat delegate = new FlakyChat(100);
        CuteChat wrapper = new CuteChatRetryWrapper(delegate, zeroInterval(2));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> wrapper.call(prompt));
        assertTrue(ex.getMessage().contains("重试 2 次仍未成功"),
                "异常信息应说明重试耗尽，实际: " + ex.getMessage());
        assertEquals(3, delegate.callCount, "应执行 1 次初始调用 + 2 次重试");
    }

    /**
     * 重试次数为 0 时不重试，首次失败直接抛出
     */
    @Test
    void doesNotRetryWhenRetryCountZero() {
        FlakyChat delegate = new FlakyChat(1);
        CuteChat wrapper = new CuteChatRetryWrapper(delegate, zeroInterval(0));

        assertThrows(RuntimeException.class, () -> wrapper.call(prompt));
        assertEquals(1, delegate.callCount, "重试次数为 0 时不应有额外调用");
    }

    /**
     * 装饰器在委托成功后应立即返回，不产生额外调用（成功路径的唯一性）
     */
    @Test
    void doesNotDoubleInvokeOnSuccess() {
        FlakyChat delegate = new FlakyChat(0);
        CuteChat wrapper = new CuteChatRetryWrapper(delegate, zeroInterval(3));

        wrapper.call(prompt);
        wrapper.call(prompt);

        assertEquals(2, delegate.callCount, "两次调用应对应两次委托执行，不得有重试开销");
    }

    /**
     * 流式调用同样具备重试能力
     */
    @Test
    void retriesStreamingConsume() {
        FlakyChat delegate = new FlakyChat(2);
        CuteChat wrapper = new CuteChatRetryWrapper(delegate, zeroInterval(3));

        List<String> received = new ArrayList<>();
        wrapper.streamConsume(prompt, stream -> stream.forEach(r -> received.add(r.getContent())));

        assertEquals(List.of("流式成功"), received, "重试后应收到成功的流式内容");
        assertEquals(3, delegate.streamCount, "流式应重试至第 3 次成功");
    }

    /**
     * 流式重试耗尽应抛出异常
     */
    @Test
    void throwsWhenStreamRetriesExhausted() {
        FlakyChat delegate = new FlakyChat(100);
        CuteChat wrapper = new CuteChatRetryWrapper(delegate, zeroInterval(1));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> wrapper.streamConsume(prompt, stream -> stream.count()));
        assertTrue(ex.getMessage().contains("重试 1 次仍未成功"),
                "异常信息应说明重试耗尽，实际: " + ex.getMessage());
    }

    /**
     * 装饰器本身仍满足 CuteChat 契约（可继续被装饰或替换）
     */
    @Test
    void implementsCuteChatContract() {
        CuteChat wrapper = new CuteChatRetryWrapper(new FlakyChat(0), zeroInterval(1));
        assertTrue(wrapper instanceof CuteChat, "装饰器必须实现 CuteChat 契约");
    }
}
