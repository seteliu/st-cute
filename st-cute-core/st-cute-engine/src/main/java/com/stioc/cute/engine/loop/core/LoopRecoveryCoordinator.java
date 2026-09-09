package com.stioc.cute.engine.loop.core;

import com.stioc.cute.engine.common.AgentEngineLock;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationQuery;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.store.types.SortDirection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;

/**
 * 循环自愈协调器：负责系统重启自愈与定时扫描清理僵死消息及挂死会话。
 * <p>
 * 内聚原宿主 StaleMessageCleanupJob 中的核心自愈状态机流转与锁临界区处理，
 * 彻底消除外部对引擎分段锁的引用与底层状态机外溢。
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class LoopRecoveryCoordinator {

    private static final Set<MessageStatus> STALE_STATUSES =
            EnumSet.of(MessageStatus.PENDING, MessageStatus.RUNNING, MessageStatus.WAITING_APPROVAL);

    private final ConversationStore conversationStore;
    private final MessageStore messageStore;
    private final LoopDataReporter loopDataReporter;
    private final AgentContextManager agentContextManager;
    private final AgentLoopCoordinator loopCoordinator;

    /**
     * 清理并自愈僵死消息与会话状态
     *
     * @param createdBefore 超时截止时间点（传 null 则为启动全量自愈扫描并重置 loopRunning）
     * @return 修复的消息总数
     */
    public int cleanStale(LocalDateTime createdBefore) {
        boolean isReset = (createdBefore == null);
        if (isReset) {
            conversationStore.updateByQuery(
                    Conversation.builder().loopRunning(0).build(),
                    ConversationQuery.builder().loopRunning(1).build()
            );
        }

        List<Message> staleMessages = messageStore.listByQuery(MessageQuery.builder()
                .statuses(STALE_STATUSES)
                .createTimeBefore(createdBefore)
                .sortDirection(SortDirection.ASC)
                .build());
        if (staleMessages.isEmpty()) {
            return 0;
        }

        String defaultReason = isReset ? "系统重置，执行被迫终止" : "执行超时，已被系统强行终止";
        Set<Long> affectedCids = new HashSet<>();

        for (Message msg : staleMessages) {
            if (!isReset && msg.getStatus() == MessageStatus.WAITING_APPROVAL) {
                msg.setStatus(MessageStatus.CANCELED);
                if (msg.getRole() == MessageRole.TOOL) {
                    msg.setContent("{\"error\": \"审批超时，已被系统自动取消。\"}");
                }
            } else {
                msg.setStatus(MessageStatus.FAILED);
                if (msg.getRole() == MessageRole.TOOL) {
                    msg.setContent("{\"error\": \"" + defaultReason + "。\"}");
                } else if (msg.getRole() == MessageRole.ASSISTANT && (msg.getContent() == null || msg.getContent().isBlank())) {
                    msg.setContent("[错误] " + defaultReason + "。");
                }
            }
            if (msg.getCid() != null) {
                affectedCids.add(msg.getCid());
            }
        }

        for (Message msg : staleMessages) {
            messageStore.updateById(msg);
        }

        for (Long cid : affectedCids) {
            handleAffectedConversation(cid, defaultReason);
        }

        return staleMessages.size();
    }

    /**
     * 处理受影响的会话，清理其 Loop 运行态字段并回收内存上下文。
     * 若该会话是子智能体，则自动向其父智能体发送挂掉的工作报告，避免父会话被卡死。
     */
    private void handleAffectedConversation(Long cid, String reason) {
        Conversation conv = conversationStore.getById(cid);
        if (conv == null) {
            return;
        }

        Long parentCid = conv.getParentCid();
        if (parentCid != null && parentCid != 0L) {
            Lock subDataLock = AgentEngineLock.CID_DATA_STRIPED.get(cid);
            Lock parentDataLock = AgentEngineLock.CID_DATA_STRIPED.get(parentCid);
            subDataLock.lock();
            parentDataLock.lock();
            try {
                log.warn("[LoopRecovery] 检测到子会话 {} 异常中断，开始向父会话 {} 汇报", cid, parentCid);
                AgentContext parentContext = agentContextManager.getOrCreateContext(parentCid);
                boolean allDone = loopDataReporter.updateWaitingSubAgentToCompleted(parentContext, cid, reason);
                if (allDone) {
                    log.debug("[LoopRecovery] 所有子智能体已完成/超时清理，异步拉起父智能体: parentCid={}", parentCid);
                    loopCoordinator.executeLoopAsync(parentCid);
                }

                loopCoordinator.forceResetLoopState(cid);
                agentContextManager.removeContext(cid);
            } finally {
                parentDataLock.unlock();
                subDataLock.unlock();
            }
        } else {
            log.debug("[LoopRecovery] 清理顶层会话 {} 异常残留状态", cid);
            Lock dataLock = AgentEngineLock.CID_DATA_STRIPED.get(cid);
            dataLock.lock();
            try {
                loopCoordinator.forceResetLoopState(cid);
                agentContextManager.removeContext(cid);
            } finally {
                dataLock.unlock();
            }
        }
    }
}
