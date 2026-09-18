package com.stioc.cute.runtime.common;

import com.stioc.cute.engine.common.EngineExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 宿主异步执行器供血实现：虚拟线程执行器（单机部署形态）。
 * <p>
 * 引擎线程模型已下放宿主（见 EngineExecutor），本实现按需选择虚拟线程池，
 * 承载 ReAct 循环拉起、子智能体执行、只读工具并发批等阻塞性长任务。
 * 云化宿主可替换为受管线程池实现。另暴露静态 submit 便捷方法供宿主自身异步任务使用，
 * 与引擎共用同一执行器（原引擎 AgentEngineCommonThread#submit 的宿主落点）。
 * </p>
 */
@Component
public class HostExecutorProvider implements EngineExecutor {

    /**
     * 全局共享的虚拟线程执行器
     */
    private static final ExecutorService ASYNC_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public ExecutorService getAsyncExecutor() {
        return ASYNC_EXECUTOR;
    }

    /**
     * 提交异步任务到宿主共享虚拟线程执行器中执行
     */
    public static void submit(Runnable task) {
        ASYNC_EXECUTOR.submit(task);
    }
}
