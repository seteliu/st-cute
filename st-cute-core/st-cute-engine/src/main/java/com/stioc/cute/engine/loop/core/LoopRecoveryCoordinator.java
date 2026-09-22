package com.stioc.cute.engine.loop.core;

import com.stioc.cute.engine.common.EngineLock;
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
import org.apache.commons.lang3.StringUtils;

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
    private final EngineLock lockProvider;

    /**
     * 清理并自愈僵死消息与会话状态
     *
     * @param createdBefore 超时截止时间点（传 null 则为启动全量自愈扫描并重置 loopRunning）
     * @return 修复的消息总数
     */
    public int cleanStale(LocalDateTime createdBefore) {
        boolean isReset = (createdBefore == null);
        if (isReset) {
            // 循环账目对账与收口必须先于批量归零：对账需读子会话断电前的 loopRunning
            // 判别「跑完了只是父未唤醒」与「随进程一起中断」两种死法，顺序颠倒会让判别恒为中断。
            // 对账内部经 clearLoopState（事件链）同步清掉有屏障会话的账目与内存运行态；
            // 批量归零仅兜底覆盖「无屏障但 loopRunning=1」的会话（Store 直写不发事件，
            // 其内存运行态本就是干净的——启动后从未被触碰恢复出 loopRunning=true）
            reconcileAndResetLoopStateOnStartup();

            // 启动全量自愈：进程内已无任何执行体，loopRunning 一律是断电残留的假事实，全面归零
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
            // 启动自愈（isReset）阶段不唤醒父会话：重启后无人值守，宿主扩展资产与权限白名单可能尚未就绪，
            // 自动发起 LLM 调用既烧配额又可能造成错误配置下的副作用（汇报已作 BRANCH 落库，等用户下次交互消费）
            handleAffectedConversation(cid, defaultReason, !isReset);
        }

        return staleMessages.size();
    }

    /**
     * 启动时的循环账目对账与收口（先补报、后清账，顺序不可颠倒）。
     * <p>
     * <b>为何必须对账</b>：屏障是「本进程内仍有工具/子循环在跑」的内存事实，重启后该事实必然为假。
     * 若只清不报，子 Agent 的死讯与已有结论会随屏障一起被静默抹掉，用户在父会话里看不到任何交代；
     * 且父会话在派发子代理后自身消息全为终态，「按僵死消息筛选」的既有路径根本不会命中它，
     * 其残缺屏障会留到下次用户交互时把会话永久卡在「运行中」。
     * </p>
     * <p>
     * <b>清账必须走 {@link LoopDataReporter#clearLoopState}（事件链）而非 Store 直写</b>：
     * 对账过程会经 getOrCreateContext 把断电前的 loopRunning=1 恢复进内存，若只直写库、
     * 不经事件刷新内存，内存残留的 loopRunning=true 会让用户之后对该会话发送新消息时
     * 被「正在运行中」守卫永久拒绝。clearLoopState 经事件同步收口 DB 与内存两侧。
     * </p>
     * <p>
     * 单遍遍历即可覆盖全部现场：每个会话只需为「自己屏障里挂着的那些子会话」补一份工作报告，
     * 而子会话自身的屏障由遍历到它的父会话负责对账，无需递归。
     * 报告状态由 {@link LoopDataReporter#updateWaitingSubAgentToCompleted} 依子会话实际终态推导
     * （已完成 / 已停止 / 失败 / 已中断），本方法只提供「系统重置」这一外部介入原因。
     * </p>
     * <p>
     * <b>刻意不唤醒父会话</b>：重启后无人值守、宿主扩展资产（技能/规则/MCP）可能尚未就绪、
     * 权限白名单也未加载，此时自动发起 LLM 调用既烧配额又可能造成错误配置下的副作用。
     * 汇报会以 BRANCH 消息落库留痕，等用户下次交互时随历史一并进入父会话上下文。
     * </p>
     */
    private void reconcileAndResetLoopStateOnStartup() {
        List<Conversation> all;
        try {
            // 刻意不加查询条件：必须覆盖「loopRunning 已为 0 但屏障残留」的会话——
            // 父会话派发子代理后自身消息全为终态，它恰恰是 loopRunning=0 且屏障残留的最常见现场。
            // 重启期一次性全量扫描，会话量级有限，可接受。
            all = conversationStore.listByQuery(ConversationQuery.builder().build());
        } catch (Exception e) {
            log.error("[LoopRecovery] 启动对账查询会话列表失败，跳过本次对账（批量归零随后兜底）", e);
            return;
        }
        String interruptedReason = "系统重置：进程重启，该子智能体已随上次运行中断。";
        int reconciled = 0;

        for (Conversation conv : all) {
            Long cid = conv.getId();
            if (cid == null) {
                continue;
            }
            boolean hasSubs = StringUtils.isNotBlank(conv.getWaitingSubCids());
            boolean hasTools = StringUtils.isNotBlank(conv.getWaitingToolIds());
            if (!hasSubs && !hasTools) {
                continue;
            }

            // 会话级隔离：单会话对账失败绝不中断整体扫描，否则后续会话的屏障会残留成永久卡死
            try {
                AgentContext context = agentContextManager.getOrCreateContext(cid);

                // 1. 先补报：为每个仍占位的子会话交代死讯或结论（此时屏障尚未清空，触发守卫可正常放行）
                if (hasSubs) {
                    for (String rawSubCid : conv.getWaitingSubCids().split(",")) {
                        String trimmed = rawSubCid.trim();
                        Long subCid;
                        try {
                            subCid = Long.valueOf(trimmed);
                        } catch (NumberFormatException e) {
                            log.warn("[LoopRecovery] 会话 {} 的 waitingSubCids 含非法子会话 ID，已跳过: {}", cid, trimmed);
                            continue;
                        }
                        // 收场定性由 LoopDataReporter 依子会话库中运行态现场推导（与级联停止同一事实源）：
                        // 仍在跑或已查无此人 → 随进程一起中断（已停止）；已自行收尾 → 结论如实呈现「已完成」，不丢失。
                        // 此处只需提供外部介入的原因文案
                        Conversation subConv = conversationStore.getById(subCid);
                        boolean subWasRunning = subConv == null
                                || (subConv.getLoopRunning() != null && subConv.getLoopRunning() == 1);
                        loopDataReporter.updateWaitingSubAgentToCompleted(context, subCid,
                                subWasRunning ? interruptedReason : null);
                        reconciled++;
                    }
                }

                // 2. 后收口：经事件链清空屏障与账目，同步刷新内存（含 loopRunning 运行态），
                //    防止内存残留 loopRunning=true 把该会话永久卡在「正在运行中」
                loopDataReporter.clearLoopState(context);
            } catch (Exception e) {
                log.warn("[LoopRecovery] 会话 {} 的启动对账失败（继续处理其余会话）", cid, e);
                // 补报失败仍要尽力收口账目，避免残留成永久卡死
                try {
                    loopDataReporter.clearLoopState(agentContextManager.getOrCreateContext(cid));
                } catch (Exception ignored) {
                    log.warn("[LoopRecovery] 会话 {} 的账目收口亦失败，留待运行时自愈扫描兜底", cid);
                }
            }
        }

        if (reconciled > 0) {
            log.warn("[LoopRecovery] 启动对账：为 {} 个残留子会话补报终态工作报告，并已收口全部循环账目（不自动续跑）", reconciled);
        }
    }

    /**
     * 处理受影响的会话，清理其 Loop 运行态字段并回收内存上下文。
     * 若该会话是子智能体，则自动向其父智能体发送挂掉的工作报告，避免父会话被卡死。
     *
     * @param allowParentWake 父会话屏障清空时是否允许唤醒父会话补一轮汇总。
     *                        取值由调用场景决定：定时自愈为 true——进程仍在跑、父会话是真在等；
     *                        启动全量自愈为 false——D1 裁决无人值守不自动续跑。
     *                        作用于父会话侧，与当前会话自身无关
     */
    private void handleAffectedConversation(Long cid, String reason, boolean allowParentWake) {
        Conversation conv = conversationStore.getById(cid);
        if (conv == null) {
            return;
        }

        Long parentCid = conv.getParentCid();
        if (parentCid != null && parentCid != 0L) {
            Lock subDataLock = lockProvider.getConversationDataLock(cid);
            Lock parentDataLock = lockProvider.getConversationDataLock(parentCid);
            subDataLock.lock();
            parentDataLock.lock();
            try {
                log.warn("[LoopRecovery] 检测到子会话 {} 异常中断，开始向父会话 {} 汇报", cid, parentCid);
                // 汇报环节异常隔离：即便向父会话交代失败，子会话自身仍必须完成收口与上下文回收。
                // 收场定性由 LoopDataReporter 现场推导，此处只提供外部介入的原因文案
                try {
                    AgentContext parentContext = agentContextManager.getOrCreateContext(parentCid);
                    boolean allDone = loopDataReporter.updateWaitingSubAgentToCompleted(parentContext, cid, reason);
                    if (allDone && allowParentWake) {
                        log.debug("[LoopRecovery] 所有子智能体已完成/超时清理，异步拉起父智能体: parentCid={}", parentCid);
                        loopCoordinator.executeLoopAsync(parentCid);
                    }
                } catch (Exception e) {
                    log.warn("[LoopRecovery] 向父会话汇报子会话异常中断失败（继续收口子会话自身）: cid={}, parentCid={}",
                            cid, parentCid, e);
                }

                // allowParentWake 必须一路传下去：子会话若自身还挂着孙会话，其收口汇报同样要遵守
                // 「启动自愈不自动续跑」的约束，否则会在重启期隔着两层把父会话拉起来
                loopCoordinator.forceResetLoopState(cid, reason, allowParentWake);
                agentContextManager.removeContext(cid);
            } finally {
                parentDataLock.unlock();
                subDataLock.unlock();
            }
        } else {
            log.debug("[LoopRecovery] 清理顶层会话 {} 异常残留状态", cid);
            Lock dataLock = lockProvider.getConversationDataLock(cid);
            dataLock.lock();
            try {
                // 顶层会话无需向任何人汇报，原因文案传 null
                loopCoordinator.forceResetLoopState(cid, null, allowParentWake);
                agentContextManager.removeContext(cid);
            } finally {
                dataLock.unlock();
            }
        }
    }
}
