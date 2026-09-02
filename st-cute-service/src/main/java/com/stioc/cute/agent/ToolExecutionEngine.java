package com.stioc.cute.agent;

import com.stioc.cute.agent.access.AgentLoopCoordinator;
import com.stioc.cute.agent.access.AgentContext;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.concurrent.locks.Lock;
import com.stioc.cute.platform.contract.ContractLock;
import java.io.File;
import com.stioc.cute.agent.event.AgentEventFactory;
import com.stioc.cute.tool.access.ToolExecutionContext;
import com.stioc.cute.platform.common.CommonThread;
import com.stioc.cute.hook.access.HookContext;
import com.stioc.cute.hook.access.HookEngineService;
import com.stioc.cute.hook.access.HookEventType;
import com.stioc.cute.message.access.MessageStatus;
import com.stioc.cute.security.access.PermissionEngine;
import com.stioc.cute.security.access.WorkspacePathResolver;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolNames;
import com.stioc.cute.tool.access.ToolRegistry;
import com.stioc.cute.llm.CuteToolCall;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 批量保序工具执行核心引擎
 * 支持只读并发分批、副作用串行、人在回路授权挂起、技能沙箱白名单门禁、生命周期 Hook 触发以及文件写锁
 */
@Slf4j
@Component
public class ToolExecutionEngine {

    @Resource
    private ToolRegistry toolRegistry;
    @Resource
    private PermissionEngine permissionEngine;
    @Resource
    private HookEngineService hookEngineService;
    @Resource
    private LlmWindowManager llmWindowManager;
    @Resource
    private WorkspacePathResolver workspacePathResolver;
    @Resource
    private ObjectProvider<AgentLoopCoordinator> agentLoopCoordinatorProvider;
    @Resource
    private ToolStatusHandler toolStatusHandler;

    /**
     * 批量保序分批执行工具集
     *
     * @param calls          大模型返回的工具调用列表
     * @param context        当前执行会话上下文
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
            boolean readOnly = tool != null && tool.isReadOnly();
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
                        () -> executeSingleToolSafely(call, context),
                        CommonThread.getVirtualThreadExecutor()))
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
            executeSingleToolSafely(call, context);
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
            AgentContext context) {

        executeSingleToolSafely(call, context, false);
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
            String result = "{\"error\": \"Tool execution crashed: " + e.getMessage() + "\"}";
            toolStatusHandler.onStatusUpdated(context, call.getId(), MessageStatus.FAILED, result);
            notifyToolCompleted(context, call.getId());
        }
    }

    /**
     * 异步批量触发工具执行引擎，供主 Processor 线程进行非阻塞快速返回
     */
    public void executeToolsBatchAsync(
            List<CuteToolCall> calls,
            AgentContext context) {
        CommonThread.submit(() -> {
            try {
                executeToolsBatch(calls, context);
            } catch (InterruptedException e) {
                log.warn("异步批量执行工具被中断: cid={}", context.getCid(), e);
                Thread.currentThread().interrupt();
            }
        });
    }

    public void resumeApprovedToolAsync(CuteToolCall call, AgentContext context) {
        CommonThread.submit(() -> {
            executeSingleToolSafely(call, context, true);
        });
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
        Long cid = context.getCid();

        log.debug("执行工具: {}, 参数: {}", name, argumentsJson);

        // 1. 解析参数
        Map<String, Object> args = new HashMap<>();
        try {
            args = JSON.parseObject(argumentsJson, Map.class);
        } catch (Exception e) {
            log.warn("解析工具参数 JSON 失败: {}", argumentsJson);
        }

        // 2. 提取参数中的 filepath/path 供写锁与生命周期校验 Hook 使用
        String filePath = null;
        if (args != null) {
            if (args.containsKey("path")) {
                filePath = String.valueOf(args.get("path"));
            } else if (args.containsKey("filepath")) {
                filePath = String.valueOf(args.get("filepath"));
            }
        }

        // 3. 触发 on_tool_call 生命周期拦截挂钩
        try {
            HookContext hookCtx = HookContext.builder()
                    .cid(cid)
                    .agentContext(context)
                    .toolCallId(toolCallId)
                    .toolName(name)
                    .toolArgs(argumentsJson)
                    .filePath(filePath)
                    .build();
            hookEngineService.triggerHook(HookEventType.ON_TOOL_CALL, hookCtx);
        } catch (Exception e) {
            log.error("阻断型 on_tool_call Hook 拦截成功，拒绝执行该工具, 原因: {}", e.getMessage());
            String result = "{\"error\": \"工具执行前被生命周期 Hook 拦截阻断: " + e.getMessage() + "\"}";

            // Hook 阻断发生在工具落库前，需要显式写入失败状态并通知前端。
            toolStatusHandler.onStatusUpdated(context, toolCallId, MessageStatus.FAILED, result);
            notifyToolCompleted(context, toolCallId);
            return;
        }

        // 4. 评估安全权限
        String decision = permissionAlreadyApproved ? "ALLOW" : permissionEngine.evaluate(name, args, context);
        log.debug("工具权限评估结果: {} -> {}", name, decision);

        boolean isAllowed = false;
        String denyReason = null;

        if ("ALLOW".equalsIgnoreCase(decision)) {
            isAllowed = true;
        } else if (decision != null && decision.startsWith("DENY:")) {
            isAllowed = false;
            denyReason = decision.substring(5);
        } else if ("ASK".equalsIgnoreCase(decision)) {
            // 需要人在回路审批：持久化 WAITING_APPROVAL 状态后直接返回，不挂起线程。
            // 审批机制直接依赖 WAITING_APPROVAL 的 MESSAGE_UPDATE 事件推送前端展示审批弹窗。
            toolStatusHandler.onStatusUpdated(context, toolCallId, MessageStatus.WAITING_APPROVAL, null);
            log.debug("工具 {} 需要人工审批，已持久化 WAITING_APPROVAL 状态，等待审批事件驱动继续执行", name);
            // 审批完成后由 resumeToolAfterApproval 重新执行并触发 notifyToolCompleted。
            return;
        } else {
            isAllowed = false;
            denyReason = "未知安全审查状态，操作被强制拒绝";
        }

        // 5. 若权限受限被拦截
        if (!isAllowed) {
            String result = "{\"error\": \"" + (denyReason != null ? denyReason : "权限受限，被拦截") + "\"}";
            log.warn("工具 {} 被拦截拒绝，原因: {}", name, denyReason);

            toolStatusHandler.onStatusUpdated(context, toolCallId, MessageStatus.FAILED, result);
            notifyToolCompleted(context, toolCallId);
            return;
        }

        // 6. 正常被允许执行，开始运行
        toolStatusHandler.onStatusUpdated(context, toolCallId, MessageStatus.RUNNING, null);

        String result;
        boolean success = false;
        CuteTool tool = toolRegistry.getTool(name, context);
        if (tool == null) {
            result = "{\"error\": \"找不到该工具: " + name + "\"}";
        } else {
            // 6.1 写文件工具，获取文件并发排他写锁
            boolean isWriteTool = tool.isWriteTool();
            Lock fileLock = null;
            if (isWriteTool && filePath != null && !filePath.isBlank()) {
                String lockKey;
                try {
                    lockKey = "file:" + new File(filePath).getCanonicalPath();
                } catch (Exception e) {
                    lockKey = "file:" + filePath;
                }
                fileLock = ContractLock.FILE_STRIPED.get(lockKey);
                fileLock.lock();
            }

            try {
                // 核心真正执行
                ToolExecutionContext execContext = new ToolExecutionContext(context, toolCallId);
                result = tool.execute(args, execContext);
                success = result != null && !(result.trim().startsWith("{\"error\":") && result.trim().endsWith("}"));

                // 6.3 执行成功后触发阻断型完成 Hook，失败时把校验错误返回给下一轮模型。
                if (success) {
                    try {
                        HookContext hookCtx = HookContext.builder()
                                .cid(cid)
                                .agentContext(context)
                                .toolCallId(toolCallId)
                                .toolName(name)
                                .toolArgs(argumentsJson)
                                .filePath(filePath)
                                .toolResult(result)
                                .build();
                        hookEngineService.triggerHook(HookEventType.ON_TOOL_COMPLETE, hookCtx);
                    } catch (Exception e) {
                        log.warn("阻断型 on_tool_complete Hook 执行失败！开始重写工具返回以激发模型自我修复, 错误: {}", e.getMessage());
                        success = false;
                        result = "{\"error\": \"工具执行后触发的生命周期编译校验失败，错误详情如下：\\n" + e.getMessage() + "\\n请根据以上报错信息修正刚才修改的代码文件。\"}";
                    }
                }

                // 6.4 如果是只读性质的文件工具，将其绝对路径登记进 context.getReadFiles()（仅登记路径占位，无哈希）。
                // ReadFileTool 执行体内部已记录带内容哈希的完整条目；此处兜底登记防止引擎层与工具层路径解析差异导致的漏记，
                // 占位值为空哈希（门禁将因失配要求重读，安全方向保守）
                if (success && ToolNames.READ_FILE.equalsIgnoreCase(name)) {
                    String pathVal = (String) args.get("path");
                    if (StringUtils.hasText(pathVal)) {
                        try {
                            // 与 ReadFileTool/ModifyFileTool 统一走 WorkspacePathResolver 解析，
                            // 保证相对路径以项目根/worktree 为基准，而非 JVM 工作目录，避免门禁路径基准不一致
                            String absPath = workspacePathResolver.resolvePath(pathVal, context).toAbsolutePath().normalize().toString();
                            context.getReadFiles().putIfAbsent(absPath, "");
                        } catch (Exception ex) {
                            // ignore
                        }
                    }
                }


            } catch (Exception e) {
                log.error("工具 {} 执行崩溃", name, e);
                result = "{\"error\": \"工具执行发生崩溃: " + e.getMessage() + "\"}";
            } finally {
                if (fileLock != null) {
                    fileLock.unlock();
                }
            }
        }

        // 7. 对工具输出执行全局 2000 行物理被动兜底截断保护（如果是只读文件读取工具则 100% 免除折叠）
        String compactedResult = result;
        String beforeCompact = null;
        if (result != null && !ToolNames.READ_FILE.equalsIgnoreCase(name)) {
            String[] lines = result.split("\\r?\\n");
            if (lines.length > 2000) {
                beforeCompact = result;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 2000; i++) {
                    sb.append(lines[i]).append("\n");
                }
                sb.append("\n... [系统检测到输出数据过长，已在 2000 行限制处执行安全截断] ...\n");
                compactedResult = sb.toString();
            }
        }

        // 8. 提取特定工具（如 load_attachment）产生的附件元数据
        String attachmentsJson = null;
        if (success && ToolNames.LOAD_ATTACHMENT.equalsIgnoreCase(name) && StringUtils.hasText(result)) {
            try {
                JSONObject resObj = JSON.parseObject(result.trim());
                if (resObj != null && "success".equalsIgnoreCase(resObj.getString("status"))) {
                    String attPath = resObj.getString("path");
                    String attName = resObj.getString("name");
                    Long attSize = resObj.getLong("size");
                    String attMime = resObj.getString("mimeType");
                    if (StringUtils.hasText(attPath)) {
                        JSONArray attArr = new JSONArray();
                        JSONObject attObj = new JSONObject();
                        attObj.put("path", attPath);
                        attObj.put("name", attName != null ? attName : attPath);
                        attObj.put("size", attSize != null ? attSize : 0L);
                        attObj.put("mimeType", attMime);
                        attArr.add(attObj);
                        attachmentsJson = attArr.toJSONString();
                    }
                }
            } catch (Exception e) {
                log.warn("解析 LOAD_ATTACHMENT 工具附件元数据失败", e);
            }
        }

        // 9. 推送并持久化工具结果状态
        MessageStatus finalStatus = success ? MessageStatus.SUCCESS : MessageStatus.FAILED;
        toolStatusHandler.onStatusUpdated(context, toolCallId, finalStatus, compactedResult, beforeCompact, attachmentsJson);

        // 9. 从 waitingToolIds 中移除本工具。
        //    invoke_subagent 工具本身也在此处算作完成（子 Agent 异步运行，其 subCid 已单独进入 waitingSubCids）。
        //    若 waitingToolIds 和 waitingSubCids 均已清空，说明本轮全部工作完成，
        //    通知前端 Loop 仍在等待子 Agent（此时 LOOP_RUNNING 状态由子 Agent 完成时驱动关闭）；
        //    若两者已同时清空则不在此触发下一轮（由 AgentProcessor while 循环自然衔接）。
        notifyToolCompleted(context, toolCallId);

    }

    private void notifyToolCompleted(AgentContext context, String toolCallId) {
        agentLoopCoordinatorProvider.getObject().notifyToolCompleted(context, toolCallId);
    }

    private void cancelRemainingTools(AgentContext context, List<CuteToolCall> batch) {
        for (CuteToolCall call : batch) {
            toolStatusHandler.onStatusUpdated(context, call.getId(), MessageStatus.CANCELED, "{\"error\": \"用户已取消执行。\"}");
        }
    }


    private record ToolExecutionBatch(
            List<CuteToolCall> calls,
            boolean readOnly
    ) {}

}
