package com.stioc.cute.engine.common;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 引擎公共线程工具类：仅保留通知层全局串行队列。
 * <p>
 * 通用异步任务执行器已下放宿主（经 {@link EngineExecutor} 注入），
 * 引擎不再自建线程，线程模型（虚拟/平台线程）由宿主按部署形态决定；
 * 通知层串行队列是引擎自身顺序语义的一部分（保证会话流事件严格有序），故保留在引擎内固定实现。
 * </p>
 */
public class AgentEngineCommonThread {

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
     * 提交顺序敏感的异步任务到通知层串行执行器中执行
     */
    public static void submitNotify(Runnable task) {
        NOTIFY_THREAD_EXECUTOR.execute(task);
    }
}
