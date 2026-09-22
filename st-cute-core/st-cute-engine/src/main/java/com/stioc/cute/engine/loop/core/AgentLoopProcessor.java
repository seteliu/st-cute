package com.stioc.cute.engine.loop.core;

import com.stioc.cute.engine.event.AgentEventFactory;
import com.stioc.cute.engine.hook.HookType;
import com.stioc.cute.engine.llm.ChatOptionsFactory;
import com.stioc.cute.engine.llm.CuteChat;
import com.stioc.cute.engine.llm.CuteChatFactory;
import com.stioc.cute.engine.llm.types.*;
import com.stioc.cute.engine.loop.message.LlmWindowManager;
import com.stioc.cute.engine.loop.message.MessageDataReporter;
import com.stioc.cute.engine.loop.message.MessageHistoryAligner;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.ToolExecutionEngine;
import com.stioc.cute.engine.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 循环的门内流程：一轮 ReAct 推理的执行步骤编排（怎么跑：窗口→状态机→LLM→工具→熔断→收尾）。
 * <p>
 * 从 AgentLoopCoordinator（门外秩序，何时跑）手中接过得已放行的会话上下文后，
 * 负责一轮推理内部的全部流程步骤。
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class AgentLoopProcessor {

    private final CuteChatFactory chatClientFactory;
    private final ToolRegistry toolRegistry;
    private final LoopDataReporter loopDataReporter;
    private final LlmWindowManager llmWindowManager;
    private final ChatOptionsFactory chatOptionsFactory;
    private final ToolExecutionEngine toolExecutionEngine;
    private final MessageDataReporter messageDataReporter;
    private final MessageHistoryAligner messageHistoryAligner;

    /**
     * 执行多轮 ReAct 自适应智能体循环（状态机驱动骨架）
     */
    public void executeLoop(AgentContext context) {
        Long cid = context.getCid();
        log.debug("Agent: 开始执行 ReAct 循环, cid: {}", cid);

        // 1. 上下文准备
        prepareRuntimeContext(context);

        // 触发生命周期挂点（宿主 HookListener 同步直调）
        try {
            context.triggerHook(HookType.ON_CONTEXT_START, null);
        } catch (Exception e) {
            log.error("ON_CONTEXT_START 挂点阻断，终止本次循环执行: cid={}, 原因: {}", cid, e.getMessage());
            return;
        }

        // 本轮推理结果
        CuteChatResponse stepResponse = null;
        Throwable runThrowable = null;

        try {
            // 2. 上下文窗口管理（压缩及滑窗裁剪）
            boolean compressSuccess = llmWindowManager.manageContextWindowSync(context);
            if (!compressSuccess) {
                return;
            }

            // 3. 执行状态机生命周期对齐，自适应转移并获取活跃助手消息 ID
            Long activeAssistantMsgId = messageHistoryAligner.alignForNextStep(context);
            if (activeAssistantMsgId == null) {
                log.warn("executeLoop: 状态机对齐未生成活跃助理消息，直接退出: cid={}", cid);
                return;
            }

            // 4. 运行单轮推理
            stepResponse = executeReActStep(context);

            // 无后续工具调用（正常推理结束），在此统一更新该 ASSISTANT 为 SUCCESS
            if (stepResponse.getToolCalls().isEmpty()) {
                messageDataReporter.updateAssistantToSuccess(context, stepResponse);
            }
        } catch (InterruptedException e) {
            runThrowable = e;
            messageDataReporter.cancelAllRemainingTools(context, "用户中断了智能体执行。");
            messageDataReporter.updateAssistantToInterrupted(context, e);
        } catch (AgentMeltdownException e) {
            runThrowable = e;
            log.warn("Agent: 触发熔断保护: {}", e.getMessage());
            messageDataReporter.cancelAllRemainingTools(context, "连续多次未知工具调用触发熔断保护。");
            messageDataReporter.updateAssistantToMeltdown(context);
        } catch (Exception e) {
            runThrowable = e;
            log.error("executeLoop 发生未预期异常: cid={}", cid, e);
            messageDataReporter.cancelAllRemainingTools(context, "执行异常崩溃: " + e.getMessage());
            messageDataReporter.updateAssistantToException(context, e);
        } finally {
            // 收尾与副作用处理
            finalizeContext(context, stepResponse, runThrowable);
        }
    }

    /**
     * 准备本轮运行上下文：登记当前执行线程，并委托数据上报器记录开跑账目。
     */
    private void prepareRuntimeContext(AgentContext context) {
        context.setActiveThread(Thread.currentThread());
        loopDataReporter.updateLoopStateToStarted(context);
    }

    /**
     * 执行单轮 ReAct 自适应推理步骤。
     *
     * @param context 当前会话上下文
     * @return 本轮推理的完整响应（正文、思考链、待执行工具调用）
     * @throws InterruptedException 若用户在中途取消或中断执行
     */
    private CuteChatResponse executeReActStep(AgentContext context) throws Exception {
        Long cid = context.getCid();
        CuteChat cuteChat = chatClientFactory.getCuteChat(context);
        Provider activeConfig = chatClientFactory.getProviderConfigForContext(context);
        final int maxIterations = 200;

        // 触发生命周期挂点（宿主 HookListener 同步直调）
        context.triggerHook(HookType.ON_LOOP_START, null);

        // 1. 中断校验：检查用户是否已手动中止当前运行
        if (context.isCanceled()) {
            throw new InterruptedException("用户中断了智能体执行。");
        }

        int currentIter = context.getLoopCount();

        // 2. 迭代上限保护：防止无限 ReAct 循环导致 Token 溢出或额度超支
        if (currentIter > maxIterations) {
            messageDataReporter.updateAssistantToIterationLimit(context);
            return CuteChatResponse.builder().content("").reasoningContent("").toolCalls(List.of()).build();
        }

        log.debug("Agent: 第 {}/{} 轮开始", currentIter, maxIterations);

        // 3. 现查最新的历史消息（包含对齐后的上下文及前文的对话历史）
        List<CuteMessage> history = messageHistoryAligner.rebuildHistory(context);

        // 4. 解析与加载大模型可用的工具列表
        List<CuteTool> allowedTools = toolRegistry.getAllTools(context);
        List<CuteTool> tools = allowedTools.stream()
                .filter(tool -> tool.isAvailable(context))
                .collect(Collectors.toList());

        // 5. 构建大模型调用参数与 CutePrompt 提示词包
        CuteChatOptions options = chatOptionsFactory.buildOptions(activeConfig, tools);
        String llmCallId = UUID.randomUUID().toString();
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

        List<CuteToolCall> pendingCalls = result.getToolCalls();

        loopDataReporter.publishWindowUsageSnapshot(context);

        // 7. 判断本次推理是否发起了工具调用
        if (pendingCalls.isEmpty()) {
            // 正常完结：交由数据上报器查库对账后统一收口 loopRunning=0
            loopDataReporter.updateLoopRunningToFinished(context, false);
            return result;
        } else {
            handleToolExecution(context, result);
            return result;
        }
    }

    /**
     * 消费 LLM 流式响应，并汇总文本、思考内容、工具调用和 usage 元数据。
     * <p>
     * <b>重试语义（关键）</b>：{@code streamConsume} 的 consumer 每次 HTTP 尝试都会被调用一次，
     * 装饰器透明重试时会从头发起并重新 accept，故本 lambda 体内必须<b>先重置累加器</b>，
     * 使「一次尝试 = 一份独立累积状态」。否则流在已产出部分内容后失败时，
     * 失败前累积的片段会被重试叠加第二遍，最终思考/正文出现重复。
     * </p>
     * <p>
     * 注意边界：本方法只保证<b>最终内容</b>（返回值与落库）不重复；重试重发时
     * 已推向前端的流式通知会重复到达，这属于中间过程展示（渲染层按累加处理），
     * 刻意不做缓冲回滚。
     * </p>
     */
    private CuteChatResponse consumeChatResponseStream(CuteChat cuteChat, CutePrompt prompt, AgentContext context) throws InterruptedException {
        Long activeAssistantMsgId = context.getActiveAssistantMsgId();
        long callStart = System.currentTimeMillis();

        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        List<StreamingToolCall> streamingToolCalls = new ArrayList<>();
        AtomicReference<CuteUsage> usageRef = new AtomicReference<>();
        AtomicInteger attemptCount = new AtomicInteger();

        cuteChat.streamConsume(prompt, stream -> {
            // 每次尝试进入时重置累积状态：本 lambda 会被调用多次（初始尝试 + 各次透明重试），
            // 且重试从头发送完整内容，不清空即会把失败前的产出叠成重复内容。
            contentBuilder.setLength(0);
            reasoningBuilder.setLength(0);
            streamingToolCalls.clear();
            usageRef.set(null);

            // 重试（非首次尝试）时额外向下游发出清空信号：已推向前端的流式内容是累加语义，
            // 若不通知丢弃，重放内容会被继续叠加（用户可见的思考/正文重复）。
            // 信号先于本次重放的首片内容发出，二者经同会话同车道保序，故下游必先清空再累积。
            // 首次尝试不发；零产出失败时发出亦无害（清空的是空缓存），故无需判断是否已产出。
            if (attemptCount.incrementAndGet() > 1) {
                context.publishEvent(AgentEventFactory.createThinkingStreamClear(context, activeAssistantMsgId));
                context.publishEvent(AgentEventFactory.createContentStreamClear(context, activeAssistantMsgId));
            }

            stream.takeWhile(_ -> !context.isCanceled())
                    .forEach(chatResponse -> {
                        String content = chatResponse.getContent();
                        String reasoning = chatResponse.getReasoningContent();

                        if (chatResponse.getToolCalls() != null && !chatResponse.getToolCalls().isEmpty()) {
                            for (var tc : chatResponse.getToolCalls()) {
                                if (StringUtils.isNotBlank(tc.getId())) {
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

                        if (StringUtils.isNotEmpty(reasoning)) {
                            reasoningBuilder.append(reasoning);
                            context.publishEvent(AgentEventFactory.createThinkingStream(context, activeAssistantMsgId, reasoning));
                        }
                        if (StringUtils.isNotEmpty(content)) {
                            contentBuilder.append(content);
                            context.publishEvent(AgentEventFactory.createContentStream(context, activeAssistantMsgId, content));
                        }

                        if (chatResponse.getUsage() != null) {
                            usageRef.set(chatResponse.getUsage());
                        }
                    });
        });

        long callDuration = System.currentTimeMillis() - callStart;

        CuteUsage usage = usageRef.get();
        loopDataReporter.recordLlmCall(context, usage, callDuration);

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

        messageDataReporter.updateAssistantToToolCalls(context, result);

        for (CuteToolCall call : pendingCalls) {
            messageDataReporter.createToolMessage(context, call, activeAssistantMsgId);
        }

        List<String> toolCallIds = pendingCalls.stream().map(CuteToolCall::getId).collect(Collectors.toList());
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

            // 退出时通过 updateLoopRunningToFinished 加锁查库对账后更新最新的 loopRunning 状态
            loopDataReporter.updateLoopRunningToFinished(context, runThrowable != null);
        } finally {
            try {
                context.triggerHook(HookType.ON_LOOP_END, null);
            } catch (Exception e) {
                log.error("ON_LOOP_END 挂点执行异常: cid={}, 原因: {}", cid, e.getMessage());
            }
        }
    }

    /**
     * 登记当前 ASSISTANT 轮次的工具调用等待屏障。
     */
    private void initToolRound(AgentContext context, List<String> toolCallIds) {
        loopDataReporter.registerRoundTools(context, toolCallIds);
    }

    /**
     * 无效工具调用熔断异常。
     */
    static class AgentMeltdownException extends RuntimeException {
        public AgentMeltdownException(String message) {
            super(message);
        }
    }

    /**
     * 流式 ToolCall 增量拼接实体
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
