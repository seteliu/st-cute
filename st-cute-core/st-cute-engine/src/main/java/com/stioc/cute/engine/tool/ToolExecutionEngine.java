package com.stioc.cute.engine.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.common.AgentEngineCommonThread;
import com.stioc.cute.engine.common.AgentEngineLock;
import com.stioc.cute.engine.event.AgentEventFactory;
import com.stioc.cute.engine.hook.HookPayload;
import com.stioc.cute.engine.hook.HookType;
import com.stioc.cute.engine.llm.types.CuteToolCall;
import com.stioc.cute.engine.loop.ApprovalRuleWriter;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.loop.core.AgentContextManager;
import com.stioc.cute.engine.loop.core.AgentLoopCoordinator;
import com.stioc.cute.engine.loop.message.MessageDataReporter;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessagePatch;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.tool.ToolCallCodec;
import com.stioc.cute.engine.tool.types.ToolApprovalRequest;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolResult;
import com.stioc.cute.engine.tool.types.ToolPermissionDecision;
import com.stioc.cute.engine.tool.types.ToolPermissionVerdict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;

/**
 * 批量保序工具执行核心引擎
 * 支持只读并发分批、副作用串行、人在回路授权挂起、技能沙箱白名单门禁、生命周期 Hook 触发以及写工具并发排他锁
 */
@Slf4j
@RequiredArgsConstructor
public class ToolExecutionEngine {

    private final ToolRegistry toolRegistry;
    private final ToolGuard toolGuard;
    private final MessageDataReporter messageDataReporter;
    private final AgentContextManager agentContextManager;
    private final MessageStore messageStore;
    private final Optional<ApprovalRuleWriter> approvalRuleWriter;

    private AgentLoopCoordinator agentLoopCoordinator;

    public void bindAgentLoopCoordinator(AgentLoopCoordinator coordinator) {
        this.agentLoopCoordinator = coordinator;
    }

    /**
     * 批量保序分批执行工具集
     *
     * @param calls   大模型返回的工具调用列表
     * @param context 当前执行会话上下文
     */
    public void executeToolsBatch(
            List<CuteToolCall> calls,
            AgentContext context) throws InterruptedException {

        // 批执行不再返回 ToolResponse；工具状态、DB 落库和下一轮 Loop 推进都由事件回调驱动。
        for (ToolExecutionBatch batch : planExecutionBatches(calls, context)) {
            ensureNotCanceled(context, batch.calls());
            if (batch.readOnly()) {
                executeReadOnlyBatch(batch.calls(), context);
            } else {
                executeSerialBatch(batch.calls(), context);
            }
        }
    }

    /**
     * 保持模型原始调用顺序：连续只读工具合并为并发批；写/副作用工具单独成串行批。
     */
    private List<ToolExecutionBatch> planExecutionBatches(List<CuteToolCall> calls, AgentContext context) {
        List<ToolExecutionBatch> batches = new ArrayList<>();
        List<CuteToolCall> readOnlyCalls = new ArrayList<>();

        for (CuteToolCall call : calls) {
            CuteTool tool = toolRegistry.getTool(call.getName(), context);
            boolean readOnly = tool != null && tool.getAccessLevel() == ToolAccessLevel.READ;
            if (readOnly) {
                readOnlyCalls.add(call);
                continue;
            }

            if (!readOnlyCalls.isEmpty()) {
                batches.add(new ToolExecutionBatch(new ArrayList<>(readOnlyCalls), true));
                readOnlyCalls.clear();
            }
            batches.add(new ToolExecutionBatch(List.of(call), false));
        }

        if (!readOnlyCalls.isEmpty()) {
            batches.add(new ToolExecutionBatch(readOnlyCalls, true));
        }

        return batches;
    }

    private void executeReadOnlyBatch(
            List<CuteToolCall> calls,
            AgentContext context) throws InterruptedException {
        log.debug("并发执行只读工具批次: {}", calls.stream().map(CuteToolCall::getName).collect(Collectors.joining(",")));

        List<CompletableFuture<Void>> futures = calls.stream()
                .map(call -> CompletableFuture.runAsync(
                        () -> executeSingleToolSafely(call, context, false),
                        AgentEngineCommonThread.getVirtualThreadExecutor()))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException e) {
            log.warn("等待只读工具批次执行时被中断，正在取消未完成的任务。");
            futures.forEach(f -> f.cancel(true));
            throw e;
        } catch (Exception e) {
            log.error("只读工具批次执行发生未预期异常", e);
        }
    }

    private void executeSerialBatch(
            List<CuteToolCall> calls,
            AgentContext context) throws InterruptedException {
        for (CuteToolCall call : calls) {
            ensureNotCanceled(context, List.of(call));
            log.debug("串行执行副作用工具: {}", call.getName());
            executeSingleToolSafely(call, context, false);
        }
    }

    private void ensureNotCanceled(
            AgentContext context,
            List<CuteToolCall> remainingCalls) throws InterruptedException {
        if (!context.isCanceled()) {
            return;
        }
        cancelRemainingTools(context, remainingCalls);
        throw new InterruptedException("用户取消了工具执行。");
    }

    private void executeSingleToolSafely(
            CuteToolCall call,
            AgentContext context,
            boolean permissionAlreadyApproved) {

        try {
            executeSingleTool(call, context, permissionAlreadyApproved);
        } catch (Exception e) {
            // 兜底保护：单工具执行闭环理论上会自行落终态，这里防止未知异常让工具卡在非终态。
            log.error("Tool {} crashed outside the normal execution guard", call.getName(), e);
            String result = ToolResult.error("Tool execution crashed: " + e.getMessage());
            messageDataReporter.updateToolStatus(context, call.getId(), MessageStatus.FAILED, result);
            notifyToolCompleted(context, call.getId());
        }
    }

    /**
     * 异步批量触发工具执行引擎，供主 Processor 线程进行非阻塞快速返回
     */
    public void executeToolsBatchAsync(
            List<CuteToolCall> calls,
            AgentContext context) {
        AgentEngineCommonThread.submit(() -> {
            try {
                executeToolsBatch(calls, context);
            } catch (InterruptedException e) {
                log.warn("异步批量执行工具被中断: cid={}", context.getCid(), e);
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * 审核工具执行决策并分流执行/拒绝流向（人在回路决策闭环）
     */
    public boolean approveTool(ToolApprovalRequest request) {
        if (request == null || request.cid() == null || request.toolCallId() == null) {
            log.warn("审批请求无效或缺少必要标识: {}", request);
            return false;
        }

        Long cid = request.cid();
        String toolCallId = request.toolCallId();
        String decision = request.decision();
        boolean alwaysAllow = request.alwaysAllow();
        String toolName = request.toolName();
        String contentPattern = request.contentPattern();
        String customArgOverride = request.customArgOverride();

        AgentContext context = agentContextManager.getOrCreateContext(cid);
        String workspaceId = context != null ? context.getWorkspaceId() : null;

        boolean isAllowDecision = ToolPermissionDecision.ALLOW.name().equalsIgnoreCase(decision);
        if (alwaysAllow && isAllowDecision
                && StringUtils.isNotBlank(toolName) && StringUtils.isNotBlank(contentPattern)
                && approvalRuleWriter.isPresent()) {
            try {
                approvalRuleWriter.get().writeAllowRule(toolName, contentPattern, workspaceId);
                log.info("用户选择总是放行，已写入本地权限规则: tool={}, pattern={}", toolName, contentPattern);
            } catch (Exception e) {
                log.error("写入总是放行权限规则失败", e);
            }
        }

        Message toolMsg = messageStore.getByQuery(MessageQuery.builder()
                .cid(cid)
                .callId(toolCallId)
                .build());
        if (toolMsg == null) {
            log.warn("审批失败：会话 {} 中找不到 toolCallId={} 对应的工具消息", cid, toolCallId);
            return false;
        }

        // 状态校验：仅允许对仍处于待审批状态的工具消息执行审批决策。
        // 防止已被超时清理（CANCELED）或已进入其他状态的工具被事后审批（僵尸审批），
        // 该场景下恢复执行会绕过屏障判定产生不可预期的调度错乱。
        if (MessageStatus.WAITING_APPROVAL != toolMsg.getStatus()) {
            log.warn("审批拒绝：会话 {} 中 toolCallId={} 的当前状态为 {}，已不是待审批状态，忽略本次审批决策",
                    cid, toolCallId, toolMsg.getStatus());
            return false;
        }

        if (!isAllowDecision) {
            if (context != null) {
                rejectToolAndContinue(context, toolCallId);
            }
            log.info("工具审批已拒绝: toolName={}, toolCallId={}", toolName, toolCallId);
            return true;
        }

        String originalArgs = extractArgsFromToolMsg(toolMsg);
        String finalArgs = originalArgs;
        if (StringUtils.isNotBlank(customArgOverride)) {
            String trimmedOverride = customArgOverride.trim();
            try {
                JSON.parse(trimmedOverride);
                finalArgs = trimmedOverride;
                // 参数重写走统一编解码器：单对象读写契约与解析侧同源
                CuteToolCall original = ToolCallCodec.parseSingle(toolMsg.getToolCalls());
                CuteToolCall updated = CuteToolCall.builder()
                        .id(original.getId())
                        .name(original.getName())
                        .arguments(finalArgs)
                        .build();
                String updatedToolCalls = ToolCallCodec.writeSingle(updated);

                MessagePatch patch = new MessagePatch(toolMsg.getId())
                        .cid(cid)
                        .toolCalls(updatedToolCalls);
                context.publishEvent(AgentEventFactory.createMessageUpdate(context, patch));
                log.info("用户审批重写了工具参数，已写回更新消息 tool_calls: msgId={}", toolMsg.getId());
            } catch (Exception e) {
                log.warn("customArgOverride 格式非法，放弃覆盖 tool_calls，使用原始参数: override={}", customArgOverride, e);
            }
        }
        String finalToolName = extractNameFromToolMsg(toolMsg);
        if (context != null) {
            CuteToolCall pendingToolCall = CuteToolCall.builder()
                    .id(toolCallId)
                    .name(finalToolName)
                    .arguments(finalArgs)
                    .build();
            resumeApprovedToolAsync(pendingToolCall, context);
        }
        log.info("工具审批已通过: toolName={}, toolCallId={}", finalToolName, toolCallId);
        return true;
    }

    private void resumeApprovedToolAsync(CuteToolCall call, AgentContext context) {
        AgentEngineCommonThread.submit(() -> {
            executeSingleToolSafely(call, context, true);
        });
    }

    private void rejectToolAndContinue(AgentContext context, String toolCallId) {
        messageDataReporter.updateToolStatus(context, toolCallId, MessageStatus.REJECTED, "{\"error\": \"Permission denied by user.\"}");
        notifyToolCompleted(context, toolCallId);
    }

    private String extractNameFromToolMsg(Message toolMsg) {
        return parseToolCallOfToolMsg(toolMsg).getName();
    }

    private String extractArgsFromToolMsg(Message toolMsg) {
        String args = parseToolCallOfToolMsg(toolMsg).getArguments();
        return args != null ? args : "{}";
    }

    private CuteToolCall parseToolCallOfToolMsg(Message toolMsg) {
        return ToolCallCodec.parseSingle(toolMsg.getToolCalls());
    }

    /**
     * 判定工具返回是否为标准错误契约（顶层 JSON 对象且含 error 字段）。
     * null 返回视为失败；非 JSON 文本或不含 error 字段的 JSON 均视为正常内容。
     */
    private boolean isErrorPayload(String payload) {
        if (payload == null) {
            return true;
        }
        String trimmed = payload.trim();
        if (!trimmed.startsWith("{")) {
            return false;
        }
        try {
            JSONObject obj = JSON.parseObject(trimmed);
            return obj != null && obj.containsKey(ToolResult.ERROR_KEY);
        } catch (Exception e) {
            // 非 JSON 文本按正常内容处理（工具允许返回纯文本结果）
            return false;
        }
    }

    /**
     * 单工具执行闭环引擎（Hook -> 权限审核 -> 人在回路 -> 技能沙箱 -> 写锁 -> 运行 -> 后置Hook -> 大日志过滤）
     */
    private void executeSingleTool(
            CuteToolCall call,
            AgentContext context,
            boolean permissionAlreadyApproved) {

        String toolCallId = call.getId();
        String name = call.getName();
        String argumentsJson = call.getArguments();

        log.debug("执行工具: {}, 参数: {}", name, argumentsJson);

        // 1. 解析参数
        Map<String, Object> args = new HashMap<>();
        try {
            args = JSON.parseObject(argumentsJson, Map.class);
        } catch (Exception e) {
            log.warn("解析工具参数 JSON 失败: {}", argumentsJson);
        }
        if (args == null) {
            args = new HashMap<>();
        }

        // 2. 获取工具实例；未知工具在进入权限评估与 Hook 链路前即早失败，
        // 防止不存在的工具被挂起进入人在回路审批、等待一个永远无法执行的审批决策
        CuteTool tool = toolRegistry.getTool(name, context);
        if (tool == null) {
            log.warn("工具 {} 未注册，引擎直接拦截", name);
            messageDataReporter.updateToolStatus(context, toolCallId, MessageStatus.FAILED,
                    ToolResult.error("找不到该工具: " + name));
            notifyToolCompleted(context, toolCallId);
            return;
        }
        if (!tool.isAvailable(context)) {
            log.warn("工具 {} 在当前会话上下文不可用，引擎直接拦截", name);
            messageDataReporter.updateToolStatus(context, toolCallId, MessageStatus.FAILED,
                    ToolResult.error("工具 [" + name + "] 在当前会话上下文中不可用。"));
            notifyToolCompleted(context, toolCallId);
            return;
        }
        String targetResource = tool.getTargetResource(args);

        // 3. 触发 on_tool_call 生命周期拦截挂点（宿主 HookListener 抛异常即拦截）
        try {
            HookPayload hookPayload = HookPayload.builder()
                    .toolCallId(toolCallId)
                    .toolName(name)
                    .toolArgs(argumentsJson)
                    .targetResource(targetResource)
                    .build();
            context.triggerHook(HookType.ON_TOOL_CALL, hookPayload);
        } catch (Exception e) {
            log.error("阻断型 on_tool_call Hook 拦截成功，拒绝执行该工具, 原因: {}", e.getMessage());
            String result = ToolResult.error("工具执行前被生命周期 Hook 拦截阻断: " + e.getMessage());

            // Hook 阻断发生在工具落库前，需要显式写入失败状态并通知前端。
            messageDataReporter.updateToolStatus(context, toolCallId, MessageStatus.FAILED, result);
            notifyToolCompleted(context, toolCallId);
            return;
        }

        // 4. 评估安全权限：已审批通过或声明免审批的工具（如纯调度工具、无宿主物理副作用）天然放行，无需穿透宿主审批
        ToolPermissionVerdict verdict;
        if (permissionAlreadyApproved || tool.isApprovalExempt()) {
            verdict = ToolPermissionVerdict.allow();
        } else {
            verdict = toolGuard.evaluate(name, args, context);
        }

        if (verdict == null) {
            verdict = ToolPermissionVerdict.deny("未知安全审查状态，操作被强制拒绝");
        }
        log.debug("工具权限评估结果: {} -> {}", name, verdict);

        if (verdict.isAsk()) {
            // 需要人在回路审批：持久化 WAITING_APPROVAL 状态后直接返回，不挂起线程。
            // 审批机制直接依赖 WAITING_APPROVAL 的 MESSAGE_UPDATE 事件推送前端展示审批弹窗。
            messageDataReporter.updateToolStatus(context, toolCallId, MessageStatus.WAITING_APPROVAL, null);
            log.debug("工具 {} 需要人工审批，已持久化 WAITING_APPROVAL 状态，等待审批事件驱动继续执行", name);
            // 审批完成后由 resumeToolAfterApproval 重新执行并触发 notifyToolCompleted。
            return;
        }

        // 5. 若权限受限被拦截
        if (verdict.isDeny()) {
            String denyReason = StringUtils.isNotBlank(verdict.reason()) ? verdict.reason() : "权限受限，被拦截";
            String result = ToolResult.error(denyReason);
            log.warn("工具 {} 被拦截拒绝，原因: {}", name, denyReason);

            messageDataReporter.updateToolStatus(context, toolCallId, MessageStatus.FAILED, result);
            notifyToolCompleted(context, toolCallId);
            return;
        }

        // 6. 正常被允许执行，开始运行
        messageDataReporter.updateToolStatus(context, toolCallId, MessageStatus.RUNNING, null);

        String result;
        boolean success;
        // 异常路径（执行崩溃）不会经过赋值点，预初始化为 null 保证确定性赋值
        String attachmentsJson = null;

        // 6.1 非只读工具执行，获取并发排他锁（WRITE 与 SENSITIVE 均有外部副作用，统一持锁）
        boolean needsWriteLock = tool.getAccessLevel() != ToolAccessLevel.READ;
        String lockKey = needsWriteLock ? tool.getLockKey(args) : null;
        Lock writeLock = null;
        if (lockKey != null && !lockKey.isBlank()) {
            writeLock = AgentEngineLock.WRITE_TOOL_STRIPED.get(lockKey);
            writeLock.lock();
        }

        try {
            // 核心真正执行
            ToolExecutionContext execContext = new ToolExecutionContext(context, toolCallId);
            result = tool.execute(args, execContext);
            attachmentsJson = execContext.getAttachments();
            // 统一错误契约判定：仅顶层含 error 字段的标准错误 JSON 判为失败，
            // 以 {"error":... 样式开头的正常文本内容不再被字符串前缀嗅探误伤
            success = !isErrorPayload(result);

            // 6.3 执行成功后触发阻断型完成挂点（宿主 HookListener），失败时把校验错误返回给下一轮模型。
            if (success) {
                try {
                    HookPayload hookPayload = HookPayload.builder()
                            .toolCallId(toolCallId)
                            .toolName(name)
                            .toolArgs(argumentsJson)
                            .targetResource(targetResource)
                            .toolResult(result)
                            .build();
                    context.triggerHook(HookType.ON_TOOL_COMPLETE, hookPayload);
                } catch (Exception e) {
                    log.warn("阻断型 on_tool_complete Hook 执行失败！开始重写工具返回以激发模型自我修复, 错误: {}", e.getMessage());
                    success = false;
                    result = ToolResult.error("工具执行后触发的生命周期后置校验失败，错误详情如下：\n" + e.getMessage()
                            + "\n请根据以上报错信息进行修正或重新尝试。");
                }
            }

        } catch (Exception e) {
            log.error("工具 {} 执行崩溃", name, e);
            result = ToolResult.error("工具执行发生崩溃: " + e.getMessage());
            success = false;
        } finally {
            if (writeLock != null) {
                writeLock.unlock();
            }
        }

        // 7. 推送并持久化工具结果状态
        MessageStatus finalStatus = success ? MessageStatus.SUCCESS : MessageStatus.FAILED;
        messageDataReporter.updateToolStatus(context, toolCallId, finalStatus, result, attachmentsJson);

        // 8. 从 waitingToolIds 中移除本工具。
        notifyToolCompleted(context, toolCallId);
    }

    private void notifyToolCompleted(AgentContext context, String toolCallId) {
        agentLoopCoordinator.notifyToolCompleted(context, toolCallId);
    }

    private void cancelRemainingTools(AgentContext context, List<CuteToolCall> batch) {
        for (CuteToolCall call : batch) {
            messageDataReporter.updateToolStatus(context, call.getId(), MessageStatus.CANCELED, "{\"error\": \"用户已取消执行。\"}");
        }
    }

    private record ToolExecutionBatch(
            List<CuteToolCall> calls,
            boolean readOnly
    ) {}
}
