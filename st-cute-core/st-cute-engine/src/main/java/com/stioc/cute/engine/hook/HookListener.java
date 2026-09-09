package com.stioc.cute.engine.hook;

import com.stioc.cute.engine.loop.core.AgentContext;

/**
 * 引擎生命周期挂点监听器（宿主实现）。
 * <p>
 * 引擎在 ReAct 循环与工具执行的关键节点经 {@link AgentContext#triggerHook}
 * 在 cid 数据锁内同步直调本接口。实现抛出异常即构成阻断语义：
 * 工具调用前抛出则拦截该工具执行，工具完成后抛出则改写工具结果为失败。
 * </p>
 */
public interface HookListener {

    /**
     * 响应引擎生命周期挂点。
     *
     * @param type    挂点类型
     * @param payload 强类型载荷（生命周期挂点为 null）
     * @param context 当前会话上下文
     * @throws Exception 宿主侧 Hook 校验失败时抛出，引擎捕获后执行阻断
     */
    void onHook(HookType type, HookPayload payload, AgentContext context) throws Exception;
}
