package com.stioc.cute.platform.common;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 公共线程工具类，提供全局共享的虚拟线程执行器
 */
public class CommonThread {

    private static final ExecutorService VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

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
}
