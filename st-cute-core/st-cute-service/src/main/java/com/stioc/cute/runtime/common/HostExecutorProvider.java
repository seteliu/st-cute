package com.stioc.cute.runtime.common;

import com.stioc.cute.engine.common.EngineExecutor;
import org.springframework.stereotype.Component;

import com.stioc.cute.platform.common.VirtualThreads;

import java.util.concurrent.ExecutorService;

/**
 * 宿主异步执行器供血实现：虚拟线程执行器（单机部署形态）。
 * <p>
 * 引擎线程模型已下放宿主（见 EngineExecutor），本实现按需选择虚拟线程池，
 * 承载 ReAct 循环拉起、子智能体执行、只读工具并发批等阻塞性长任务。
 * 云化宿主可替换为受管线程池实现。另暴露静态 submit 便捷方法供宿主自身异步任务使用，
 * 与引擎共用同一执行器。
 * </p>
 * <p>
 * 注意：引擎通知层（第三层事件投递）不走本执行器——其顺序语义属引擎自身契约，
 * 由引擎自建的按会话保序车道执行器承载，故不在本类的供血范围内。
 * </p>
 */
@Component
public class HostExecutorProvider implements EngineExecutor {

    @Override
    public ExecutorService getAsyncExecutor() {
        // 动态获取全局虚拟线程执行器，确保容器重载或刷新后能获取到自愈重建的健康实例
        return VirtualThreads.getGlobalExecutor();
    }

    /**
     * 提交异步任务到宿主共享虚拟线程执行器中执行
     */
    public static void submit(Runnable task) {
        VirtualThreads.getGlobalExecutor().submit(task);
    }
}
