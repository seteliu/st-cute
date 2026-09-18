package com.stioc.cute.engine.loop.core;

import com.stioc.cute.engine.common.EngineExecutor;
import com.stioc.cute.engine.common.EngineLock;
import com.stioc.cute.engine.loop.message.MessageDataReporter;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.store.types.ConversationQuery;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.support.ChatNamingHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.locks.Lock;

/**
 * 循环的门外秩序：ReAct 循环的调度与协调（何时跑：锁排队/防重入/cancel 门禁/拉起下一轮/强停清场）。
 * <p>
 * 在进入 AgentLoopProcessor（门内流程）之前裁决循环该不该跑、并发冲突如何排队、取消如何收场——本类持有调度权；
 * 门内门外的账目变更统一委托 LoopDataReporter（数据上报器）记账。
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class AgentLoopCoordinator {

    private final AgentLoopProcessor agentProcessor;
    private final AgentContextManager agentContextManager;
    private final ConversationStore conversationStore;
    private final LoopDataReporter loopDataReporter;
    private final MessageStore messageStore;
    private final MessageDataReporter messageDataReporter;
    private final EngineLock lockProvider;
    private final EngineExecutor executorProvider;
    private ChatNamingHelper chatNamingHelper;

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
                    // 只有子代理会话的 loopRunning 变成 0（真正推理终结且助手消息入库）时，才向父智能体汇报
                    if (!isConversationLoopRunning(context.getCid())) {
                        AgentContext parentContext = agentContextManager.getOrCreateContext(context.getParentCid());
                        boolean allDone = loopDataReporter.updateWaitingSubAgentToCompleted(parentContext, context.getCid(), null);
                        if (allDone) {
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
     */
    public void forceResetLoopState(Long cid) {
        log.debug("[AgentLoopCoordinator] 开始强制重置 Loop 状态并强制中断线程: cid={}", cid);

        AgentContext context = agentContextManager.getOrCreateContext(cid);
        // 1. 调用数据上报器仅清空 DB 的 Loop 状态指标，保留上报收口
        loopDataReporter.clearLoopState(context);

        // 2. 抢占式清理：将本会话所有尚未完成的 TOOL 与 ASSISTANT 消息全部标记为 CANCELED
        // （通过发布 MESSAGE_UPDATE 事件，以使缓存和前端 WebSocket 完美自愈同步）。
        try {
            messageDataReporter.cancelAllInflightMessages(context, "Execution canceled by user.");
        } catch (Exception e) {
            log.warn("[AgentLoopCoordinator] 抢占式清理未完成的消息失败: cid={}", cid, e);
        }

        // 3. 同步中断内存中的活跃执行线程并通知前端
        if (context != null) {
            context.setCanceled(true);

            // 3.5 强制取消活跃的物理副作用（强杀外部子进程 + cancel 大模型 HTTP 连接）
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
            log.warn("[AgentLoopCoordinator] 更新工具等待状态失败: cid={}, toolCallId={}", context.getCid(), toolCallId, e);
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
        return conversationStore.existsByQuery(ConversationQuery.builder()
                .id(cid)
                .loopRunning(1)
                .build());
    }
}
