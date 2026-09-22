package com.stioc.cute.engine.loop.types;

import com.stioc.cute.engine.store.types.Conversation;

/**
 * 子智能体的收场定性：决定父会话收到的《子 Agent 工作报告》以何种状态行呈现。
 * <p>
 * 「是否外部介入」与「原因文案」是两个独立维度，禁止用文案是否为空兼职表达定性——
 * 否则调用方无法从参数看出子会话会被定性为「已停止」，还是按其真实终态如实呈现。
 * </p>
 */
public enum SubAgentOutcome {

    /**
     * 自然收场：按子会话自身的终态如实呈现（已完成 / 失败 / 已停止 / 已中断）。
     */
    NATURAL,

    /**
     * 外部介入收场：子会话被强制停止（用户点停止、父会话级联停止、系统重置等），
     * 无论其消息停在哪个状态，报告一律以「已停止」定性。
     */
    INTERRUPTED;

    /**
     * 依子会话的库中运行态推导收场定性（定性推导的唯一事实源，收口动作必须经本方法判定）。
     * <p>
     * 传 null（会话行已被物理删除）视为 {@link #INTERRUPTED}：屏障里仍残留它的占位，
     * 恰恰说明它当时确实还在跑，只是记录已不存在。
     * </p>
     *
     * @param subConversation 子会话实体（可为 null）
     * @return 该子会话的收场定性
     */
    public static SubAgentOutcome of(Conversation subConversation) {
        if (subConversation == null) {
            return INTERRUPTED;
        }
        boolean running = subConversation.getLoopRunning() != null
                && subConversation.getLoopRunning() == 1;
        return running ? INTERRUPTED : NATURAL;
    }
}
