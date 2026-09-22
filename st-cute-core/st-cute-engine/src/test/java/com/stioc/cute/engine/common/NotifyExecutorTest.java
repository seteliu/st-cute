package com.stioc.cute.engine.common;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NotifyExecutor} 按会话保序并行执行器单元测试。
 * <p>
 * 钉住三条不变量：同会话投递严格保序、跨会话可并行推进、车道散列对同一会话恒定。
 * 另覆盖队列满时「调用线程自行执行」的背压降级路径。
 * </p>
 */
class NotifyExecutorTest {

    /**
     * 同会话的连续任务必须严格按提交顺序串行执行（保序是第三层事件的核心不变量）
     */
    @Test
    void preservesOrderWithinSameConversation() {
        NotifyExecutor executor = new NotifyExecutor(4);
        List<Integer> order = Collections.synchronizedList(new CopyOnWriteArrayList<>());
        int taskCount = 200;

        CountDownLatch done = new CountDownLatch(taskCount);
        for (int i = 0; i < taskCount; i++) {
            final int seq = i;
            executor.execute(1001L, () -> {
                order.add(seq);
                done.countDown();
            });
        }

        awaitQuietly(done);
        assertEquals(taskCount, order.size(), "全部任务应被执行");
        for (int i = 0; i < taskCount; i++) {
            assertEquals(i, order.get(i), "同会话任务必须严格保序，实际第 " + i + " 位为 " + order.get(i));
        }
    }

    /**
     * 不同会话的任务应能并行推进（避免全局串行这一原始瓶颈）
     */
    @Test
    void runsDifferentConversationsInParallel() throws Exception {
        int laneCount = 4;
        NotifyExecutor executor = new NotifyExecutor(laneCount);
        // 选 4 个确定落入不同车道的会话 ID，并让每个任务阻塞直至全部就绪
        List<Long> cids = distinctLaneConversations(executor, laneCount);
        assertEquals(laneCount, cids.size(), "应能取到与车道数相同的互不碰撞会话");

        CountDownLatch allEntered = new CountDownLatch(laneCount);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrentPeak = new AtomicInteger();
        AtomicInteger running = new AtomicInteger();

        for (Long cid : cids) {
            executor.execute(cid, () -> {
                concurrentPeak.accumulateAndGet(running.incrementAndGet(), Math::max);
                allEntered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    running.decrementAndGet();
                }
            });
        }

        // 若为全局串行，allEntered 永远不会归零
        boolean entered = allEntered.await(5, TimeUnit.SECONDS);
        release.countDown();
        assertTrue(entered, "不同会话应能同时进入执行（否则仍是全局串行）");
        assertEquals(laneCount, concurrentPeak.get(), "并发峰值应达到车道数");
    }

    /**
     * 同一会话多次散列必须恒定落同一条车道（保序的前提）
     */
    @Test
    void laneIndexIsStableForSameConversation() {
        NotifyExecutor executor = new NotifyExecutor(8);
        for (long cid = 1; cid <= 50; cid++) {
            List<String> threads = Collections.synchronizedList(new CopyOnWriteArrayList<>());
            int rounds = 20;
            CountDownLatch done = new CountDownLatch(rounds);
            for (int i = 0; i < rounds; i++) {
                executor.execute(cid, () -> {
                    threads.add(Thread.currentThread().getName());
                    done.countDown();
                });
            }
            awaitQuietly(done);
            long distinct = threads.stream().distinct().count();
            assertEquals(1, distinct, "同一会话 cid=" + cid + " 的多次投递必须恒落同一车道，实际：" + threads);
        }
    }

    /**
     * 无归属（null）会话固定落同一车道，保证其内部有序
     */
    @Test
    void nullConversationFallsIntoSingleStableLane() {
        NotifyExecutor executor = new NotifyExecutor(8);
        List<String> threads = Collections.synchronizedList(new CopyOnWriteArrayList<>());
        int rounds = 30;
        CountDownLatch done = new CountDownLatch(rounds);
        for (int i = 0; i < rounds; i++) {
            executor.execute(null, () -> {
                threads.add(Thread.currentThread().getName());
                done.countDown();
            });
        }
        awaitQuietly(done);
        assertEquals(1, threads.stream().distinct().count(), "null 会话应固定落单一车道");
    }

    /**
     * 队列积压超限时，任务由提交线程自行执行：不得丢任务、不得抛异常
     */
    @Test
    void backsPressureByRunningOnCallerWhenQueueSaturated() throws Exception {
        NotifyExecutor executor = new NotifyExecutor(1);
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);

        // 先用一个长任务占住唯一车道
        executor.execute(7L, () -> {
            started.countDown();
            try {
                blocker.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS), "占用任务应已开始");

        // 灌满队列 + 触发溢出，全部任务都必须被执行（CallerRunsPolicy 兜底）
        int submitCount = NotifyExecutor.LANE_QUEUE_CAPACITY + 50;
        AtomicInteger executed = new AtomicInteger();
        for (int i = 0; i < submitCount; i++) {
            executor.execute(7L, executed::incrementAndGet);
        }
        blocker.countDown();

        // 溢出部分由提交线程当场执行，故此处应已有可观进度
        assertTrue(executed.get() >= submitCount - NotifyExecutor.LANE_QUEUE_CAPACITY - 1,
                "队列满时溢出的任务应由提交线程执行，实际已执行 " + executed.get());
    }

    /**
     * 构造非法车道数时应回退默认值，避免装配出不可用执行器
     */
    @Test
    void fallsBackToDefaultLaneCountOnInvalidInput() {
        assertEquals(NotifyExecutor.DEFAULT_LANE_COUNT, new NotifyExecutor(0).laneCount());
        assertEquals(NotifyExecutor.DEFAULT_LANE_COUNT, new NotifyExecutor(-3).laneCount());
        assertEquals(3, new NotifyExecutor(3).laneCount());
    }

    // ──────────────────────────────────────────────
    // 辅助
    // ──────────────────────────────────────────────

    /**
     * 挑选出落入不同车道的会话 ID（通过观察任务实际执行的线程名反推车道）
     */
    private List<Long> distinctLaneConversations(NotifyExecutor executor, int wanted) {
        List<Long> picked = new CopyOnWriteArrayList<>();
        List<String> usedLanes = new CopyOnWriteArrayList<>();
        for (long cid = 1; cid <= 1000 && picked.size() < wanted; cid++) {
            CountDownLatch done = new CountDownLatch(1);
            List<String> threadName = new CopyOnWriteArrayList<>();
            long probe = cid;
            executor.execute(probe, () -> {
                threadName.add(Thread.currentThread().getName());
                done.countDown();
            });
            awaitQuietly(done);
            if (!threadName.isEmpty() && !usedLanes.contains(threadName.get(0))) {
                usedLanes.add(threadName.get(0));
                picked.add(probe);
            }
        }
        return picked;
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "任务未在超时内全部完成");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
