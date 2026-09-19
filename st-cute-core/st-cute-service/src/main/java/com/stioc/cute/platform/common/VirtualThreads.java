package com.stioc.cute.platform.common;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 平台通用虚拟线程门面工具。
 * <p>
 * 核心设计原则：
 * <ul>
 *   <li><b>不池化</b>：遵循 JEP 444 原则，虚拟线程按需创建、用完即毁，绝不复用实例。</li>
 *   <li><b>可观测</b>：默认带命名前缀与自增序号（如 {@code st-cute-vt-1}）或支持动态传入业务语义名（如 {@code cmd-stdout-toolCallId}）。</li>
 *   <li><b>防吞异常</b>：统一兜底未捕获异常日志，杜绝异步任务静默死掉无感知。</li>
 *   <li><b>受管闭环</b>：全局执行器支持有界优雅停机，杜绝 JDK 默认无限期挂死。</li>
 * </ul>
 * </p>
 */
@Slf4j
public final class VirtualThreads {

    private static final String DEFAULT_PREFIX = "st-cute-vt-";

    /**
     * 无名任务线程名自增序列号，增强可观测性
     */
    private static final AtomicLong SEQUENCE = new AtomicLong(1);

    /**
     * 全局默认未捕获异常处理器
     */
    private static final Thread.UncaughtExceptionHandler LOGGING_HANDLER = (thread, throwable) -> {
        log.error("[VirtualThread] 异步任务执行发生未捕获异常, 线程名={}", thread.getName(), throwable);
    };

    /**
     * 停机状态受管标志：容器关闭时置为 true，杜绝关机期间自愈重建产生孤儿执行器
     */
    private static volatile boolean stopping = false;

    /**
     * 全局共享的 Per-Task 虚拟线程执行器（任务即新建，用完即毁，非池化复用）
     */
    private static volatile ExecutorService globalExecutor = createGlobalExecutor();

    private VirtualThreads() {
    }

    private static ExecutorService createGlobalExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name(DEFAULT_PREFIX, 0)
                        .uncaughtExceptionHandler(LOGGING_HANDLER)
                        .factory()
        );
    }

    /**
     * 获取全局共享的虚拟线程执行器（任务即新建；若之前非停机原因被关闭则安全自愈重建）
     */
    public static ExecutorService getGlobalExecutor() {
        if (stopping) {
            throw new IllegalStateException("平台全局虚拟线程执行器已处于停机状态，拒绝获取或重建");
        }
        ExecutorService executor = globalExecutor;
        if (executor == null || executor.isShutdown()) {
            synchronized (VirtualThreads.class) {
                if (stopping) {
                    throw new IllegalStateException("平台全局虚拟线程执行器已处于停机状态，拒绝获取或重建");
                }
                if (globalExecutor == null || globalExecutor.isShutdown()) {
                    globalExecutor = createGlobalExecutor();
                }
                executor = globalExecutor;
            }
        }
        return executor;
    }

    /**
     * 创建基于指定命名前缀的专属虚拟线程工厂
     *
     * @param prefix 线程名前缀（如 "mcp-client-"）
     * @return 线程工厂
     */
    public static ThreadFactory factory(String prefix) {
        return Thread.ofVirtual()
                .name(prefix, 1)
                .uncaughtExceptionHandler(LOGGING_HANDLER)
                .factory();
    }

    /**
     * 创建基于指定命名前缀的专属虚拟线程执行器（任务即新建，用完即毁）
     *
     * @param prefix 线程名前缀
     * @return 独立的 ExecutorService
     */
    public static ExecutorService newExecutor(String prefix) {
        return Executors.newThreadPerTaskExecutor(factory(prefix));
    }

    /**
     * 以默认前缀与自增序号启动一个虚拟线程执行任务（增强日志与 dump 可观测性）
     *
     * @param task 待执行任务
     * @return 启动的线程实例
     */
    public static Thread run(Runnable task) {
        if (stopping) {
            throw new RejectedExecutionException("平台全局虚拟线程执行器已处于停机状态，拒绝启动新虚拟线程");
        }
        return Thread.ofVirtual()
                .name(DEFAULT_PREFIX + SEQUENCE.getAndIncrement())
                .uncaughtExceptionHandler(LOGGING_HANDLER)
                .start(task);
    }

    /**
     * 以指定的固定/动态业务线程名启动一个虚拟线程执行任务
     *
     * @param name 完整的线程名（如 "cmd-watchdog-123"）
     * @param task 待执行任务
     * @return 启动的线程实例
     */
    public static Thread run(String name, Runnable task) {
        if (stopping) {
            throw new RejectedExecutionException("平台全局虚拟线程执行器已处于停机状态，拒绝启动新虚拟线程");
        }
        return Thread.ofVirtual()
                .name(name)
                .uncaughtExceptionHandler(LOGGING_HANDLER)
                .start(task);
    }

    /**
     * 提交异步任务到全局虚拟线程执行器中
     *
     * @param task 待执行任务
     * @return Future 句柄
     */
    public static Future<?> submit(Runnable task) {
        if (stopping) {
            throw new RejectedExecutionException("平台全局虚拟线程执行器已处于停机状态，拒绝提交新任务");
        }
        return getGlobalExecutor().submit(task);
    }

    /**
     * 提交带返回值的异步任务到全局虚拟线程执行器中
     *
     * @param task 待执行任务
     * @param <T>  返回值类型
     * @return Future 句柄
     */
    public static <T> Future<T> submit(Callable<T> task) {
        if (stopping) {
            throw new RejectedExecutionException("平台全局虚拟线程执行器已处于停机状态，拒绝提交新任务");
        }
        return getGlobalExecutor().submit(task);
    }

    /**
     * 全局虚拟线程执行器有界安全停机：有界等待（默认 3 秒），超时强制 shutdownNow，杜绝无限期阻塞关停流程。
     *
     * @param timeoutMs 停机等待超时时间（毫秒）
     */
    public static synchronized void shutdownGracefully(long timeoutMs) {
        stopping = true;
        ExecutorService executor = globalExecutor;
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                    log.warn("[VirtualThread] 全局执行器在 {}ms 内未完全终止，触发强制关闭", timeoutMs);
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    /**
     * 重置停机标记并自愈重建全局执行器（仅供测试或框架容器刷新重载时使用）
     */
    public static synchronized void resetState() {
        stopping = false;
        if (globalExecutor == null || globalExecutor.isShutdown()) {
            globalExecutor = createGlobalExecutor();
        }
    }
}
