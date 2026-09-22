package com.stioc.cute.engine.assembly;

import com.stioc.cute.engine.common.EngineExecutor;
import com.stioc.cute.engine.common.EngineLock;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 引擎技术设施装配壳：锁与异步执行器的运输聚合，压缩组件构造参数的传递体积。
 * <p>
 * 两者均为必需注入物：引擎不自建线程与锁，线程模型与锁实现（条带锁/分布式锁等）
 * 的决策权在宿主。装配壳为纯 getter 数据容器（铁律见 AgentEngineBuilder 类头），
 * 通知执行器不在此壳内——其车道数属调优配置，经 {@link EngineOptions} 下发。
 * </p>
 */
@Getter
@RequiredArgsConstructor
public class EngineInfra {

    /**
     * 引擎锁供血：全部并发临界区的锁来源（会话数据锁、循环锁、命名锁、写工具锁）
     */
    private final EngineLock locks;

    /**
     * 引擎异步执行器供血：循环拉起、子智能体执行、工具批执行等异步任务的线程来源
     */
    private final EngineExecutor executor;
}
