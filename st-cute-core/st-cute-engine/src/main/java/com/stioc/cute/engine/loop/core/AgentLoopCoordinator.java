package com.stioc.cute.engine.loop.core;

import com.stioc.cute.engine.assembly.EngineInfra;
import com.stioc.cute.engine.assembly.EngineStores;
import com.stioc.cute.engine.common.EngineExecutor;
import com.stioc.cute.engine.common.EngineLock;
import com.stioc.cute.engine.loop.message.MessageDataReporter;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.store.types.*;
import com.stioc.cute.engine.support.ChatNamingHelper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;

/**
 * 循环的门外秩序：ReAct 循环的调度与协调（何时跑：锁排队/防重入/cancel 门禁/拉起下一轮/强停清场）。
 * <p>
 * 在进入 AgentLoopProcessor（门内流程）之前裁决循环该不该跑、并发冲突如何排队、取消如何收场——本类持有调度权；
 * 门内门外的账目变更统一委托 LoopDataReporter（数据上报器）记账。
 * </p>
 */
@Slf4j
public class AgentLoopCoordinator {

    private final AgentLoopProcessor agentProcessor;
    private final AgentContextManager agentContextManager;
    private final LoopDataReporter loopDataReporter;
    private final MessageDataReporter messageDataReporter;
    private ChatNamingHelper chatNamingHelper;

    private final ConversationStore conversationStore;
    private final MessageStore messageStore;
    private final EngineLock lockProvider;
    private final EngineExecutor executorProvider;

    /**
     * 收存储对与技术设施聚合，构造器内解包为实际使用字段
     */
    public AgentLoopCoordinator(AgentLoopProcessor agentProcessor,
                                AgentContextManager agentContextManager,
                                EngineStores stores,
                                LoopDataReporter loopDataReporter,
                                MessageDataReporter messageDataReporter,
                                EngineInfra infra) {
        this.agentProcessor = agentProcessor;
        this.agentContextManager = agentContextManager;
        this.loopDataReporter = loopDataReporter;
        this.messageDataReporter = messageDataReporter;
        this.conversationStore = stores.getConversations();
        this.messageStore = stores.getMessages();
        this.lockProvider = infra.getLocks();
        this.executorProvider = infra.getExecutor();
    }

    /**
     * 绑定会话智能命名助手（Builder 装配后回填）
     */
    public void bindChatNamingHelper(ChatNamingHelper chatNamingHelper) {
        this.chatNamingHelper = chatNamingHelper;
    }

    /**
     * 同步执行 ReAct 推理循环，进行 cid 级互斥加锁排队
     */
    public void executeLoopSync(Long cid) {
        Lock lock = lockProvider.getConversationLoopLock(cid);
        lock.lock();
        try {
            log.debug("[AgentLoopCoordinator] 获得会话锁，开始执行 ReAct 循环: cid={}", cid);

            AgentContext context = agentContextManager.getOrCreateContext(cid);
            if (context == null) {
                log.warn("[AgentLoopCoordinator] 未能获取 AgentContext，退出执行: cid={}", cid);
                return;
            }

            // 联动防重入：若会话当前未运行，且数据库中并没有任何新写/待处理的 PENDING 消息，说明是重复排队任务，直接安全拦截退出。
            if (!isConversationLoopRunning(cid)) {
                boolean hasPending = messageStore.existsByQuery(MessageQuery.builder()
                        .cid(cid)
                        .status(MessageStatus.PENDING)
                        .build());
                if (!hasPending) {
                    log.debug("[AgentLoopCoordinator] 会话处于挂起态(loopRunning=0)且无待处理消息(PENDING)，直接拦截排队期间的重复执行: cid={}", cid);
                    context.setLoopRunning(false);
                    return;
                }
            }

            // Cancel 门禁：若会话已被取消且本次不是由用户主动发消息触发，
            // 说明是工具回调等内部驱动的重拉，直接拦截，防止 cancel 后仍继续执行下一轮。
            if (context.isCanceled()) {
                log.debug("[AgentLoopCoordinator] 会话已被标记取消，拦截本次内部驱动的 Loop 重拉: cid={}", cid);
                context.setLoopRunning(false);
                return;
            }

            // 更新内存中活跃线程
            context.setActiveThread(Thread.currentThread());

            // 执行推理
            try {
                agentProcessor.executeLoop(context);
            } finally {
                // 清理活跃线程状态
                if (context.getActiveThread() == Thread.currentThread()) {
                    context.setActiveThread(null);
                }
                // 联动 Hook：若是子智能体且已停止运行，写入工作报告并尝试唤醒父智能体
                if (context.getParentCid() != null) {
                    // 只有子代理会话的 loopRunning 变成 0（真正推理终结且助手消息入库）时，才向父智能体汇报。
                    // 收场定性由 LoopDataReporter 依子会话库中运行态现场推导（已完成/失败/已中断），无需传原因
                    if (!isConversationLoopRunning(context.getCid())) {
                        AgentContext parentContext = agentContextManager.getOrCreateContext(context.getParentCid());
                        boolean allDone = loopDataReporter.updateWaitingSubAgentToCompleted(parentContext,
                                context.getCid(), null);
                        if (allDone && !parentContext.isCanceled()) {
                            log.debug("[AgentLoopCoordinator] 所有子智能体已完成，异步拉起父智能体: parentCid={}", context.getParentCid());
                            executeLoopAsync(context.getParentCid());
                        }
                    }
                }
            }
        } finally {
            log.debug("[AgentLoopCoordinator] 释放会话锁: cid={}", cid);
            lock.unlock();
        }
    }

    /**
     * 异步提交执行推理
     */
    public void executeLoopAsync(Long cid) {
        executeLoopAsync(cid, null);
    }

    /**
     * 异步提交执行推理，并于推理结束后执行回调
     */
    public void executeLoopAsync(Long cid, Runnable afterRun) {
        executorProvider.getAsyncExecutor().submit(() -> {
            try {
                executeLoopSync(cid);
            } catch (Exception e) {
                log.error("[AgentLoopCoordinator] 异步执行 ReAct 循环发生异常: cid={}", cid, e);
                AgentContext ctx = agentContextManager.getActiveContext(cid);
                if (ctx != null) {
                    ctx.setLoopRunning(false);
                }
            } finally {
                if (afterRun != null) {
                    try {
                        afterRun.run();
                    } catch (Exception e) {
                        log.error("[AgentLoopCoordinator] 执行 afterRun 回调异常: cid={}", cid, e);
                    }
                }
            }
        });
    }

    /**
     * 强制重置 Loop 状态（DB 侧重置快照数据，同步中断并等待内存中活跃执行线程退出，并将挂起的消息标为 CANCELED）
     * <p>
     * 顶层会话调用时会级联强停其名下仍在运行的子会话——「主会话停止」的语义必须是整棵树停止，
     * 否则子 Agent 会在后台继续烧 token 并把结论投递给一个已经停摆的父会话。
     * </p>
     * <p>
     * 用户主动停止：父会话真在等待子会话时会被唤醒补一轮汇总（allowParentWake=true）。
     * </p>
     */
    public void forceResetLoopState(Long cid) {
        forceResetLoopState(cid, null, true);
    }

    /**
     * 强制重置 Loop 状态的完整形态。
     * <p>
     * <b>停止语义的三条不变量</b>：
     * <ol>
     *   <li><b>级联</b>：停父必停子树，且级联排在父自身清账之前——父屏障若先被抹掉，
     *       子会话的终止汇报会被触发守卫当作「迟到」丢弃，父会话消息流里将不留任何痕迹；</li>
     *   <li><b>等同错误结束</b>：子会话被停后仍要向父会话写工作报告并扣减等待屏障，
     *       否则父会话的屏障永远扣不掉、父永远醒不来；</li>
     *   <li><b>不复活</b>：停止期间父会话不得被级联汇报误唤醒（父自身已置 canceled，
     *       该门禁在 {@link #executeLoopSync} 与 {@link #notifyToolCompleted} 双重兜底）。
     * </ol>
     * </p>
     *
     * @param cid        目标会话 ID
     * @param reason     停止原因文案（纯展示用途：本会话恰好是子会话、需向父会话汇报时才被消费；
     *                   顶层会话忽略。为空时取默认文案）
     * @param allowParentWake 父会话屏障清空时是否允许唤醒父会话补一轮汇总。
     *                   取值由调用场景决定：用户主动停止/定时自愈为 true（父会话真在等）；
     *                   进程重启自愈为 false（D1 裁决：无人值守不自动续跑）。
     *                   作用于父会话侧，与当前会话自身无关——当前会话是顶层会话时该值无消费方
     */
    public void forceResetLoopState(Long cid, String reason, boolean allowParentWake) {
        log.debug("[AgentLoopCoordinator] 开始强制重置 Loop 状态并强制中断线程: cid={}, allowParentWake={}",
                cid, allowParentWake);

        AgentContext context = agentContextManager.getOrCreateContext(cid);

        // 1. 抢占取消门禁：先置 canceled 再动手，防止级联与汇报期间父会话被误唤醒后真的开跑
        context.setCanceled(true);

        // 2. 级联强停仍在运行的子会话（必须排在自身清账之前，保证子会话的终止汇报能正常落库并扣减屏障；
        //    且必须排在清账之前才能读到父会话此刻完整的待办子会话屏障，据以判定谁还在跑）
        stopSubConversations(context, allowParentWake);

        // 3. 向父会话汇报自身的停止（子会话专有：等同错误结束，仅状态与原因不同。
        //    收场定性由 LoopDataReporter 依子会话停止时刻的库中运行态现场推导，无需调用方传递）
        reportStopToParent(context, reason, allowParentWake);

        // 4. 调用数据上报器仅清空 DB 的 Loop 状态指标，保留上报收口
        //    （异常隔离：此前插入的级联与汇报环节已各自容错，此处再兜一层，
        //     确保「用户点了停止」这一语义上最关键的清场动作绝不会被任何上游异常跳过）
        try {
            loopDataReporter.clearLoopState(context);
        } catch (Exception e) {
            log.warn("[AgentLoopCoordinator] 清空会话循环账目失败（继续执行后续清场）: cid={}", cid, e);
        }

        // 5. 抢占式清理：将本会话所有尚未完成的 TOOL 与 ASSISTANT 消息全部标记为 CANCELED
        // （通过发布 MESSAGE_UPDATE 事件，以使缓存和前端 WebSocket 完美自愈同步）。
        try {
            messageDataReporter.cancelAllInflightMessages(context, "Execution canceled by user.");
        } catch (Exception e) {
            log.warn("[AgentLoopCoordinator] 抢占式清理未完成的消息失败: cid={}", cid, e);
        }

        // 6. 强制取消活跃的物理副作用（强杀外部子进程 + cancel 大模型 HTTP 连接），并中断活跃执行线程
        agentContextManager.cancelActiveSideEffects(context);

        Thread activeThread = context.getActiveThread();
        if (activeThread != null && activeThread.isAlive()) {
            log.debug("[AgentLoopCoordinator] 向活跃执行线程 {} 发送中断信号: cid={}", activeThread.getName(), cid);
            activeThread.interrupt();
            try {
                activeThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (activeThread.isAlive()) {
                log.warn("[AgentLoopCoordinator] 旧执行线程 {} 在 2 秒内未退出，强制继续: cid={}", activeThread.getName(), cid);
            }
        }
    }

    /**
     * 级联强停指定会话名下「仍在运行」的子会话（单个失败不影响其余，绝不阻断父会话的停止主流程）。
     * <p>
     * <b>只级联还在跑的子会话</b>：已完结的历史子会话不牵连（其报告早已投递、屏障早已扣减），
     * 无差别遍历会对它们做无谓的上下文创建（{@code getOrCreateContext} 会触发宿主资产装载：
     * 读技能/规则/MCP），并越界清理其历史消息。
     * </p>
     */
    private void stopSubConversations(AgentContext parentContext, boolean allowParentWake) {
        Long cid = parentContext.getCid();
        List<Conversation> children;
        try {
            children = conversationStore.listByQuery(ConversationQuery.builder()
                    .parentCid(cid)
                    .build());
        } catch (Exception e) {
            log.warn("[AgentLoopCoordinator] 查询子会话失败，跳过级联停止: cid={}", cid, e);
            return;
        }
        if (children.isEmpty()) {
            return;
        }

        // 父会话此刻的待办子会话快照（本方法排在 clearLoopState 之前，故内存屏障仍完整）
        Set<Long> pendingSubCids = Set.copyOf(parentContext.getWaitingSubCids());
        String cascadeReason = "父会话已停止，级联终止该子智能体。";

        for (Conversation child : children) {
            Long childCid = child.getId();
            if (childCid == null || childCid.equals(cid)) {
                // 跳过非法 ID 与自环脏数据（parentCid 指向自身会造成无限递归）
                continue;
            }
            boolean childRunning = child.getLoopRunning() != null && child.getLoopRunning() == 1;
            if (!childRunning && !pendingSubCids.contains(childCid)) {
                // 已完结的历史子会话：不牵连（其报告早已投递、屏障早已扣减）
                continue;
            }
            try {
                // 停止原因按现场分流：仍在跑的子会话交代「被级联终止」；
                // 已自行收尾却仍占位于屏障的（跑完了、父会话尚未被唤醒就点了停止）传 null，
                // 其结论按自身终态如实呈现「已完成」，避免把一次成功执行误报成被停止
                log.info("[AgentLoopCoordinator] 级联强停子会话: parentCid={}, subCid={}, running={}",
                        cid, childCid, childRunning);
                forceResetLoopState(childCid, childRunning ? cascadeReason : null, allowParentWake);
            } catch (Exception e) {
                // 单个子会话异常不得中断级联，更不得阻断父会话自身的清场
                log.warn("[AgentLoopCoordinator] 级联强停子会话异常，继续处理其余子会话: subCid={}", childCid, e);
            }
        }
    }

    /**
     * 子会话被强制停止后向父会话写工作报告并扣减等待屏障（子会话专有收口）。
     * <p>
     * 这是「子 Agent 被强杀后父 Agent 永远在等」的根治点：无论子会话是否有活跃线程，
     * 停止动作本身都必须完成上报与扣减，父会话才可能被唤醒或收口。
     * </p>
     * <p>
     * 全程异常隔离：汇报走事件链（第一、二层失败会熔断抛异常），若任其传播将导致
     * 调用方的清账与消息清理被整体跳过——用户点了停止，父会话反而卡在「运行中」。
     * </p>
     */
    private void reportStopToParent(AgentContext context, String reason, boolean allowParentWake) {
        Long parentCid = context.getParentCid();
        if (parentCid == null || parentCid == 0L) {
            return;
        }
        try {
            AgentContext parentContext = agentContextManager.getOrCreateContext(parentCid);
            boolean allDone = loopDataReporter.updateWaitingSubAgentToCompleted(parentContext, context.getCid(), reason);
            if (allDone && allowParentWake && !parentContext.isCanceled()) {
                log.debug("[AgentLoopCoordinator] 子会话停止后父会话等待清空，唤醒父智能体汇总: parentCid={}", parentCid);
                executeLoopAsync(parentCid);
            }
        } catch (Exception e) {
            log.warn("[AgentLoopCoordinator] 向父会话汇报子会话停止失败（不阻断停止主流程）: subCid={}, parentCid={}",
                    context.getCid(), parentCid, e);
        }
    }

    /**
     * 工具进入终态后的统一回调，供外部回调（如人在回路审批恢复）调用。
     * 当会话等待工具清空时，拉起下一轮 Loop 执行。
     */
    public void notifyToolCompleted(AgentContext context, String toolCallId) {
        boolean allDone;
        try {
            // 调用数据上报器发送屏障扣减事件并评估是否全部完成
            allDone = loopDataReporter.updateWaitingToolToCompleted(context, toolCallId);
        } catch (Exception e) {
            log.warn("[AgentLoopCoordinator] 更新工具等待状态失败: cid={}, toolCallId={}",
                    context.getCid(), toolCallId, e);
            return;
        }

        if (allDone) {
            // Cancel 门禁：工具全部完成时，如果会话已被取消则不拉起下一轮 LLM 推理
            if (context.isCanceled()) {
                log.debug("[AgentLoopCoordinator] 所有工具已完成但会话已被取消，不拉起下一轮推理: cid={}", context.getCid());
                return;
            }
            log.debug("[AgentLoopCoordinator] 本轮所有工具和子 Agent 均已完成，拉起下一轮 LLM 推理: cid={}", context.getCid());
            executeLoopAsync(context.getCid());
        }
    }

    /**
     * 提交用户新消息并异步启动推理循环（加锁防并发重入，清除 canceled、轮次归零、原子标记运行态、写 USER 消息并在结束时尝试命名）
     */
    public void submitUserMessage(Long cid, String text, String attachments) {
        Lock dataLock = lockProvider.getConversationDataLock(cid);
        dataLock.lock();
        try {
            AgentContext context = agentContextManager.getOrCreateContext(cid);
            if (context.isLoopRunning() || isConversationLoopRunning(cid)) {
                throw new IllegalStateException("当前会话正在运行中，无法发送新消息");
            }
            context.setLoopRunning(true);
            context.setCanceled(false);
            context.setLoopCount(0);
            messageDataReporter.createUserMessage(context, text, attachments);
            executeLoopAsync(cid, () -> {
                if (chatNamingHelper != null) {
                    chatNamingHelper.autoRenameChatIfNew(cid);
                }
            });
        } finally {
            dataLock.unlock();
        }
    }

    /**
     * 重试指定消息：若为 ASSISTANT 则重置为 PENDING 并启动循环；若为 USER 则回退至该节点重新启动循环（加锁防并发重入）
     */
    public void retryMessage(Long cid, Long messageId) {
        Lock dataLock = lockProvider.getConversationDataLock(cid);
        dataLock.lock();
        try {
            Message message = messageStore.getById(messageId);
            if (message == null) {
                throw new IllegalArgumentException("未找到待重试的消息, messageId=" + messageId);
            }

            AgentContext context = agentContextManager.getOrCreateContext(cid);
            if (context.isLoopRunning() || isConversationLoopRunning(cid)) {
                throw new IllegalStateException("当前会话正在运行中，无法重试消息");
            }

            context.setLoopRunning(true);
            context.setCanceled(false);
            context.setLoopCount(0);

            if (MessageRole.ASSISTANT == message.getRole()) {
                messageDataReporter.resetAssistantMessage(context, messageId);
            } else if (MessageRole.USER == message.getRole()) {
                messageDataReporter.resetUserMessageAndTruncateSubsequent(context, messageId);
            }

            executeLoopAsync(cid);
        } finally {
            dataLock.unlock();
        }
    }

    /**
     * 回退并重置会话消息历史至指定节点（删除该节点及之后的所有消息）
     */
    public void resetConversationMessages(Long cid, Long messageId) {
        log.info("重置会话 {} 至消息节点 {}", cid, messageId);
        AgentContext context = agentContextManager.getOrCreateContext(cid);
        Message targetMsg = messageStore.getByQuery(MessageQuery.builder()
                .cid(cid)
                .id(messageId)
                .role(MessageRole.USER)
                .build());
        if (targetMsg == null) {
            throw new IllegalArgumentException("未找到对应的用户消息或消息不属于该会话, messageId=" + messageId);
        }

        boolean hasCompressed = messageStore.existsByQuery(MessageQuery.builder()
                .cid(cid)
                .greaterThanId(messageId)
                .role(MessageRole.COMPRESSED)
                .build());
        if (hasCompressed) {
            throw new IllegalStateException("后续消息中存在已压缩的上下文，无法重置到此节点");
        }

        messageDataReporter.deleteMessagesFrom(context, messageId);
    }

    private boolean isConversationLoopRunning(Long cid) {
        if (cid == null) {
            return false;
        }
        AgentContext ctx = agentContextManager.getActiveContext(cid);
        if (ctx != null) {
            return ctx.isLoopRunning();
        }
        return conversationStore.existsByQuery(ConversationQuery.builder()
                .id(cid)
                .loopRunning(1)
                .build());
    }
}
