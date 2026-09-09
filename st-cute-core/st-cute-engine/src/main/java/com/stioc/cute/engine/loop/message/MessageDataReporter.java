package com.stioc.cute.engine.loop.message;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.event.AgentEventFactory;
import com.stioc.cute.engine.llm.types.CuteChatResponse;
import com.stioc.cute.engine.llm.types.CuteToolCall;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessagePatch;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.store.types.SortDirection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 统一管理 Agent 循环中的持久化消息状态迁移与事件上报（Message Data Reporter）。
 * <p>
 * 融合原助手消息状态机（MessageStateMachine）与工具消息状态处理器（ToolStatusHandler），
 * 作为全引擎唯一消息生命周期事件发布点：
 * 负责 ASSISTANT、TOOL、COMPRESSED 以及 USER/BRANCH 输入消息在 PENDING、RUNNING、SUCCESS、FAILED、WAITING_APPROVAL、CANCELED
 * 等状态之间的持久化流转与事件上报，提供统一的 CANCELED 终态防腐与悬挂消息清理能力。
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class MessageDataReporter {

    private final MessageStore messageStore;

    // ==================== ASSISTANT 消息生命周期 ====================

    /**
     * 创建一条指定状态的 ASSISTANT 占位消息并发布落库事件
     */
    public Long createAssistantMessage(AgentContext context, MessageStatus status) {
        Message astMsg = Message.builder()
                .cid(context.getCid())
                .role(MessageRole.ASSISTANT)
                .content("")
                .status(status)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        context.publishEvent(AgentEventFactory.createMessageCreate(context, astMsg));
        return astMsg.getId();
    }

    /**
     * 将指定 ASSISTANT 消息置为 RUNNING
     */
    public void updateAssistantToRunning(AgentContext context, Long activeAssistantMsgId) {
        if (activeAssistantMsgId == null) {
            return;
        }
        MessagePatch updateAst = new MessagePatch(activeAssistantMsgId)
                .cid(context.getCid())
                .role(MessageRole.ASSISTANT)
                .status(MessageStatus.RUNNING)
                .content("");
        context.publishEvent(AgentEventFactory.createMessageUpdate(context, updateAst));
    }

    /**
     * 将当前活跃的 ASSISTANT 消息标记为最终自然语言回答（SUCCESS）
     */
    public void updateAssistantToSuccess(AgentContext context, CuteChatResponse response) {
        updateAssistant(context, MessageStatus.SUCCESS, null, null, null, response);
    }

    /**
     * 持久化本轮模型发起工具调用时的 ASSISTANT 消息（SUCCESS）
     */
    public void updateAssistantToToolCalls(AgentContext context, CuteChatResponse response) {
        updateAssistant(context, MessageStatus.SUCCESS, null, null, null, response);
    }

    /**
     * 当 ReAct 迭代次数超过配置上限时，将当前 ASSISTANT 消息置为失败（FAILED）
     */
    public void updateAssistantToIterationLimit(AgentContext context) {
        String warningMsg = "\n\n**[警告] 已达到最大迭代上限，运行已停止以防止失控。**";
        context.publishEvent(AgentEventFactory.createContentStream(context, context.getActiveAssistantMsgId(), warningMsg));
        updateAssistant(context, MessageStatus.FAILED, warningMsg, null, null, null);
    }

    /**
     * 用户中断后，将当前 ASSISTANT 消息置为取消（CANCELED）
     */
    public void updateAssistantToInterrupted(AgentContext context, InterruptedException e) {
        log.warn("Agent Loop 被中断: {}", e.getMessage());
        String cancelMsg = "[已取消] 当前 Agent 运行已停止。";
        updateAssistant(context, MessageStatus.CANCELED, cancelMsg, null, null, null);
        context.publishEvent(AgentEventFactory.createContentStream(context, context.getActiveAssistantMsgId(), "\n\n**[已取消] 当前 Agent 运行已停止。**"));
    }

    /**
     * 连续出现无效工具调用后，将当前 ASSISTANT 消息置为失败（FAILED）
     */
    public void updateAssistantToMeltdown(AgentContext context) {
        String warningMsg = "[熔断保护] 模型连续发起无效工具调用，运行已停止。";
        updateAssistant(context, MessageStatus.FAILED, warningMsg, null, null, null);
    }

    /**
     * Loop 发生未预期异常时，将当前 ASSISTANT 消息置为失败（FAILED），并发布错误事件
     */
    public void updateAssistantToException(AgentContext context, Exception e) {
        log.error("Agent Loop 执行失败", e);
        String errorMsg = "[对话异常] Agent 执行失败: " + e.getMessage();
        updateAssistant(context, MessageStatus.FAILED, errorMsg, null, null, null);
    }

    // ==================== 输入消息（USER/BRANCH）状态流转 ====================

    /**
     * 查询当前会话中处于 PENDING 状态的用户输入（USER/BRANCH）并批量标记为 SUCCESS
     *
     * @param context 当前智能体上下文
     * @return 本次标记为 SUCCESS 的挂起输入条数
     */
    public int updatePendingInputsToSuccess(AgentContext context) {
        List<Message> pendingInputs = messageStore.listByQuery(MessageQuery.builder()
                .cid(context.getCid())
                .roles(List.of(MessageRole.USER, MessageRole.BRANCH))
                .statuses(List.of(MessageStatus.PENDING))
                .sortDirection(SortDirection.ASC)
                .build());
        if (pendingInputs == null || pendingInputs.isEmpty()) {
            return 0;
        }
        for (Message input : pendingInputs) {
            updatePendingInputToSuccess(context, input);
        }
        return pendingInputs.size();
    }

    /**
     * 将数据库中仍为 PENDING 状态的用户输入（USER/BRANCH）标记为 SUCCESS
     */
    public void updatePendingInputsToSuccess(AgentContext context, List<Message> pendingInputs) {
        if (pendingInputs == null || pendingInputs.isEmpty()) {
            return;
        }
        for (Message input : pendingInputs) {
            updatePendingInputToSuccess(context, input);
        }
    }

    private void updatePendingInputToSuccess(AgentContext context, Message inputMsg) {
        if (MessageStatus.PENDING != inputMsg.getStatus()) {
            return;
        }
        MessagePatch update = new MessagePatch(inputMsg.getId())
                .cid(context.getCid())
                .role(inputMsg.getRole())
                .status(MessageStatus.SUCCESS)
                .content(inputMsg.getContent());
        context.publishEvent(AgentEventFactory.createMessageUpdate(context, update));
    }

    /**
     * 创建并发布一条子智能体分支报告消息（BRANCH 角色，PENDING 态），用于向父会话汇总结论
     *
     * @param context 当前智能体（通常为父智能体）上下文
     * @param reportContent 报告正文
     * @return 分支消息实体 ID
     */
    public Long createBranchMessage(AgentContext context, String reportContent) {
        Message branchMsg = Message.builder()
                .cid(context.getCid())
                .role(MessageRole.BRANCH)
                .content(reportContent)
                .status(MessageStatus.PENDING)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        context.publishEvent(AgentEventFactory.createMessageCreate(context, branchMsg));
        return branchMsg.getId();
    }

    // ==================== TOOL 消息生命周期 ====================

    /**
     * 登记并创建工具调用卡片消息，通过事件抛出创建请求。初始状态统一为 PENDING。
     */
    public void createToolMessage(AgentContext context, CuteToolCall call, Long parentAssistantMsgId) {
        MessageStatus initialStatus = MessageStatus.PENDING;

        // 构造存盘的工具描述 JSONObject
        JSONObject toolDesc = new JSONObject();
        toolDesc.put("id", call.getId());
        toolDesc.put("name", call.getName());
        toolDesc.put("arguments", call.getArguments());

        // 构造尚未落库的消息实体（callId 作为 callId↔messageId 的稳定关联键，写入 call_id 列）
        Message toolEntity = Message.builder()
                .cid(context.getCid())
                .role(MessageRole.TOOL)
                .content("")
                .status(initialStatus)
                .toolCalls(toolDesc.toJSONString())
                .callId(call.getId())
                .parentMessageId(parentAssistantMsgId)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();

        // 抛出同步创建数据库指令事件，由监听器链完成数据库落库
        context.publishEvent(AgentEventFactory.createMessageCreate(context, toolEntity));
    }

    /**
     * 更新工具执行状态（RUNNING / WAITING_APPROVAL / SUCCESS / FAILED 等）
     */
    public void updateToolStatus(AgentContext context, String toolCallId, MessageStatus status, String resultPayload) {
        updateToolStatus(context, toolCallId, status, resultPayload, null);
    }

    /**
     * 更新工具执行状态，支持关联附件元数据
     */
    public void updateToolStatus(AgentContext context, String toolCallId, MessageStatus status, String resultPayload, String attachments) {
        Message toolMsg = messageStore.getByQuery(MessageQuery.builder()
                .cid(context.getCid())
                .callId(toolCallId)
                .build());
        if (toolMsg == null) {
            context.publishEvent(AgentEventFactory.createMessageCreate(context,
                    buildMissingToolMessage(context, toolCallId, resultPayload)));
            return;
        }

        // CANCELED 终态守卫：工具消息已被中断链路置为 CANCELED 后，丢弃迟到回写，避免覆盖取消结果
        if (MessageStatus.CANCELED == toolMsg.getStatus() && MessageStatus.CANCELED != status) {
            log.debug("TOOL 消息 {} 已是 CANCELED 终态，丢弃迟到的 {} 回写: toolCallId={}", toolMsg.getId(), status, toolCallId);
            return;
        }

        MessagePatch update = new MessagePatch(toolMsg.getId())
                .cid(context.getCid())
                .role(MessageRole.TOOL)
                .status(status)
                .content(resultPayload)
                .updateTime(LocalDateTime.now());
        if (StringUtils.isNotBlank(attachments)) {
            update.attachments(attachments);
        }
        context.publishEvent(AgentEventFactory.createMessageUpdate(context, update));
    }

    private Message buildMissingToolMessage(AgentContext context, String toolCallId, String resultPayload) {
        JSONObject toolDesc = new JSONObject();
        toolDesc.put("id", toolCallId);
        toolDesc.put("name", "unknown");
        toolDesc.put("arguments", "{}");

        String content = resultPayload != null
                ? resultPayload
                : "{\"error\": \"Tool message was missing before status update.\"}";
        return Message.builder()
                .cid(context.getCid())
                .role(MessageRole.TOOL)
                .content(content)
                .status(MessageStatus.FAILED)
                .toolCalls(toolDesc.toJSONString())
                .callId(toolCallId)
                .parentMessageId(context.getActiveAssistantMsgId())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
    }

    // ==================== COMPRESSED 消息生命周期与上下文压缩 ====================

    /**
     * 创建一条 RUNNING 状态的 COMPRESSED 占位消息并发布落库事件
     *
     * @param context 当前智能体上下文
     * @return 占位消息 ID
     */
    public Long createCompressedMessage(AgentContext context) {
        Message compMsg = Message.builder()
                .cid(context.getCid())
                .role(MessageRole.COMPRESSED)
                .content("")
                .status(MessageStatus.RUNNING)
                .visibleToUser(true)
                .visibleToModel(true)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        context.publishEvent(AgentEventFactory.createMessageCreate(context, compMsg));
        return compMsg.getId();
    }

    /**
     * 将指定 COMPRESSED 消息置为 SUCCESS 并填充压缩记忆摘要
     *
     * @param context 当前智能体上下文
     * @param messageId 压缩消息 ID
     * @param summaryText 记忆摘要正文
     */
    public void updateCompressedToSuccess(AgentContext context, Long messageId, String summaryText) {
        if (messageId == null) {
            return;
        }
        MessagePatch successPatch = new MessagePatch(messageId)
                .cid(context.getCid())
                .role(MessageRole.COMPRESSED)
                .status(MessageStatus.SUCCESS)
                .content("[System Memory Summary]: " + summaryText);
        context.publishEvent(AgentEventFactory.createMessageUpdate(context, successPatch));
    }

    /**
     * 将指定 COMPRESSED 消息置为 FAILED
     *
     * @param context 当前智能体上下文
     * @param messageId 压缩消息 ID
     */
    public void updateCompressedToFailed(AgentContext context, Long messageId) {
        if (messageId == null) {
            return;
        }
        MessagePatch failPatch = new MessagePatch(messageId)
                .cid(context.getCid())
                .role(MessageRole.COMPRESSED)
                .status(MessageStatus.FAILED);
        context.publishEvent(AgentEventFactory.createMessageUpdate(context, failPatch));
    }

    /**
     * 上下文压缩失败后的统一兜底上报处理：
     * 1. 将所有挂起的用户输入标记为 SUCCESS；
     * 2. 创建一条失败的 ASSISTANT 错误消息并发布；
     * 3. 登记当前活跃 ASSISTANT 消息 ID。
     *
     * @param context 当前智能体上下文
     * @param errorMessage 错误消息说明
     */
    public void recordCompressFailure(AgentContext context, String errorMessage) {
        Long cid = context.getCid();
        updatePendingInputsToSuccess(context);

        Message astMsg = Message.builder()
                .cid(cid)
                .role(MessageRole.ASSISTANT)
                .content(errorMessage)
                .status(MessageStatus.FAILED)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        context.publishEvent(AgentEventFactory.createMessageCreate(context, astMsg));
        context.setActiveAssistantMsgId(astMsg.getId());
    }

    /**
     * 批量将指定的历史归档消息在数据库中置为模型不可见（visibleToModel = false）
     *
     * @param context 当前智能体上下文
     * @param messages 需要归档的消息集合
     */
    public void archiveMessagesVisibleToModel(AgentContext context, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Long cid = context.getCid();
        List<Long> msgIds = messages.stream().map(Message::getId).filter(Objects::nonNull).toList();
        messageStore.updateByQuery(
                Message.builder().visibleToModel(false).build(),
                MessageQuery.builder().cid(cid).ids(msgIds).build()
        );
    }

    // ==================== 终态防腐与悬挂清理 ====================

    /**
     * 更新当前活跃 ASSISTANT 消息的统一收口方法
     */
    private void updateAssistant(AgentContext context, MessageStatus status, String content, String reasoningContent, String toolCallsJson, CuteChatResponse response) {
        Long activeAssistantMsgId = context.getActiveAssistantMsgId();
        if (activeAssistantMsgId == null) {
            log.warn("跳过 ASSISTANT 消息更新，activeAssistantMsgId 为空: cid={}, status={}", context.getCid(), status);
            return;
        }
        // CANCELED 终态守卫：消息已被用户中断置为 CANCELED 后，丢弃后续迟到的终态回写，避免覆盖取消结果
        if (isAlreadyCanceled(activeAssistantMsgId, status)) {
            return;
        }
        // FAILED 终态守卫：消息已被置为 FAILED（如超轮次、熔断保护、未预期异常）后，
        // 后续迟到的 SUCCESS 回写不应覆盖终态，防止刷新后丢失警告内容
        if (isAlreadyFailed(activeAssistantMsgId, status)) {
            return;
        }
        MessagePatch update = new MessagePatch(activeAssistantMsgId)
                .cid(context.getCid())
                .role(MessageRole.ASSISTANT)
                .status(status);
        if (response != null) {
            update.content(response.getContent())
                    .reasoningContent(response.getReasoningContent());
            if (response.getToolCalls() != null && !response.getToolCalls().isEmpty()) {
                update.toolCalls(JSON.toJSONString(response.getToolCalls()));
            }
            if (response.getUsage() != null) {
                update.inputTokens(response.getUsage().getInputTokens())
                        .outputTokens(response.getUsage().getOutputTokens())
                        .cachedTokens(response.getUsage().getCachedTokens());
            }
            update.executionDurationMs(response.getExecutionDurationMs());
        } else {
            update.content(content)
                    .reasoningContent(reasoningContent)
                    .toolCalls(toolCallsJson);
        }
        context.publishEvent(AgentEventFactory.createMessageUpdate(context, update));
    }

    /**
     * CANCELED 终态守卫：目标消息在 DB 中已是 CANCELED 时返回 true（且本次并非取消写入自身），
     * 用于拦截用户中断后被解阻塞线程迟到的状态回写，防止覆盖已落定的取消终态。
     */
    private boolean isAlreadyCanceled(Long messageId, MessageStatus incomingStatus) {
        if (incomingStatus == MessageStatus.CANCELED) {
            return false;
        }
        Message current = messageStore.getById(messageId);
        if (current != null && current.getStatus() == MessageStatus.CANCELED) {
            log.debug("ASSISTANT 消息 {} 已是 CANCELED 终态，丢弃迟到的 {} 回写", messageId, incomingStatus);
            return true;
        }
        return false;
    }

    /**
     * FAILED 终态守卫：目标消息在 DB 中已是 FAILED 时返回 true，
     * 用于拦截后续的 SUCCESS 回写，防止覆盖超轮次/熔断/异常等终态警告内容。
     * 允许 CANCELED 回写覆盖 FAILED（用户取消优先级最高）。
     */
    private boolean isAlreadyFailed(Long messageId, MessageStatus incomingStatus) {
        if (incomingStatus == MessageStatus.CANCELED) {
            return false;
        }
        Message current = messageStore.getById(messageId);
        if (current != null && current.getStatus() == MessageStatus.FAILED) {
            log.debug("ASSISTANT 消息 {} 已是 FAILED 终态，丢弃迟到的 {} 回写", messageId, incomingStatus);
            return true;
        }
        return false;
    }

    /**
     * 取消当前会话所有尚未运行完成的 ASSISTANT 消息，防范悬挂状态数据。
     */
    public void cancelAllRemainingAssistants(AgentContext context) {
        List<Message> inflight = messageStore.listByQuery(MessageQuery.builder()
                .cid(context.getCid())
                .roles(List.of(MessageRole.ASSISTANT))
                .statuses(List.of(MessageStatus.PENDING, MessageStatus.RUNNING))
                .sortDirection(SortDirection.ASC)
                .build());
        for (Message m : inflight) {
            MessagePatch update = new MessagePatch(m.getId())
                    .cid(context.getCid())
                    .role(MessageRole.ASSISTANT)
                    .status(MessageStatus.CANCELED);
            context.publishEvent(AgentEventFactory.createMessageUpdate(context, update));

            // 同步发送取消流式提示
            context.publishEvent(AgentEventFactory.createContentStream(context, m.getId(), "\n\n[已取消] 当前 Agent 运行已停止。"));
        }
    }

    /**
     * 取消当前会话所有尚未运行完成的工具，防范悬挂状态数据。
     */
    public void cancelAllRemainingTools(AgentContext context, String reason) {
        String errorPayload = "{\"error\": \"" + (reason != null ? reason : "运行中断或熔断") + "\"}";
        List<Message> inflight = messageStore.listByQuery(MessageQuery.builder()
                .cid(context.getCid())
                .roles(List.of(MessageRole.TOOL))
                .statuses(List.of(MessageStatus.PENDING, MessageStatus.RUNNING, MessageStatus.WAITING_APPROVAL))
                .sortDirection(SortDirection.ASC)
                .build());
        for (Message m : inflight) {
            MessagePatch update = new MessagePatch(m.getId())
                    .cid(context.getCid())
                    .role(MessageRole.TOOL)
                    .status(MessageStatus.CANCELED)
                    .content(errorPayload)
                    .updateTime(LocalDateTime.now());
            context.publishEvent(AgentEventFactory.createMessageUpdate(context, update));
        }
    }

    /**
     * 一键取消当前会话下所有处于飞行中的消息（包括 ASSISTANT 与 TOOL），防范悬挂孤儿数据。
     */
    public void cancelAllInflightMessages(AgentContext context, String reason) {
        cancelAllRemainingAssistants(context);
        cancelAllRemainingTools(context, reason);
    }

    // ==================== USER 消息与历史控制 ====================

    /**
     * 创建一条 USER 消息并发布落库与广播事件。
     */
    public void createUserMessage(AgentContext context, String text, String attachments) {
        Message userEntity = Message.builder()
                .cid(context.getCid())
                .role(MessageRole.USER)
                .content(text)
                .attachments(attachments)
                .status(MessageStatus.PENDING)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        context.publishEvent(AgentEventFactory.createMessageCreate(context, userEntity));
    }

    /**
     * 重置指定的 ASSISTANT 消息为 PENDING 状态并清空正文与思考（用于消息重试）。
     */
    public void resetAssistantMessage(AgentContext context, Long messageId) {
        MessagePatch updateMsg = new MessagePatch(messageId)
                .cid(context.getCid())
                .status(MessageStatus.PENDING)
                .content("")
                .reasoningContent("")
                .toolCalls(null);
        context.publishEvent(AgentEventFactory.createMessageUpdate(context, updateMsg));
    }

    /**
     * 从事件总线上报会话消息截断删除命令（删除 ID 大于等于 messageId 的所有消息）。
     */
    public void deleteMessagesFrom(AgentContext context, Long messageId) {
        Message deleteCondition = Message.builder()
                .cid(context.getCid())
                .id(messageId - 1)
                .build();
        context.publishEvent(AgentEventFactory.createMessageDelete(context, deleteCondition));
    }

    /**
     * 重置指定 USER 消息为 PENDING 并物理截断删除其后续所有消息（用于 USER 消息重试）。
     *
     * @param context 当前智能体运行上下文
     * @param userMessageId 目标用户消息 ID
     */
    public void resetUserMessageAndTruncateSubsequent(AgentContext context, Long userMessageId) {
        Long cid = context.getCid();
        Message targetMsg = messageStore.getByQuery(MessageQuery.builder()
                .cid(cid)
                .id(userMessageId)
                .role(MessageRole.USER)
                .build());
        if (targetMsg == null) {
            throw new IllegalArgumentException("未找到对应的用户消息或消息不属于该会话, messageId=" + userMessageId);
        }

        boolean hasCompressed = messageStore.existsByQuery(MessageQuery.builder()
                .cid(cid)
                .greaterThanId(userMessageId)
                .role(MessageRole.COMPRESSED)
                .build());
        if (hasCompressed) {
            throw new IllegalStateException("后续消息中存在已压缩的上下文，无法重置到此节点");
        }

        // 1. 删除该用户消息之后的所有消息（传入 userMessageId 触发 Direct 层 deleteByQuery greaterThanId(userMessageId)）
        Message deleteCondition = Message.builder()
                .cid(cid)
                .id(userMessageId)
                .build();
        context.publishEvent(AgentEventFactory.createMessageDelete(context, deleteCondition));

        // 2. 将该用户消息自身重置为 PENDING 状态
        MessagePatch updateMsg = new MessagePatch(userMessageId)
                .cid(cid)
                .role(MessageRole.USER)
                .status(MessageStatus.PENDING);
        context.publishEvent(AgentEventFactory.createMessageUpdate(context, updateMsg));
    }
}
