package com.stioc.cute.agent;

import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.agent.event.AgentEventFactory;
import com.stioc.cute.conversation.access.ConversationEntity;
import com.mybatisflex.core.util.UpdateEntity;
import com.stioc.cute.conversation.access.ConversationService;
import com.stioc.cute.message.access.MessageStateMachineService;
import com.stioc.cute.hook.access.HookContext;
import com.stioc.cute.hook.access.HookEngineService;
import com.stioc.cute.hook.access.HookEventType;
import com.stioc.cute.platform.contract.Provider;
import com.stioc.cute.provider.ProviderService;

import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolRegistry;
import com.stioc.cute.tool.access.ToolNames;
import lombok.extern.slf4j.Slf4j;
import com.stioc.cute.llm.*;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.Resource;

import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 智能体 ReAct 核心执行处理器，负责控制多轮 ReAct 智能体推理与核心循环调度。
 * 本执行器采用由单一数据源（Message 列表末尾状态）驱动的状态机模式。
 */
@Slf4j
@Service
public class AgentProcessor {

    @Resource
    private ProviderService providerService;
    @Resource
    private ToolRegistry toolRegistry;
    @Resource
    private HookEngineService hookEngineService;
    @Resource
    private ConversationService conversationService;
    @Resource
    private LlmWindowManager llmWindowManager;
    @Resource
    private ChatOptionsFactory chatOptionsFactory;
    @Resource
    private ToolExecutionEngine toolExecutionEngine;
    @Resource
    private MessageStateMachineService messageStateMachineService;
    @Resource
    private ToolStatusHandler toolStatusHandler;
    @Resource
    private AgentTokenUsageRecorder agentTokenUsageRecorder;

    /**
     * 执行多轮 ReAct 自适应智能体循环（状态机驱动骨架）
     */
    public void executeLoop(AgentContext context) {
        Long cid = context.getCid();
        log.debug("Agent: 开始执行 ReAct 循环, cid: {}", cid);

        // 1. 上下文准备
        prepareRuntimeContext(context);

        // 触发生命周期挂钩
        triggerHook(HookEventType.ON_CONTEXT_START, context);

        // 本轮推理结果
        CuteChatResponse stepRsponse = null;
        Throwable runThrowable = null;

        try {
            // 2. 上下文窗口管理（压缩及滑窗裁剪）
            boolean compressSuccess = llmWindowManager.manageContextWindowSync(context);
            if (!compressSuccess) {
                return;
            }

            // 3. 执行状态机生命周期对齐，自适应转移并获取活跃助手消息 ID
            Long activeAssistantMsgId = messageStateMachineService.alignForNextStep(context);
            if (activeAssistantMsgId == null) {
                log.warn("executeLoop: 状态机对齐未生成活跃助理消息，直接退出: cid={}", cid);
                return;
            }

            // 4. 运行单轮推理
            stepRsponse = executeReActStep(context);

            // 无后续工具调用（正常推理结束），在此统一更新该 ASSISTANT 为 SUCCESS
            if (stepRsponse.getToolCalls().isEmpty()) {
                messageStateMachineService.markAssistantSuccess(context, stepRsponse);
            }
        } catch (InterruptedException e) {
            runThrowable = e;
            toolStatusHandler.cancelAllRemainingTools(context, "用户中断了智能体执行。");
            messageStateMachineService.markInterrupted(context, e);
        } catch (AgentMeltdownException e) {
            runThrowable = e;
            log.warn("Agent: 触发熔断保护: {}", e.getMessage());
            toolStatusHandler.cancelAllRemainingTools(context, "连续多次未知工具调用触发熔断保护。");
            messageStateMachineService.markMeltdown(context);
        } catch (Exception e) {
            runThrowable = e;
            log.error("executeLoop 发生未预期异常: cid={}", cid, e);
            toolStatusHandler.cancelAllRemainingTools(context, "执行异常崩溃: " + e.getMessage());
            messageStateMachineService.markException(context, e);
        } finally {
            // 收尾与副作用处理
            finalizeContext(context, stepRsponse, runThrowable);
        }
    }

    /**
     * 准备本轮运行上下文，登记当前执行线程，推进循环轮次，并更新 loopRunning 为 true。
     * 循环轮次 loopCount：每次循环启动无条件 +1 并落库——用户消息入口已置 0（主动发起新一轮，
     * 本处 +1 后从 1 开始计数），工具完成回调触发的自循环也在此统一推进，
     * 避免依赖消息角色判定轮次来源（USER 角色消息未必是用户真实发起，如上下文压缩产生的消息）。
     * 注意：canceled 状态由 HTTP 入口层（用户主动发消息时）清除，此处不再重置，
     * 避免 cancel 信号在进入执行主体前被意外抹掉。
     */
    private void prepareRuntimeContext(AgentContext context) {
        Long cid = context.getCid();
        context.setActiveThread(Thread.currentThread());

        // 循环轮次无条件推进（内存 + 落库同事件完成，供前端展示与 200 轮上限检查）
        int newLoopCount = context.getLoopCount() + 1;
        context.setLoopCount(newLoopCount);

        ConversationEntity updatePayload = UpdateEntity.of(ConversationEntity.class);
        updatePayload.setId(cid);
        updatePayload.setLoopCount(newLoopCount);
        updatePayload.setLoopRunning(1);
        context.publishEvent(AgentEventFactory.createConversationUpdate(context, updatePayload));
    }

    /**
     * 执行单轮 ReAct 自适应推理步骤。
     *
     * @param context 当前会话上下文
     * @return 本轮推理的完整响应（正文、思考链、待执行工具调用）
     * @throws InterruptedException 若用户在中途取消或中断执行
     */
    private CuteChatResponse executeReActStep(AgentContext context) throws InterruptedException {
        // 获取当前会话上下文、LLM 客户端以及运行配置信息
        Long cid = context.getCid();
        CuteChat cuteChat = providerService.getCuteChat(context);
        Provider activeConfig = providerService.getProviderConfigForContext(context);
        // 迭代上限默认 200，防止无限 ReAct 循环导致 Token 溢出或额度超支
        final int maxIterations = 200;

        // 触发生命周期挂钩，表明单轮推理步骤开始
        triggerHook(HookEventType.ON_LOOP_START, context);

        // 1. 中断校验：检查用户是否已手动中止当前运行
        if (context.isCanceled()) {
            throw new InterruptedException("用户中断了智能体执行。");
        }

        // 循环轮次读取：loopCount 由触发点 CAS 推进（用户发消息置 1，每轮工具完成后 +1），
        // 此处仅读取展示当前处于第几轮，不再负责递增
        int currentIter = context.getLoopCount();

        // 2. 迭代上限保护：防止无限 ReAct 循环导致 Token 溢出或额度超支
        if (currentIter > maxIterations) {
            messageStateMachineService.markIterationLimitExceeded(context);
            return CuteChatResponse.builder().content("").reasoningContent("").toolCalls(List.of()).build();
        }

        log.debug("Agent: 第 {}/{} 轮开始", currentIter, maxIterations);
        // 统一通过发送 CONVERSATION_UPDATE 更新迭代进度（包含落库 -> 同步内存缓存 -> WS推送）
        ConversationEntity iterUpdate = UpdateEntity.of(ConversationEntity.class);
        iterUpdate.setId(cid);
        iterUpdate.setLoopCount(currentIter);
        context.publishEvent(AgentEventFactory.createConversationUpdate(context, iterUpdate));

        // 3. 现查最新的历史消息（包含对齐后的上下文及前文的对话历史）
        List<CuteMessage> history = llmWindowManager.rebuildHistory(context);

        // 4. 解析与加载大模型可用的工具列表
        List<String> allowedToolNames = toolRegistry.getAllTools(context).stream()
                .map(CuteTool::getName)
                .toList();

        List<CuteTool> tools = new ArrayList<>();
        boolean isSubAgent = context.getParentCid() != null && context.getParentCid() != 0L;

        for (String toolName : allowedToolNames) {
            // 如果是子 Agent 运行，排除 invoke_subagent，防止无限递归和嵌套
            if (isSubAgent && ToolNames.INVOKE_SUBAGENT.equals(toolName)) {
                continue;
            }
            CuteTool tool = toolRegistry.getTool(toolName, context);
            if (tool != null) {
                tools.add(tool);
            }
        }

        // 5. 构建大模型调用参数与 CutePrompt 提示词包
        CuteChatOptions options = chatOptionsFactory.buildOptions(activeConfig, tools);
        String llmCallId = java.util.UUID.randomUUID().toString();
        CutePrompt prompt = CutePrompt.builder()
                .messages(history)
                .options(options)
                .callListener(call -> {
                    context.setActiveLlmCall(call);
                    context.registerLlmCall(llmCallId, call, options.getModel());
                })
                .build();

        // 6. 发起流式大模型调用，并实时更新思考流/内容输出流
        CuteChatResponse result;
        try {
            result = consumeChatResponseStream(cuteChat, prompt, context);
        } finally {
            context.unregisterLlmCall(llmCallId);
        }

        // 本次推理大模型返回的文本内容、思考内容与请求的工具调用（流式消费内已累积）
        List<CuteToolCall> pendingCalls = result.getToolCalls();

        agentTokenUsageRecorder.publishWindowUsageSnapshot(context);

        // 7. 判断本次推理是否发起了工具调用
        if (pendingCalls.isEmpty()) {
            // 结束前台的流式思考界面
            context.publishEvent(AgentEventFactory.createThinkingStream(context, "", false, true));

            // 更新会话状态为正常轮次结束，并向上下文分发 LoopRunning=false 状态（通过事件合并上报）
            ConversationEntity loopEndPayload = UpdateEntity.of(ConversationEntity.class);
            loopEndPayload.setId(cid);
            loopEndPayload.setLoopRunning(0);
            loopEndPayload.setWaitingToolIds("");
            loopEndPayload.setWaitingSubCids("");
            context.publishEvent(AgentEventFactory.createConversationUpdate(context, loopEndPayload));
            return result;
        } else {
            // 包含工具调用分支：移交至工具执行引擎异步批量执行本次请求的所有工具，同时返回 true 挂起 ReAct 推理轮次
            handleToolExecution(context, result);
            return result;
        }
    }

    /**
     * 消费 LLM 流式响应，并汇总文本、思考内容、工具调用和 usage 元数据。
     */
    private CuteChatResponse consumeChatResponseStream(CuteChat cuteChat, CutePrompt prompt, AgentContext context) throws InterruptedException {
        Long activeAssistantMsgId = context.getActiveAssistantMsgId();
        long callStart = System.currentTimeMillis();

        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        List<StreamingToolCall> streamingToolCalls = new ArrayList<>();
        AtomicReference<CuteUsage> usageRef = new AtomicReference<>();

        cuteChat.streamConsume(prompt, stream -> stream
                    .takeWhile(_ -> !context.isCanceled())
                    .forEach(chatResponse -> {
                        String content = chatResponse.getContent();
                        String reasoning = chatResponse.getReasoningContent();

                        if (chatResponse.getToolCalls() != null && !chatResponse.getToolCalls().isEmpty()) {
                            for (var tc : chatResponse.getToolCalls()) {
                                if (StringUtils.hasText(tc.getId())) {
                                    StreamingToolCall existing = streamingToolCalls.stream()
                                            .filter(p -> p.id.equals(tc.getId()))
                                            .findFirst().orElse(null);
                                    if (existing == null) {
                                        streamingToolCalls.add(new StreamingToolCall(tc.getId(), tc.getName(), tc.getArguments()));
                                    } else {
                                        existing.arguments.append(tc.getArguments());
                                    }
                                } else {
                                    if (!streamingToolCalls.isEmpty()) {
                                        streamingToolCalls.get(streamingToolCalls.size() - 1).arguments.append(tc.getArguments());
                                    }
                                }
                            }
                        }

                        if (StringUtils.hasLength(reasoning)) {
                            reasoningBuilder.append(reasoning);
                            context.publishEvent(AgentEventFactory.createThinkingStream(context, reasoning, true, false, activeAssistantMsgId));
                        }
                        if (StringUtils.hasLength(content)) {
                            contentBuilder.append(content);
                            context.publishEvent(AgentEventFactory.createThinkingStream(context, content, false, false, activeAssistantMsgId));
                        }

                        if (chatResponse.getUsage() != null) {
                            usageRef.set(chatResponse.getUsage());
                        }
                    }));

        long callDuration = System.currentTimeMillis() - callStart;

        CuteUsage usage = usageRef.get();
        agentTokenUsageRecorder.recordLlmCall(context, usage, callDuration);

        if (context.isCanceled()) {
            throw new InterruptedException("Agent execution canceled during stream consumption.");
        }

        List<CuteToolCall> toolCalls = streamingToolCalls.stream()
                .map(p -> CuteToolCall.builder()
                        .id(p.id)
                        .name(p.name)
                        .arguments(p.arguments.toString())
                        .build())
                .toList();
        return CuteChatResponse.builder()
                .content(contentBuilder.toString())
                .reasoningContent(reasoningBuilder.toString())
                .toolCalls(toolCalls)
                .usage(usage)
                .executionDurationMs(callDuration)
                .build();
    }

    /**
     * 处理带有工具调用的执行分支。
     */
    private void handleToolExecution(AgentContext context, CuteChatResponse result) throws InterruptedException {
        Long activeAssistantMsgId = context.getActiveAssistantMsgId();
        List<CuteToolCall> pendingCalls = result.getToolCalls();

        log.debug("Agent: 模型请求了 {} 个工具", pendingCalls.size());

        checkMeltdownSafety(context, pendingCalls);

        messageStateMachineService.markAssistantToolCalls(context, result);

        for (CuteToolCall call : pendingCalls) {
            toolStatusHandler.onToolCreated(context, call, activeAssistantMsgId);
        }

        List<String> toolCallIds = pendingCalls.stream().map(c -> c.getId()).collect(Collectors.toList());
        initToolRound(context, toolCallIds);

        toolExecutionEngine.executeToolsBatchAsync(pendingCalls, context);
    }

    /**
     * 校验未知工具（幻觉工具）连续尝试次数
     */
    private void checkMeltdownSafety(AgentContext context, List<CuteToolCall> pendingCalls) {
        boolean hasUnknownInBatch = pendingCalls.stream().anyMatch(c -> toolRegistry.getTool(c.getName(), context) == null);
        if (hasUnknownInBatch) {
            int count = context.incrementAndGetConsecutiveUnknownTools();
            if (count >= 3) {
                log.warn("检测到大模型连续 3 轮尝试调用未注册工具，触发安全熔断护栏");
                throw new AgentMeltdownException("由于大模型连续尝试无效工具，运行已被熔断。");
            }
        } else {
            context.setConsecutiveUnknownTools(0);
        }
    }

    /**
     * 释放本轮执行线程，回写窗口用量快照，并在子 Agent 完成时通知父会话。
     */
    private void finalizeContext(AgentContext context, CuteChatResponse response, Throwable runThrowable) {
        Long cid = context.getCid();
        try {
            // 确保 LLM Call 引用被清理
            context.setActiveLlmCall(null);

            if (context.getActiveThread() == Thread.currentThread()) {
                context.setActiveThread(null);
            }

            // 退出时通过 updateLoopRunningStateIfFinished 加锁更新最新的 loopRunning 状态（锁内自动同步内存及推送事件）
            conversationService.updateLoopRunningStateIfFinished(context, runThrowable != null);
        } finally {
            triggerHook(HookEventType.ON_LOOP_END, context);
        }
    }

    /**
     * 注册当前 ASSISTANT 轮次的工具调用等待屏障。
     * 工具执行仍由 ToolExecutionEngine 负责；是否在全部工具和子 Agent 完成后推进下一轮 Loop，
     * 由 AgentProcessor 统一决策。
     */
    private void initToolRound(AgentContext context, List<String> toolCallIds) {
        conversationService.initRoundTools(context, toolCallIds);
    }

    /**
     * 触发生命周期 Hook，并隔离 Hook 失败，避免影响核心 Loop。
     */
    private void triggerHook(HookEventType event, AgentContext context) {
        try {
            HookContext hookCtx = HookContext.builder()
                    .cid(context.getCid())
                    .agentContext(context)
                    .build();
            hookEngineService.triggerHook(event, hookCtx);
        } catch (Exception e) {
            log.warn("执行 {} 挂钩失败", event.name(), e);
        }
    }

    /**
     * 无效工具调用熔断异常。
     */
    private static class AgentMeltdownException extends RuntimeException {
        public AgentMeltdownException(String message) {
            super(message);
        }
    }

    /**
     * 流式 ToolCall 增量拼接实体（仅在 consumeChatResponseStream 内部使用，合并跨 chunk 的参数片段）。
     * 流结束后即冻结为 CuteChatResponse 内的 CuteToolCall，不外逃。
     */
    private static class StreamingToolCall {
        final String id;
        final String name;
        final StringBuilder arguments = new StringBuilder();

        StreamingToolCall(String id, String name, String argumentsStr) {
            this.id = id;
            this.name = name;
            if (argumentsStr != null) {
                this.arguments.append(argumentsStr);
            }
        }
    }
}
