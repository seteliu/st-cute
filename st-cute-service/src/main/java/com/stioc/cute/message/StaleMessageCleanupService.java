package com.stioc.cute.message;

import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.agent.access.AgentContextManager;
import com.stioc.cute.agent.access.AgentLoopCoordinator;
import com.stioc.cute.conversation.access.ConversationService;
import com.stioc.cute.message.access.MessageStatus;
import com.stioc.cute.message.access.MessageEntity;
import com.stioc.cute.message.access.MessageRole;
import com.mybatisflex.core.query.QueryWrapper;
import com.stioc.cute.repository.MessageMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 僵死消息时效清理服务。
 *
 * <p>PENDING/RUNNING 消息在正常流程中是短暂的过渡状态，
 * 若进程崩溃或线程异常终止，这些消息会永久停留在过渡状态，前端会将其误判为错误。
 *
 * <p>清理策略：
 * <ul>
 *   <li><b>启动时</b>：对所有残留的 PENDING/RUNNING 消息做一次全量修复，
 *       不设时间门限——上次进程都死了，不管多新都不可能再被推进。</li>
 *   <li><b>运行时</b>：每 60 秒扫描一次，仅将超过 {@value #STALE_THRESHOLD_MINUTES} 分钟
 *       仍处于 PENDING/RUNNING 的消息标记为 FAILED，
 *       给正在推进中的消息留出充足的正常执行时间。</li>
 * </ul>
 */
@Slf4j
@Service
public class StaleMessageCleanupService {

    /**
     * 运行时扫描的超时门限（单位：分钟），超过此时长仍未完结则视为僵死
     */
    private static final int STALE_THRESHOLD_MINUTES = 10;

    /**
     * 启动时扫描判定为僵死状态的消息状态集合
     */
    private static final Set<MessageStatus> STARTUP_STALE_STATUSES =
            EnumSet.of(MessageStatus.PENDING, MessageStatus.RUNNING, MessageStatus.WAITING_APPROVAL);

    /**
     * 定时任务扫描判定为僵死状态的消息状态集合
     */
    private static final Set<MessageStatus> SCHEDULE_STALE_STATUSES =
            EnumSet.of(MessageStatus.PENDING, MessageStatus.RUNNING, MessageStatus.WAITING_APPROVAL);

    @Resource
    private MessageMapper messageMapper;
    @Resource
    private AgentContextManager agentContextManager;
    @Resource
    private ConversationService conversationService;
    @Resource
    private AgentLoopCoordinator agentLoopCoordinator;


    // ──────────────────────────────────────────────
    // 启动时全量修复
    // ──────────────────────────────────────────────

    /**
     * 应用启动后立即执行一次全量扫描。
     * 上次进程已死，所有残留的 PENDING/RUNNING/WAITING_APPROVAL 消息均不可能被推进，直接标记为 FAILED。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void cleanOnStartup() {
        conversationService.resetAllConversationsLoopRunning();
        int fixed = fixStaleMessagesOnStartup();
        if (fixed > 0) {
            log.warn("[StaleCleanup] 启动扫描：检测到 {} 条残留消息（PENDING/RUNNING/WAITING_APPROVAL），已全部标记为 FAILED", fixed);
        } else {
            log.info("[StaleCleanup] 启动扫描：未发现残留消息");
        }
    }

    // ──────────────────────────────────────────────
    // 运行时定时扫描
    // ──────────────────────────────────────────────

    /**
     * 每 60 秒执行一次，清理超过 {@value #STALE_THRESHOLD_MINUTES} 分钟未完结的消息。
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void cleanOnSchedule() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STALE_THRESHOLD_MINUTES);
        int fixed = fixStaleMessagesOnSchedule(threshold);
        if (fixed > 0) {
            log.warn("[StaleCleanup] 定时扫描：清理了 {} 条超过 {} 分钟的僵死消息", fixed, STALE_THRESHOLD_MINUTES);
        }
    }

    // ──────────────────────────────────────────────
    // 核心修复逻辑
    // ──────────────────────────────────────────────

    /**
     * 启动时全量修复：将所有残留的非终态消息强制修改为 FAILED（表示系统发生过重启，执行被迫终止）。
     */
    private int fixStaleMessagesOnStartup() {
        QueryWrapper query = QueryWrapper.create()
                .where(MessageEntity::getStatus).in(STARTUP_STALE_STATUSES);
        List<MessageEntity> staleMessages = messageMapper.selectListByQuery(query);
        if (staleMessages.isEmpty()) {
            return 0;
        }

        Set<Long> affectedCids = new HashSet<>();
        for (MessageEntity msg : staleMessages) {
            msg.setStatus(MessageStatus.FAILED);
            if (msg.getRole() == MessageRole.TOOL) {
                msg.setContent("{\"error\": \"系统发生重启，执行被迫终止。\"}");
            } else if (msg.getRole() == MessageRole.ASSISTANT && (msg.getContent() == null || msg.getContent().isBlank())) {
                msg.setContent("[错误] 系统发生重启，执行被迫终止。");
            }
            if (msg.getCid() != null) {
                affectedCids.add(msg.getCid());
            }
        }

        for (MessageEntity msg : staleMessages) {
            messageMapper.update(msg);
        }

        for (Long cid : affectedCids) {
            handleAffectedConversation(cid, "系统发生重启，执行被迫终止");
        }

        return staleMessages.size();
    }

    /**
     * 定时任务超时修复：
     * 1. WAITING_APPROVAL 超时未审批的，算作 CANCELED。
     * 2. PENDING / RUNNING 超时未跑完的，算作 FAILED。
     */
    private int fixStaleMessagesOnSchedule(LocalDateTime createdBefore) {
        QueryWrapper query = QueryWrapper.create()
                .where(MessageEntity::getStatus).in(SCHEDULE_STALE_STATUSES)
                .and(MessageEntity::getCreatedAt).lt(createdBefore);
        List<MessageEntity> staleMessages = messageMapper.selectListByQuery(query);
        if (staleMessages.isEmpty()) {
            return 0;
        }

        Set<Long> affectedCids = new HashSet<>();
        for (MessageEntity msg : staleMessages) {
            if (msg.getStatus() == MessageStatus.WAITING_APPROVAL) {
                msg.setStatus(MessageStatus.CANCELED);
                if (msg.getRole() == MessageRole.TOOL) {
                    msg.setContent("{\"error\": \"审批超时，已被系统自动取消。\"}");
                }
            } else {
                msg.setStatus(MessageStatus.FAILED);
                if (msg.getRole() == MessageRole.TOOL) {
                    msg.setContent("{\"error\": \"工具执行超时，已被系统强行终止。\"}");
                } else if (msg.getRole() == MessageRole.ASSISTANT && (msg.getContent() == null || msg.getContent().isBlank())) {
                    msg.setContent("[错误] 执行超时，已被系统强行终止。");
                }
            }
            if (msg.getCid() != null) {
                affectedCids.add(msg.getCid());
            }
        }

        for (MessageEntity msg : staleMessages) {
            messageMapper.update(msg);
        }

        for (Long cid : affectedCids) {
            handleAffectedConversation(cid, "执行超时，已被系统强行终止");
        }

        return staleMessages.size();
    }

    /**
     * 处理受影响的会话，清理其 Loop 运行态字段并回收内存上下文。
     * 若该会话是子智能体，则自动向其父智能体发送挂掉的工作报告，避免父会话被卡死。
     */
    private void handleAffectedConversation(Long cid, String reason) {
        conversationService.findById(cid).ifPresent(conv -> {
            Long parentCid = conv.getParentCid();
            if (parentCid != null && parentCid != 0L) {
                log.warn("[StaleCleanup] 检测到子会话 {} 异常中断，开始向父会话 {} 汇报", cid, parentCid);
                // 1. 获取/构建子会话的运行上下文
                AgentContext subContext = agentContextManager.getOrCreateContext(cid);

                // 2. 汇报给父 Agent 并更新父会话等待状态
                AgentContext parentContext = agentContextManager.getOrCreateContext(parentCid);
                boolean allDone = conversationService.handleSubAgentFinishedHook(parentContext, cid, reason);
                if (allDone) {
                    log.debug("[StaleCleanup] 所有子智能体已完成/超时清理，异步拉起父智能体: parentCid={}", parentCid);
                    agentLoopCoordinator.executeLoopAsync(parentCid);
                }

                // 3. 强制重置子会话自身的数据库 Loop 运行态字段（callToolCount、waitingToolIds、waitingSubCids）及内存状态
                conversationService.forceResetLoopState(subContext);

                // 4. 回收子会话内存运行上下文及其专属 MCP 等物理进程资源
                agentContextManager.removeContext(cid);
            } else {
                // 顶层会话挂了，重置其数据库 Loop 状态，并回收内存上下文与 MCP 进程
                log.debug("[StaleCleanup] 清理顶层会话 {} 异常残留状态", cid);
                AgentContext context = agentContextManager.getOrCreateContext(cid);
                conversationService.forceResetLoopState(context);
                agentContextManager.removeContext(cid);
            }
        });
    }
}
