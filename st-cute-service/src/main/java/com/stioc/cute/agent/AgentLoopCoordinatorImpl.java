package com.stioc.cute.agent;

import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.agent.access.AgentContextManager;
import com.stioc.cute.agent.access.AgentLoopCoordinator;
import com.stioc.cute.agent.event.AgentEventFactory;
import com.stioc.cute.conversation.access.ConversationEntity;
import com.mybatisflex.core.util.UpdateEntity;
import com.stioc.cute.conversation.access.ConversationService;
import com.stioc.cute.message.access.MessageService;
import com.stioc.cute.message.access.MessageEntity;
import com.stioc.cute.message.access.MessageRole;
import com.stioc.cute.message.access.MessageStatus;
import com.stioc.cute.platform.common.CommonThread;
import com.stioc.cute.platform.contract.ContractLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.concurrent.locks.Lock;

/**
 * 智能体 Loop 调度与协调管理器实现类。
 * 负责调度 ReAct 主循环（加锁互斥、多线程拉起、工具回调、人在回路中断与强制重置状态）。
 */
@Slf4j
@Service
public class AgentLoopCoordinatorImpl implements AgentLoopCoordinator {

    @Resource
    private AgentProcessor agentProcessor;
    @Resource
    private AgentContextManager agentContextManager;
    @Resource
    private ConversationService conversationService;
    @Resource
    private MessageService messageService;

    /**
     * 同步执行 ReAct 推理循环，进行 cid 级互斥加锁排队
     */
    public void executeLoopSync(Long cid) {
        Lock lock = ContractLock.CID_LOOP_STRIPED.get(cid);
        lock.lock();
        try {
            log.debug("[AgentLoopCoordinator] 获得会话锁，开始执行 ReAct 循环: cid={}", cid);
            
            AgentContext context = agentContextManager.getOrCreateContext(cid);
            if (context == null) {
                log.warn("[AgentLoopCoordinator] 未能获取 AgentContext，退出执行: cid={}", cid);
                return;
            }

            // 联动防重入：若会话当前未运行，且数据库中并没有任何新写/待处理的 PENDING 消息，说明是重复排队任务，直接安全拦截退出。
            if (!conversationService.isLoopRunning(cid)) {
                if (!messageService.hasPendingMessages(cid)) {
                    log.debug("[AgentLoopCoordinator] 会话处于挂起态(loopRunning=0)且无待处理消息(PENDING)，直接拦截排队期间的重复执行: cid={}", cid);
                    return;
                }
            }

            // Cancel 门禁：若会话已被取消且本次不是由用户主动发消息触发（用户发消息由 HTTP 入口层提前清除 canceled），
            // 说明是工具回调等内部驱动的重拉，直接拦截，防止 cancel 后仍继续执行下一轮。
            if (context.isCanceled()) {
                log.debug("[AgentLoopCoordinator] 会话已被标记取消，拦截本次内部驱动的 Loop 重拉: cid={}", cid);
                return;
            }

            // 更新内存中活跃线程（注意：不在此处清除 canceled，由 HTTP 入口层在用户主动操作时负责清除）
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
                    if (!conversationService.isLoopRunning(context.getCid())) {
                        AgentContext parentContext = agentContextManager.getOrCreateContext(context.getParentCid());
                        boolean allDone = conversationService.handleSubAgentFinishedHook(parentContext, context.getCid(), null);
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
        CommonThread.submit(() -> {
            try {
                executeLoopSync(cid);
            } catch (Exception e) {
                log.error("[AgentLoopCoordinator] 异步执行 ReAct 循环发生异常: cid={}", cid, e);
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
        // 1. 调用 ConversationService 仅清空 DB 的 Loop 状态指标，保留 Service 收口
        conversationService.forceResetLoopState(context);

        // 2. 抢占式清理：将本会话所有尚未完成的 TOOL 消息全部标记为 CANCELED（通过发布 MESSAGE_UPDATE 事件，以使缓存和前端 WebSocket 完美自愈同步）
        try {
            List<MessageEntity> inflightTools = messageService.findInflightToolMessages(cid);
            for (MessageEntity tool : inflightTools) {
                MessageEntity updateMsg = MessageEntity.builder()
                        .id(tool.getId())
                        .cid(cid)
                        .status(MessageStatus.CANCELED)
                        .content("{\"error\": \"Execution canceled by user.\"}")
                        .build();
                context.publishEvent(AgentEventFactory.createMessageUpdate(context, updateMsg));
            }
        } catch (Exception e) {
            log.warn("[AgentLoopCoordinator] 抢占式清理未完成的工具消息失败: cid={}", cid, e);
        }

        // 2.5 终态兜底：将所有仍处于 RUNNING/PENDING 的 ASSISTANT 消息先行置为 CANCELED（content 置空以保留已流出的正文），
        //     不再被动依赖执行线程响应中断后才落终态。即使线程仍卡在阻塞 IO，消息也不会悬空转圈。
        try {
            List<MessageEntity> inflightAssistants = messageService.findInflightMessagesByRoles(cid, List.of(MessageRole.ASSISTANT));
            for (MessageEntity assistant : inflightAssistants) {
                MessageEntity updateMsg = MessageEntity.builder()
                        .id(assistant.getId())
                        .cid(cid)
                        .role(MessageRole.ASSISTANT)
                        .status(MessageStatus.CANCELED)
                        .build();
                context.publishEvent(AgentEventFactory.createMessageUpdate(context, updateMsg));

                // 同步结束前端的流式思考动效，防止界面上的 isStreaming 状态残留
                context.publishEvent(AgentEventFactory.createThinkingStream(context, "\n\n[已取消] 当前 Agent 运行已停止。", false, true, assistant.getId()));
            }
        } catch (Exception e) {
            log.warn("[AgentLoopCoordinator] 兜底清理未完成的 ASSISTANT 消息失败: cid={}", cid, e);
        }

        // 3. 同步中断内存中的活跃执行线程并更新 loopRunning 为 false 通知前端
        if (context != null) {
            ConversationEntity loopEndPayload = UpdateEntity.of(ConversationEntity.class);
            loopEndPayload.setId(cid);
            loopEndPayload.setLoopRunning(0);
            context.publishEvent(AgentEventFactory.createConversationUpdate(context, loopEndPayload));
            context.setCanceled(true);

            // 3.5 强制取消活跃的物理副作用（强杀外部子进程 + cancel 大模型 HTTP 连接）：
            //     OkHttp 阻塞读（readLine/execute）不响应 Thread.interrupt()，必须 cancel Call 才能立即解除阻塞，
            //     否则线程会一直阻塞至 readTimeout(300s) 或下一个 chunk 到来才醒来。
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
            // 调用 ConversationService 发送屏障扣减事件并评估是否全部完成
            allDone = conversationService.onToolCompleted(context, toolCallId);
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
}
