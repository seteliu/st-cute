package com.stioc.cute.agent.access;

/**
 * 智能体 Loop 调度与协调管理器接口
 */
public interface AgentLoopCoordinator {

    /**
     * 同步执行 ReAct 推理循环
     *
     * @param cid 会话 ID
     */
    void executeLoopSync(Long cid);

    /**
     * 异步提交执行 ReAct 推理循环
     *
     * @param cid 会话 ID
     */
    void executeLoopAsync(Long cid);

    /**
     * 异步提交执行 ReAct 推理循环，并于运行结束后执行回调函数
     *
     * @param cid 会话 ID
     * @param afterRun 回调函数
     */
    void executeLoopAsync(Long cid, Runnable afterRun);

    /**
     * 强制重置/停止指定会话的 Loop 运行状态
     *
     * @param cid 会话 ID
     */
    void forceResetLoopState(Long cid);

    /**
     * 工具进入终态后的回调通知
     *
     * @param context    当前智能体运行时上下文
     * @param toolCallId 工具调用 ID
     */
    void notifyToolCompleted(AgentContext context, String toolCallId);
}
