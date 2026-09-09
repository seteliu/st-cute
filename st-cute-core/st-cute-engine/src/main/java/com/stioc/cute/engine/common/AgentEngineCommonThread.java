package com.stioc.cute.engine.common;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 引擎公共线程工具类，提供全局共享的虚拟线程执行器。
 * <p>自 platform/common/CommonThread 随行迁入引擎，逻辑等价，旧引用统一改指本类。</p>
 */
public class AgentEngineCommonThread {
    private static final ExecutorService VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 通知层事件串行推送执行器：全局单线程，入队顺序即执行顺序。
     * 专供 WS 推送等对顺序敏感的异步任务使用，消除流式 chunk 并发推送导致的偶发乱序。
     */
    private static final ExecutorService NOTIFY_THREAD_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "notify-serial");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 获取全局共享的虚拟线程执行器
     */
    public static ExecutorService getVirtualThreadExecutor() {
        return VIRTUAL_THREAD_EXECUTOR;
    }

    /**
     * 提交异步任务到虚拟线程执行器中执行
     */
    public static void submit(Runnable task) {
        VIRTUAL_THREAD_EXECUTOR.submit(task);
    }

    /**
     * 提交顺序敏感的异步任务到通知层串行执行器中执行
     */
    public static void submitNotify(Runnable task) {
        NOTIFY_THREAD_EXECUTOR.execute(task);
    }
}
