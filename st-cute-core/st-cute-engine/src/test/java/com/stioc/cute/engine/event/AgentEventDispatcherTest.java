package com.stioc.cute.engine.event;

import com.stioc.cute.engine.assembly.EngineInfra;
import com.stioc.cute.engine.common.NotifyExecutor;
import com.stioc.cute.engine.event.types.AgentEvent;
import com.stioc.cute.engine.event.types.AgentEventType;
import com.stioc.cute.engine.event.types.ListenerTier;
import com.stioc.cute.engine.event.types.StreamChunkPayload;
import com.stioc.cute.engine.testkit.EngineStubs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentEventDispatcher} 分发模式单元测试。
 * <p>
 * 钉住两种模式的行为差异：极速模式下网络型流式事件与普通事件一样异步投递车道；
 * 非极速模式下由调用线程同步直调（背压通道）。另覆盖网络型事件的监听器异常隔离。
 * </p>
 */
class AgentEventDispatcherTest {

    /**
     * 记录执行线程名的通知层监听器
     */
    private static final class ThreadCapturingListener implements AgentEventListener {

        private final List<String> threads = new ArrayList<>();
        private final CountDownLatch latch;
        private final boolean throwOnEvent;

        private ThreadCapturingListener(int expected, boolean throwOnEvent) {
            this.latch = new CountDownLatch(expected);
            this.throwOnEvent = throwOnEvent;
        }

        @Override
        public ListenerTier getTier() {
            return ListenerTier.NOTIFICATION;
        }

        @Override
        public synchronized void onEvent(AgentEvent event) {
            threads.add(Thread.currentThread().getName());
            latch.countDown();
            if (throwOnEvent) {
                throw new RuntimeException("模拟宿主外推失败");
            }
        }

        private synchronized List<String> captured() {
            return new ArrayList<>(threads);
        }
    }

    private static AgentEvent streamEvent(AgentEventType type) {
        return AgentEvent.builder()
                .type(type)
                .payload(new StreamChunkPayload(1L, "内容片段"))
                .build();
    }

    @Test
    @DisplayName("极速模式：网络型流式事件异步投递车道，不在调用线程执行")
    void fastModeDispatchesNetworkStreamAsync() throws Exception {
        ThreadCapturingListener listener = new ThreadCapturingListener(3, false);
        AgentEventDispatcher dispatcher = new AgentEventDispatcher(
                List.of(listener), new EngineInfra(EngineStubs.engineLock(), EngineStubs.engineExecutor()),
                new NotifyExecutor(2), true);

        String callerThread = Thread.currentThread().getName();
        dispatcher.dispatch(1L, streamEvent(AgentEventType.AGENT_CONTENT_STREAM));
        dispatcher.dispatch(1L, streamEvent(AgentEventType.AGENT_THINKING_STREAM));
        dispatcher.dispatch(1L, streamEvent(AgentEventType.TOOL_LOG_STREAM));

        assertTrue(listener.latch.await(5, TimeUnit.SECONDS), "任务应在超时内被消费");
        for (String thread : listener.captured()) {
            assertTrue(thread.startsWith("notify-lane-"),
                    "极速模式下应在通知车道执行，实际线程: " + thread + "（调用线程为 " + callerThread + "）");
        }
    }

    @Test
    @DisplayName("非极速模式：网络型流式事件由调用线程同步直调（背压通道）")
    void nonFastModeDispatchesNetworkStreamSynchronously() throws Exception {
        ThreadCapturingListener listener = new ThreadCapturingListener(1, false);
        AgentEventDispatcher dispatcher = new AgentEventDispatcher(
                List.of(listener), new EngineInfra(EngineStubs.engineLock(), EngineStubs.engineExecutor()),
                new NotifyExecutor(2), false);

        String callerThread = Thread.currentThread().getName();
        dispatcher.dispatch(1L, streamEvent(AgentEventType.AGENT_CONTENT_STREAM));

        // 同步直调：dispatch 返回时必然已执行完（无需等待）
        assertEquals(1, listener.captured().size(), "非极速模式下应在 dispatch 返回前完成消费");
        assertEquals(callerThread, listener.captured().getFirst(),
                "非极速模式下应由调用线程执行（背压语义）");
    }

    @Test
    @DisplayName("非极速模式：工具日志流不受背压通道影响，仍走车道异步投递")
    void nonFastModeKeepsToolLogAsync() throws Exception {
        ThreadCapturingListener listener = new ThreadCapturingListener(1, false);
        AgentEventDispatcher dispatcher = new AgentEventDispatcher(
                List.of(listener), new EngineInfra(EngineStubs.engineLock(), EngineStubs.engineExecutor()),
                new NotifyExecutor(2), false);

        dispatcher.dispatch(1L, streamEvent(AgentEventType.TOOL_LOG_STREAM));

        assertTrue(listener.latch.await(5, TimeUnit.SECONDS), "任务应在超时内被消费");
        assertTrue(listener.captured().getFirst().startsWith("notify-lane-"),
                "工具日志流始终异步（同步会挂起子进程 stdout），实际: " + listener.captured().getFirst());
    }

    @Test
    @DisplayName("网络型流式事件：单个监听器异常被隔离，不影响同层其他监听器")
    void networkStreamListenerFailureIsIsolated() {
        ThreadCapturingListener failing = new ThreadCapturingListener(1, true);
        AtomicInteger secondCalled = new AtomicInteger();
        AgentEventListener second = new AgentEventListener() {
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
                secondCalled.incrementAndGet();
            }
        };
        AgentEventDispatcher dispatcher = new AgentEventDispatcher(
                List.of(failing, second), new EngineInfra(EngineStubs.engineLock(), EngineStubs.engineExecutor()),
                new NotifyExecutor(2), false);

        // 前者抛异常不应中断分发，后者必须照常执行
        dispatcher.dispatch(1L, streamEvent(AgentEventType.AGENT_CONTENT_STREAM));

        assertEquals(1, secondCalled.get(), "前一监听器异常不得阻断同层后续监听器");
    }

    @Test
    @DisplayName("不同会话的流式事件在极速模式下可并行消化（分属不同车道）")
    void fastModeAllowsCrossConversationParallelism() throws Exception {
        int laneCount = 3;
        AgentEventDispatcher dispatcher = new AgentEventDispatcher(
                List.of(), new EngineInfra(EngineStubs.engineLock(), EngineStubs.engineExecutor()),
                new NotifyExecutor(laneCount + 2), true);
        // 仅验证不抛异常且调用方不被阻塞（车道数远大于事件数）
        for (long cid = 1; cid <= 10; cid++) {
            dispatcher.dispatch(cid, streamEvent(AgentEventType.AGENT_CONTENT_STREAM));
        }
    }
}
