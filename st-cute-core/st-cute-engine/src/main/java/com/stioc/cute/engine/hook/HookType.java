package com.stioc.cute.engine.hook;

/**
 * 引擎生命周期挂点类型。
 * <p>
 * Hook 是引擎对外暴露的同步回调扩展点（拦截/校验），区别于 event 包的
 * 广播事实事件：挂点在 cid 数据锁内同步直调 {@link HookListener}，
 * 宿主实现抛出异常即构成阻断语义（工具拦截/结果改写）。
 * </p>
 */
public enum HookType {

    /**
     * 上下文启动挂点（单次 ReAct executeLoop 开始）
     */
    ON_CONTEXT_START,

    /**
     * 单轮推理步骤开始挂点（每轮 LLM 调用前）
     */
    ON_LOOP_START,

    /**
     * 单次 executeLoop 收尾挂点（含异常路径，finally 中触发）
     */
    ON_LOOP_END,

    /**
     * 工具调用前置挂点（阻断型：宿主监听器抛异常则引擎拦截该工具执行）
     */
    ON_TOOL_CALL,

    /**
     * 工具执行成功后置挂点（阻断型：宿主监听器抛异常则引擎改写工具结果为失败）
     */
    ON_TOOL_COMPLETE
}
