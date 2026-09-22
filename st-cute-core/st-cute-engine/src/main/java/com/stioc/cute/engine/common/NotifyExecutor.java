package com.stioc.cute.engine.common;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;

/**
 * 通知层按会话保序的并行执行器（第三层事件投递目的地）。
 * <p>
 * 顺序与并发的解耦：第三层事件只要求「同一会话内严格有序」，会话之间本无因果依赖，
 * 故不应由单线程兼顾两者。本执行器提供 {@code laneCount} 条互相独立的串行车道，
 * 按会话 ID 稳定散列到固定车道——同会话恒落同车道（天然保持全序），不同会话并行推进。
 * </p>
 * <p>
 * <b>为什么是「独立单线程车道」而非「共享队列 + 多工作线程」</b>：后者会让同一会话的
 * 相邻事件被不同工作线程并行消费，顺序保障即刻失效。车道必须是各自持有单线程与独立队列的
 * 完整串行单元，这是「打散」与「保序」得以兼得的唯一形态。
 * </p>
 * <p>
 * <b>车道散列使用「每会话稳定的扰动」而非随机数</b>：随机散列会让同一会话的连续事件落到
 * 不同车道，导致消息状态类事件乱序（如终态先于运行态被前端消费）。扰动的作用仅为改善
 * 纯取模在会话 ID 密集时的分布均匀性，不同会话碰撞到同一车道只损失并行度、不破坏正确性。
 * </p>
 * <p>
 * <b>有界队列 + 调用线程执行</b>：每车道队列容量有限，积压超限时由提交线程自行执行，形成
 * 天然背压而非无限堆积内存。此降级路径意味着提交线程（如命令输出读取线程）可能被下游消费
 * 阻塞，属系统过载时的预期劣化，优于 OOM。
 * </p>
 * <p>
 * 本执行器随 {@code AgentEngine} 装配而创建，线程为守护线程、生命周期与 JVM 同体，
 * 无需显式关闭；车道数由构造方决定，默认见 {@link #DEFAULT_LANE_COUNT}。
 * </p>
 */
@Slf4j
public class NotifyExecutor {

    /**
     * 默认车道数：8 条并行车道，兼顾多会话并行度与线程开销
     */
    public static final int DEFAULT_LANE_COUNT = 8;

    /**
     * 单条车道的队列容量上限。
     * <p>
     * 容量口径为「每车道」而非总量：车道间互相隔离，单车道过载只背压自身生产者，
     * 不影响其他车道。按单个流式事件约 0.5KB 量级估算，每车道积压上限约 2.5MB，
     * 全量车道合计约 20MB，属内存安全区间。
     * </p>
     * <p>
     * 容量取值与服务形态相关：网络通知事件极速模式下，高频流式 chunk 也走本队列，
     * 故容量取得比纯控制类事件场景更宽裕，让突发流式产出在队列内平滑消化，而不是
     * 频繁触发调用线程执行（那会退化为对循环线程的阻塞）。
     * </p>
     */
    public static final int LANE_QUEUE_CAPACITY = 5120;

    /**
     * 每条车道的串行执行器（内部单线程 + 独立有界队列）。
     * <p>
     * 声明为接口类型而非 {@code ThreadPoolExecutor}：车道是随引擎装配创建、与服务同生命周期
     * 存在的长驻执行器（线程均为守护线程，随 JVM 退出而终止），不是块级作用域内需要释放的资源，
     * 刻意不暴露 {@code AutoCloseable} 契合同样是表达这一点。
     * </p>
     */
    private final ExecutorService[] lanes;

    public NotifyExecutor(int laneCount) {
        int count = laneCount > 0 ? laneCount : DEFAULT_LANE_COUNT;
        this.lanes = new ExecutorService[count];
        for (int i = 0; i < count; i++) {
            this.lanes[i] = createLane(i);
        }
    }

    /**
     * 使用默认车道数构造
     */
    public NotifyExecutor() {
        this(DEFAULT_LANE_COUNT);
    }

    /**
     * 创建单条车道：单线程 + 有界队列 + 调用线程执行策略。
     * <p>
     * 收敛为私有工厂方法并返回接口类型，使「线程池构造」不落在调用方的块级作用域中——
     * 长驻守护线程池并非 try-with-resources 语义下的短生命周期资源。
     * </p>
     *
     * @param laneIndex 车道序号（用于线程命名，便于诊断）
     * @return 该车道的串行执行器
     */
    private static ExecutorService createLane(int laneIndex) {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "notify-lane-" + laneIndex);
            thread.setDaemon(true);
            return thread;
        };
        // 队列满时由提交线程自行执行：形成天然背压，杜绝无界堆积
        RejectedExecutionHandler rejectedHandler = new ThreadPoolExecutor.CallerRunsPolicy();
        return new ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(LANE_QUEUE_CAPACITY),
                threadFactory,
                rejectedHandler);
    }

    /**
     * 提交通知任务至该会话所属车道执行。
     * <p>同一会话的任务恒落同一条车道，按提交顺序串行消费；不同会话并行推进。</p>
     *
     * @param cid  归属会话 ID（null 或 0 视为无归属，固定落 0 号车道以保证其内部有序）
     * @param task 待执行的通知任务
     */
    public void execute(Long cid, Runnable task) {
        if (task == null) {
            return;
        }
        lanes[laneIndexOf(cid)].execute(task);
    }

    /**
     * 计算会话所属车道下标：先经乘法混合扰动打散，再对车道数取模。
     * <p>
     * 乘法混合（经典 32 位散列终态函数）让连续或低位规律的会话 ID 在取模后分布更均匀，
     * 避免全部挤入相邻车道；对同一 cid 恒定输出同一结果，是保序的前提。
     * </p>
     */
    private int laneIndexOf(Long cid) {
        long key = cid != null ? cid : 0L;
        // 32 位乘法混合扰动：稳定、无状态、对同输入恒定
        int hash = Long.hashCode(key);
        hash ^= (hash >>> 16);
        hash *= 0x7feb352d;
        hash ^= (hash >>> 15);
        return Math.floorMod(hash, lanes.length);
    }

    /**
     * 当前车道数（供测试与诊断使用）
     */
    public int laneCount() {
        return lanes.length;
    }
}
