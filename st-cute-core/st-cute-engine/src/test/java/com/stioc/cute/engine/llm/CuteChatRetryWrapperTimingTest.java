package com.stioc.cute.engine.llm;

import com.stioc.cute.engine.llm.types.CuteChatResponse;
import com.stioc.cute.engine.llm.types.CutePrompt;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CuteChatRetryWrapper} 重试等待的「速度语义」测试。
 * <p>
 * <b>当前状态：整类 {@link Disabled}</b>。本类源于「重试成功后吐字明显变慢」这一生产观察的
 * 探测性验证，经核实其结论<b>不足以解释该现象</b>——下面三条事实只能说明重试会带来偶发的秒级停顿，
 * 无法解释用户观测到的「持续数 t/s 慢速吐字」（且内容重复缺陷的方向与「字数涨得慢」相反）。
 * 该现象已由用户裁定转入专项处理，本类作为已知事实的存档保留，待专项分析时启用或改写。
 * </p>
 * <p>
 * 已锁定的三条速度事实（启用后仍然有效）：
 * <ol>
 *   <li><b>等待发生在调用线程上</b>：{@code Thread.sleep} 直接跑在循环线程内，
 *       等待期间整个会话停摆（不是后台并行等待）；</li>
 *   <li><b>等待造成流中可见停顿</b>：重试间隔会原样出现在「最后一片内容 → 重试后首片内容」之间，
 *       用户侧表现就是吐字卡住一段时间；</li>
 *   <li><b>等待被计入调用耗时</b>：装饰器的等待包含在 {@code streamConsume} 墙钟内，
 *       因此基于调用耗时统计的吐字速率会被系统性低估。</li>
 * </ol>
 * </p>
 * <p>
 * 用例的真实性依赖「流已产出部分内容后失败」这一形态：假客户端返回的流在抛出前会先产出若干片，
 * 与生产环境上游中途切断 SSE 的行为一致。
 * </p>
 */
@Disabled("重试后持续变慢成因未定性，转入专项处理；本类仅存档已确认的秒级停顿事实")
class CuteChatRetryWrapperTimingTest {

    /**
     * 测试用重试间隔（秒）：取 1 秒即可稳定测出停顿，且不拖慢用例
     */
    private static final int INTERVAL_SEC = 1;

    /**
     * 停顿判定下限：留 50ms 容差，避免调度抖动造成假红
     */
    private static final long STALL_LOWER_BOUND_MS = 950L;

    /**
     * 模拟「先产出部分内容、再失败」的假客户端。
     * <p>第 1 次调用返回的流会先产出若干片内容，随后在迭代中抛异常；第 2 次调用返回完整成功流。</p>
     */
    private static final class PartialThenFailChat implements CuteChat {

        private final List<String> partialChunks;
        private final String successContent;
        private int attempts;

        private PartialThenFailChat(List<String> partialChunks, String successContent) {
            this.partialChunks = partialChunks;
            this.successContent = successContent;
        }

        @Override
        public CuteChatResponse call(CutePrompt prompt) {
            throw new UnsupportedOperationException("本用例只覆盖流式路径");
        }

        @Override
        public void streamConsume(CutePrompt prompt, Consumer<Stream<CuteChatResponse>> consumer) {
            attempts++;
            if (attempts == 1) {
                consumer.accept(partialThenThrow(partialChunks));
                return;
            }
            consumer.accept(Stream.of(CuteChatResponse.builder().content(successContent).build()));
        }
    }

    /**
     * 构造「产出若干片后抛异常」的流：前 partialChunks.size() 次 next() 返回内容，
     * 再次 next() 抛异常，从而在消费途中（而非调用前）触发失败。
     */
    private static Stream<CuteChatResponse> partialThenThrow(List<String> chunks) {
        Iterator<CuteChatResponse> iterator = new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public CuteChatResponse next() {
                if (index >= chunks.size()) {
                    throw new RuntimeException("模拟流中途失败（已产出 " + index + " 片）");
                }
                return CuteChatResponse.builder().content(chunks.get(index++)).build();
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false);
    }

    private static RetryPolicyProvider policy(int retryCount, int intervalSec) {
        return new RetryPolicyProvider() {
            @Override
            public int getRetryCount() {
                return retryCount;
            }

            @Override
            public int getRetryIntervalSec() {
                return intervalSec;
            }
        };
    }

    private final CutePrompt prompt = CutePrompt.builder().messages(List.of()).build();

    /**
     * 重试等待跑在调用线程上：等待期间该线程被完全占用（会话停摆），不存在后台并行等待。
     */
    @Test
    void retryWaitBlocksTheCallingThread() {
        PartialThenFailChat delegate = new PartialThenFailChat(List.of("甲"), "乙");
        CuteChat wrapper = new CuteChatRetryWrapper(delegate, policy(1, INTERVAL_SEC));

        Thread caller = Thread.currentThread();
        List<Thread> consumerThreads = new CopyOnWriteArrayList<>();

        wrapper.streamConsume(prompt, stream -> stream.forEach(r -> consumerThreads.add(Thread.currentThread())));

        assertTrue(consumerThreads.size() >= 2, "两次尝试都应在同一线程上被消费，实际次数: " + consumerThreads.size());
        for (Thread t : consumerThreads) {
            assertSame(caller, t, "流式消费必须发生在调用线程上，重试等待期间该线程被阻塞");
        }
        assertEquals(2, delegate.attempts, "应为 1 次失败 + 1 次重试");
    }

    /**
     * 重试等待会造成流中可见停顿：最后一片内容到重试后首片内容之间的间隔，
     * 至少等于配置的重试间隔（用户侧即为「吐字卡住」）。
     */
    @Test
    void retryIntervalAppearsAsVisibleStallBetweenChunks() {
        PartialThenFailChat delegate = new PartialThenFailChat(List.of("第一片", "第二片"), "重试后的内容");
        CuteChat wrapper = new CuteChatRetryWrapper(delegate, policy(1, INTERVAL_SEC));

        List<Long> stamps = new CopyOnWriteArrayList<>();
        List<String> texts = new CopyOnWriteArrayList<>();
        wrapper.streamConsume(prompt, stream -> stream.forEach(r -> {
            texts.add(r.getContent());
            stamps.add(System.currentTimeMillis());
        }));

        assertEquals(List.of("第一片", "第二片", "重试后的内容"), new ArrayList<>(texts),
                "失败前已产出的内容与重试后的内容都会被推送（这正是重复的成因）");

        assertEquals(3, stamps.size());
        long stall = Collections.max(
                List.of(stamps.get(1) - stamps.get(0), stamps.get(2) - stamps.get(1)));
        // 相邻间隔中必然存在一个跨越「重试等待」的大间隔
        long retryGap = stamps.get(2) - stamps.get(1);
        assertTrue(retryGap >= STALL_LOWER_BOUND_MS,
                "重试间隔应造成可见停顿（>=" + STALL_LOWER_BOUND_MS + "ms），实际停顿: " + retryGap + "ms");

        // 同一次尝试内的产片间隔应远小于重试停顿，确保断言确实在测「重试」而非本地慢
        assertTrue(stamps.get(1) - stamps.get(0) < STALL_LOWER_BOUND_MS,
                "同一次尝试内的相邻产片不应产生秒级停顿，实际: " + (stamps.get(1) - stamps.get(0)) + "ms");
        assertTrue(stall > 0, "停顿测量必须为正");
    }

    /**
     * 重试等待计入调用墙钟耗时：调用方观测到的总耗时至少包含一次完整重试间隔。
     * <p>这解释了「按调用耗时统计的吐字速率被低估」的现象。</p>
     */
    @Test
    void retryWaitIsChargedToCallWallClock() {
        PartialThenFailChat delegate = new PartialThenFailChat(List.of("甲"), "乙");
        CuteChat wrapper = new CuteChatRetryWrapper(delegate, policy(1, INTERVAL_SEC));

        long start = System.currentTimeMillis();
        wrapper.streamConsume(prompt, stream -> stream.count());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= STALL_LOWER_BOUND_MS,
                "调用墙钟应包含重试等待（>=" + STALL_LOWER_BOUND_MS + "ms），实际: " + elapsed + "ms");
    }
}
