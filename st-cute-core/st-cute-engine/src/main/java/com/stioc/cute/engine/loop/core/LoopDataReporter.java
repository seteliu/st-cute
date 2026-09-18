package com.stioc.cute.engine.loop.core;

import com.stioc.cute.engine.common.EngineLock;
import com.stioc.cute.engine.event.AgentEventFactory;
import com.stioc.cute.engine.llm.types.CuteUsage;
import com.stioc.cute.engine.loop.message.MessageDataReporter;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.types.SortDirection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.locks.Lock;

/**
 * 循环数据与运行态上报器：等待屏障、轮次与运行态指标的变更收口（门内门外共用，只记账不调度）。
 * <p>
 * 定位为全引擎唯一循环指标事件发布点（铁律：谁改循环账，谁必须经本上报器），
 * 改账动作全部经事件链落库，从不决定循环跑不跑——唯一触发者判定的结果交还调用方处置。
 * 门内（AgentLoopProcessor 开轮登记/收尾对账）与门外（AgentLoopCoordinator 工具回调/强停/子代理联动）
 * 共用本上报器。散落在宿主的循环业务规则（触发守卫、cid 临界区、CAS 令牌语义、子代理报告）
 * 全部收口本类，组合调用瘦身后 Store 的原子 CRUD 实现。
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class LoopDataReporter {

    private final ConversationStore conversationStore;
    private final MessageStore messageStore;
    private final MessageDataReporter messageDataReporter;
    private final EngineLock lockProvider;

    /**
     * 登记本轮等待工具清单（内存计数 + waitingToolIds 落库，经事件上报）。
     * 循环轮次 loopCount 的推进已迁移至触发点 CAS，此处不再负责计数。
     */
    public void registerRoundTools(AgentContext context, List<String> toolCallIds) {
        Long cid = context.getCid();
        String waitingIdsStr = toolCallIds.isEmpty() ? null : String.join(",", toolCallIds);

        // 1. 同步更新内存
        context.setCallToolCount(toolCallIds.size());

        // 2. 上报更新事件，委托持久化层写盘
        ConversationPatch updatePayload = new ConversationPatch(cid)
                .callToolCount(toolCallIds.size())
                .waitingToolIds(waitingIdsStr)
                .updateTime(LocalDateTime.now());
        context.publishEvent(AgentEventFactory.createConversationUpdate(context, updatePayload));

        log.debug("已通过事件总线上报下一轮工具轮次初始化: cid={}, count={}", cid, toolCallIds.size());
    }

    /**
     * 从等待屏障剔除一个已完成工具 ID 并判定是否全部完成（通过事件上报）。
     */
    public boolean updateWaitingToolToCompleted(AgentContext context, String toolCallId) {
        Long cid = context.getCid();
        log.debug("onToolCompleted 开始执行: cid={}, toolCallId={}", cid, toolCallId);

        // 会话级排他锁：将「扣减屏障 → 判定」收敛进单一临界区，锁与 publishEvent 内部使用的
        // 为同一把可重入锁（同 key 同源），此处外层加锁后事件链内重入无死锁。
        // 并行批多个完成回调交错执行时，多线程都可能读到"屏障已空"而重复拉起下一轮（双触发竞态）。
        // 锁内临界区串行保证只有真实扣掉最后一项的线程能看到空集合，天然唯一触发。
        Lock cidLock = lockProvider.getConversationDataLock(cid);
        cidLock.lock();
        try {
            // 1. 入口验证：回调对应的 id 必须仍在等待集合中。
            // 重复通知、迟到的滞留回调（如会话已被 forceReset 清空屏障后醒来）不携带扣减语义，
            // 直接丢弃，从入口切断"空窗期内不带扣减的回调看到空集合"的幻影触发路径
            if (!context.getWaitingToolIds().contains(toolCallId)) {
                log.warn("[触发守卫] 回调 id 不在等待集合中（重复/迟到/清理后滞留），丢弃本次回调: cid={}, toolCallId={}",
                        cid, toolCallId);
                return false;
            }

            // 2. 上报差量剔除工具事件（事件链在锁内同步完成写盘与内存回填）
            ConversationPatch updatePayload = new ConversationPatch(cid)
                    .waitingToolIds("-" + toolCallId);
            context.publishEvent(AgentEventFactory.createConversationUpdate(context, updatePayload));

            // 3. 判定：锁内临界区串行，只有真实扣掉最后一项的线程才能观察到空集合（唯一触发者）
            return context.getWaitingToolIds().isEmpty() && context.getWaitingSubCids().isEmpty();
        } finally {
            cidLock.unlock();
        }
    }

    /**
     * 写子代理分支报告消息并从父会话等待屏障剔除该子会话占位，判定是否全部完成（通过事件上报）。
     *
     * @param parentContext 父会话运行上下文
     * @param subCid        子会话 ID
     * @param errorDetail   错误描述详情（子智能体异常超时挂掉的报告），正常结束传 null
     * @return 若该子会话是最后一个完成的，返回 true；否则返回 false
     */
    public boolean updateWaitingSubAgentToCompleted(AgentContext parentContext, Long subCid, String errorDetail) {
        Long parentCid = parentContext.getCid();

        // 1. 构建子代理工作报告（正常结束取子会话最后一条消息内容，异常结束携带错误详情）
        String reportContent;
        if (errorDetail != null) {
            reportContent = buildReport(subCid, null, new RuntimeException(errorDetail));
        } else {
            Message lastMsg = messageStore.getByQuery(MessageQuery.builder()
                    .cid(subCid)
                    .sortField("id")
                    .sortDirection(SortDirection.DESC)
                    .build());
            String lastContent = lastMsg != null ? lastMsg.getContent() : null;
            reportContent = buildReport(subCid, lastContent, null);
        }

        messageDataReporter.createBranchMessage(parentContext, reportContent);

        // 会话级排他锁：与 updateWaitingToolToCompleted 对称的子 Agent 完成判定路径，
        // 防止多子 Agent 完成回调交错时重复拉起父会话下一轮
        Lock cidLock = lockProvider.getConversationDataLock(parentCid);
        cidLock.lock();
        try {
            // 2. 入口验证：子会话 id 必须仍在等待集合中（对称触发守卫）。
            // 僵死清理、forceReset 等路径已扣减过该 id 时，迟到的完成汇报直接丢弃
            if (!parentContext.getWaitingSubCids().contains(subCid)) {
                log.warn("[触发守卫] 子会话 id 不在等待集合中（重复/迟到/清理后滞留），丢弃本次汇报: parentCid={}, subCid={}",
                        parentCid, subCid);
                return false;
            }

            // 3. 上报差量剔除子会话事件（事件链在锁内同步完成写盘与内存回填）
            ConversationPatch updatePayload = new ConversationPatch(parentCid)
                    .waitingSubCids("-" + subCid);
            parentContext.publishEvent(AgentEventFactory.createConversationUpdate(parentContext, updatePayload));

            // 4. 判定：锁内临界区串行，只有真实扣掉最后一项的线程才能观察到空集合（唯一触发者）
            return parentContext.getWaitingToolIds().isEmpty() && parentContext.getWaitingSubCids().isEmpty();
        } finally {
            cidLock.unlock();
        }
    }

    /**
     * 清空循环运行状态账目（DB 侧清空 waitingToolIds/waitingSubCids/计数器，loopRunning=0）。
     */
    public void clearLoopState(AgentContext context) {
        Long cid = context.getCid();
        log.debug("强制重置 Loop 状态（通过事件上报）: cid={}", cid);

        // loopCount 重置为 0（无活动循环）：滞留的旧回调携带的 observed ≥1 与 0 不等，自动被 CAS 令牌拒绝；
        // 内存同步归零：防止内存残留轮次与 DB 漂移（下次用户发消息时 alignForNextStep 会置 1）
        context.setLoopCount(0);
        ConversationPatch updatePayload = new ConversationPatch(cid)
                .callToolCount(0)
                .loopCount(0)
                .waitingToolIds(null)
                .waitingSubCids(null)
                .loopRunning(0)
                .updateTime(LocalDateTime.now());
        context.publishEvent(AgentEventFactory.createConversationUpdate(context, updatePayload));
    }

    /**
     * 清空循环全部计量账目（Token 快照/工具计数/轮次/等待集/运行标志全量归零，经事件上报）。
     */
    public void clearLoopMetrics(AgentContext context) {
        Long cid = context.getCid();
        ConversationPatch updatePayload = new ConversationPatch(cid)
                .inputTokens(0L)
                .outputTokens(0L)
                .cachedTokens(0L)
                .callToolCount(0)
                .loopCount(0)
                .waitingToolIds(null)
                .waitingSubCids(null)
                .loopRunning(0)
                .updateTime(LocalDateTime.now());
        context.publishEvent(AgentEventFactory.createConversationUpdate(context, updatePayload));
        log.debug("已上报清空会话数据事件: cid={}", cid);
    }

    /**
     * 清空指定会话的全部历史消息与运行状态指标（落库物理删除消息 + 内存与 DB 指标归零）。
     */
    public void clearConversationData(AgentContext context) {
        Long cid = context.getCid();
        log.info("清空会话消息与指标数据: cid={}", cid);
        messageStore.deleteByQuery(MessageQuery.builder().cid(cid).build());
        clearLoopMetrics(context);
    }

    /**
     * 记录循环开跑账目：轮次推进 + loopRunning=1（内存与落库同事件完成，经事件上报）。
     */
    public void updateLoopStateToStarted(AgentContext context) {
        Long cid = context.getCid();

        // 循环轮次无条件推进（内存 + 落库同事件完成，供前端展示与 200 轮上限检查）
        int newLoopCount = context.getLoopCount() + 1;
        context.setLoopCount(newLoopCount);

        ConversationPatch updatePayload = new ConversationPatch(cid)
                .loopCount(newLoopCount)
                .loopRunning(1);
        context.publishEvent(AgentEventFactory.createConversationUpdate(context, updatePayload));
    }

    /**
     * 查库对账判定循环真完结后置 loopRunning=0（通过事件上报）。
     * 基于库内最新等待指标判定，避免内存视图滞后误判；为全引擎唯一完结收口方法，
     * 门内正常完结/压缩失败终结与门内收尾统一走此方法。
     */
    public void updateLoopRunningToFinished(AgentContext context, boolean hasException) {
        Long cid = context.getCid();
        Conversation conv = conversationStore.getById(cid);
        if (conv == null) {
            return;
        }
        boolean allWaitingEmpty = isBlank(conv.getWaitingToolIds()) && isBlank(conv.getWaitingSubCids());
        boolean trulyFinished = hasException || allWaitingEmpty;
        if (trulyFinished) {
            ConversationPatch updatePayload = new ConversationPatch(cid)
                    .loopRunning(0)
                    .updateTime(LocalDateTime.now());
            context.publishEvent(AgentEventFactory.createConversationUpdate(context, updatePayload));
        }
    }

    /**
     * 子代理工作报告构建
     */
    private String buildReport(Long subCid, String assistantOutput, Throwable runThrowable) {
        StringBuilder report = new StringBuilder();
        report.append("[子 Agent 工作报告]\n");
        report.append("子 Agent 会话 ID: ").append(subCid).append("\n");
        if (runThrowable != null) {
            report.append("运行状态: 失败 (").append(runThrowable.getClass().getSimpleName()).append(")\n");
            report.append("错误信息: ").append(runThrowable.getMessage()).append("\n");
        } else {
            report.append("运行状态: 已完成\n");
        }
        report.append("结果摘要:\n");
        report.append(assistantOutput != null && !assistantOutput.isEmpty() ? assistantOutput : "(无输出内容)");
        return report.toString();
    }

    /**
     * 记录一次真实的大模型调用，并更新当前上下文窗口的用量快照。
     */
    public void recordLlmCall(AgentContext context, CuteUsage usage, long callDurationMs) {
        Long cid = context.getCid();
        long inputTokens = 0;
        long outputTokens = 0;
        long cachedTokens = 0;

        if (usage != null) {
            inputTokens = usage.getInputTokens() != null ? usage.getInputTokens() : 0L;
            outputTokens = usage.getOutputTokens() != null ? usage.getOutputTokens() : 0L;
            cachedTokens = usage.getCachedTokens() != null ? usage.getCachedTokens() : 0L;
        }

        ConversationPatch tokenUpdate = new ConversationPatch(cid)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cachedTokens(cachedTokens);
        context.publishEvent(AgentEventFactory.createConversationUpdate(context, tokenUpdate));

        log.debug("已完成单次 LLM 调用 Token 快照广播: cid={}, prompt={}, completion={}, duration={}ms",
                cid, inputTokens, outputTokens, callDurationMs);
    }

    /**
     * 推送当前会话在内存中的最新 Token 窗口用量快照事件
     */
    public void publishWindowUsageSnapshot(AgentContext context) {
        ConversationPatch tokenUpdate = new ConversationPatch(context.getCid())
                .inputTokens(context.getInputTokens())
                .outputTokens(context.getOutputTokens())
                .cachedTokens(context.getCachedTokens());
        context.publishEvent(AgentEventFactory.createConversationUpdate(context, tokenUpdate));
    }

    private boolean isBlank(String val) {
        return val == null || val.isBlank();
    }
}
