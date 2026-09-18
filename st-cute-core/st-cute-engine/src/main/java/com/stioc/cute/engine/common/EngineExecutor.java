package com.stioc.cute.engine.common;

import java.util.concurrent.ExecutorService;

/**
 * 引擎异步执行器供血接口：引擎自身的全部异步任务执行器由宿主注入，引擎不再自建线程。
 * <p>
 * 线程模型的决策权下放宿主后，引擎源码不再依赖特定 JDK 版本的线程设施（如虚拟线程）；
 * 单机宿主可注入虚拟线程执行器，受限运行环境宿主可注入平台线程池实现。
 * </p>
 */
public interface EngineExecutor {

    /**
     * 获取通用异步任务执行器：承载 ReAct 循环拉起、子智能体执行、只读工具并发批、
     * 审批恢复执行与工具批量异步执行等任务。
     * <p>
     * 契约：引擎不假设线程类型（虚拟/平台线程均可），仅要求
     * <ul>
     *   <li>能承载阻塞性长任务（单任务分钟级：LLM 推理 + 工具执行）；</li>
     *   <li>并发容量可观（多会话并行 + 单会话内多子智能体/只读工具并发）；</li>
     *   <li>宿主与其他异步任务共用同一执行器时，由宿主自行保障隔离与配额。</li>
     * </ul>
     * </p>
     *
     * @return 通用异步任务执行器
     */
    ExecutorService getAsyncExecutor();
}
