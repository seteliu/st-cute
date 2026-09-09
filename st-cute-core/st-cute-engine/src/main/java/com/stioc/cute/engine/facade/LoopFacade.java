package com.stioc.cute.engine.facade;

import com.stioc.cute.engine.loop.core.AgentLoopCoordinator;
import com.stioc.cute.engine.loop.core.LoopRecoveryCoordinator;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ReAct 循环门面：用户消息提交、循环拉起/重试、停止与系统自愈能力（极薄门面，全量一句话转发）。
 */
@RequiredArgsConstructor
public class LoopFacade {

    private final AgentLoopCoordinator loopCoordinator;
    private final LoopRecoveryCoordinator loopRecoveryCoordinator;

    /**
     * 提交用户新消息并异步启动推理循环
     */
    public void submitUserMessage(Long cid, String text, String attachments) {
        loopCoordinator.submitUserMessage(cid, text, attachments);
    }

    /**
     * 重试指定消息：若为 ASSISTANT 则重置为 PENDING 并启动循环；若为 USER 则回退至该节点重新启动循环
     */
    public void retryMessage(Long cid, Long messageId) {
        loopCoordinator.retryMessage(cid, messageId);
    }

    /**
     * 清理并自愈僵死消息与会话状态（createdBefore 为 null 时表示系统重置全量扫描，非 null 时表示定时超时扫描）
     *
     * @param createdBefore 超时截止时间点（传 null 则为全量自愈扫描并重置 loopRunning）
     * @return 修复的消息总数
     */
    public int cleanStale(LocalDateTime createdBefore) {
        return loopRecoveryCoordinator.cleanStale(createdBefore);
    }

    /**
     * 强制停止会话：重置循环状态快照 + 抢占清理未完成消息 + 中断活跃线程 + 强杀副作用
     */
    public void forceStopLoop(Long cid) {
        loopCoordinator.forceResetLoopState(cid);
    }
}
