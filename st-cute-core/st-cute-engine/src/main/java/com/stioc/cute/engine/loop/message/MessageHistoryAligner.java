package com.stioc.cute.engine.loop.message;

import com.alibaba.fastjson2.JSON;
import com.stioc.cute.engine.llm.AttachmentContentLoader;
import com.stioc.cute.engine.llm.CuteChatFactory;
import com.stioc.cute.engine.llm.types.CuteAttachment;
import com.stioc.cute.engine.llm.types.CuteMessage;
import com.stioc.cute.engine.llm.types.CuteMessageRole;
import com.stioc.cute.engine.llm.types.CuteToolCall;
import com.stioc.cute.engine.llm.types.Provider;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.prompt.SystemPromptAssembler;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.tool.ToolCallCodec;
import com.stioc.cute.engine.store.types.SortDirection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 历史消息与输入对齐工具（Message History Aligner）。
 * <p>
 * 专门负责两项关键对齐工作：
 * 1. 【运行输入对齐】：将数据库末尾消息对齐到下一次模型推理步骤，消费挂起输入，创建/激活当前活跃 ASSISTANT 占位符；
 * 2. 【历史数据还原】：从数据库还原大模型可见的全部历史消息，处理多模态附件、空结果状态感知占位符、COMPRESSED 排序与 SYSTEM 提示词装配。
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class MessageHistoryAligner {

    private final MessageStore messageStore;
    private final SystemPromptAssembler systemPromptAssembler;
    private final CuteChatFactory chatClientFactory;
    private final Optional<AttachmentContentLoader> attachmentContentLoader;
    private final MessageDataReporter messageDataReporter;

    // ==================== 下一轮输入对齐 ====================

    /**
     * 将数据库末尾消息对齐到下一次模型推理步骤。
     * 将待处理输入标记为已消费（SUCCESS），并创建或重新打开当前活跃的 ASSISTANT 占位消息。
     */
    public Long alignForNextStep(AgentContext context) {
        Long cid = context.getCid();

        // 1. 查找并标记该会话下所有挂起状态的用户输入（USER/BRANCH）为 SUCCESS
        boolean hasNewInput = messageDataReporter.updatePendingInputsToSuccess(context) > 0;

        // 2. 如果存在挂起的输入被消费，说明是新一轮交互的开始，在此重置连续未知工具计数
        if (hasNewInput) {
            context.setConsecutiveUnknownTools(0);
        }

        Message lastMsg = messageStore.getByQuery(MessageQuery.builder()
                .cid(cid)
                .sortField("id")
                .sortDirection(SortDirection.DESC)
                .build());
        if (lastMsg == null) {
            return null;
        }

        Long activeAssistantMsgId;
        if (MessageRole.USER == lastMsg.getRole()) {
            context.setConsecutiveUnknownTools(0);
            activeAssistantMsgId = messageDataReporter.createAssistantMessage(context, MessageStatus.RUNNING);
        } else if (MessageRole.ASSISTANT == lastMsg.getRole()) {
            activeAssistantMsgId = lastMsg.getId();
            messageDataReporter.updateAssistantToRunning(context, activeAssistantMsgId);
        } else if (MessageRole.TOOL == lastMsg.getRole() || MessageRole.SYSTEM == lastMsg.getRole() || MessageRole.COMPRESSED == lastMsg.getRole()) {
            activeAssistantMsgId = messageDataReporter.createAssistantMessage(context, MessageStatus.RUNNING);
        } else if (MessageRole.BRANCH == lastMsg.getRole()) {
            activeAssistantMsgId = messageDataReporter.createAssistantMessage(context, MessageStatus.RUNNING);
        } else {
            log.warn("无法对齐 Loop 消息状态: cid={}, role={}", cid, lastMsg.getRole());
            return null;
        }

        context.setActiveAssistantMsgId(activeAssistantMsgId);
        return activeAssistantMsgId;
    }

    // ==================== 大模型历史还原与转换 ====================

    /**
     * 对话历史还原与同步（从数据库拉取大模型可见的全部历史消息）
     */
    public List<CuteMessage> rebuildHistory(AgentContext context) {
        Long cid = context.getCid();
        List<Message> allMsgs = messageStore.listByQuery(MessageQuery.builder()
                .cid(cid)
                .visibleToModel(true)
                .sortField("id")
                .sortDirection(SortDirection.ASC)
                .build());
        return rebuildHistory(context, allMsgs);
    }

    /**
     * 基于指定消息集合还原大模型历史会话
     */
    public List<CuteMessage> rebuildHistory(AgentContext context, List<Message> allMsgs) {
        Long cid = context.getCid();
        Long excludeAssistantMsgId = context.getActiveAssistantMsgId();
        List<CuteMessage> history = new ArrayList<>();
        String systemPrompt = systemPromptAssembler.assemble(context);

        // 1. 无条件把最新的系统提示词作为历史消息列表的第一条（大模型上下文的第一条）
        history.add(CuteMessage.builder().role(CuteMessageRole.SYSTEM).content(systemPrompt).build());

        // 2. 过滤出 visibleToModel == true 的消息，同时排除 SYSTEM 角色消息（屏蔽数据库残留的系统消息）
        List<Message> dbMsgs = allMsgs.stream()
                .filter(m -> Boolean.TRUE.equals(m.getVisibleToModel()) && MessageRole.SYSTEM != m.getRole())
                .collect(Collectors.toList());

        // 3. 对 dbMsgs 进行重新排序以确保 MessageRole.COMPRESSED 消息排在最前面，其他普通消息紧随其后
        dbMsgs.sort((m1, m2) -> {
            boolean isComp1 = MessageRole.COMPRESSED == m1.getRole();
            boolean isComp2 = MessageRole.COMPRESSED == m2.getRole();
            if (isComp1 && !isComp2) {
                return -1;
            } else if (!isComp1 && isComp2) {
                return 1;
            } else {
                return m1.getId().compareTo(m2.getId());
            }
        });

        Provider activeConfig = chatClientFactory.getProviderConfigForContext(context);
        boolean isMultimodal = activeConfig != null && Boolean.TRUE.equals(activeConfig.getMultimodal());

        // 寻找最后一条有效的用户消息 ID，仅对该消息挂载多模态附件
        Long lastUserMsgId = null;
        for (int i = dbMsgs.size() - 1; i >= 0; i--) {
            Message m = dbMsgs.get(i);
            if (excludeAssistantMsgId != null && m.getId().equals(excludeAssistantMsgId)) {
                continue;
            }
            if (MessageRole.USER == m.getRole() && MessageStatus.CANCELED != m.getStatus()) {
                lastUserMsgId = m.getId();
                break;
            }
        }

        for (Message dbMsg : dbMsgs) {
            // 排除当前轮占位符
            if (excludeAssistantMsgId != null && dbMsg.getId().equals(excludeAssistantMsgId)) {
                continue;
            }

            MessageStatus mStatus = dbMsg.getStatus();
            if (mStatus == null) {
                mStatus = MessageStatus.SUCCESS;
            }

            if (MessageRole.USER == dbMsg.getRole()) {
                if (MessageStatus.CANCELED == mStatus) {
                    continue;
                }
                List<CuteAttachment> cuteAttachments = null;
                String userContent = dbMsg.getContent() != null ? dbMsg.getContent() : "";

                if (StringUtils.isNotBlank(dbMsg.getAttachments())) {
                    if (dbMsg.getId().equals(lastUserMsgId)) {
                        // 最后一轮有效用户消息：完整加载附件 Payload
                        cuteAttachments = loadAttachments(dbMsg.getAttachments(), context, isMultimodal);
                    } else {
                        // 历史用户消息：生成轻量 Markdown 占位符追加在文本末尾
                        String placeholder = buildAttachmentPlaceholder(dbMsg.getAttachments());
                        if (StringUtils.isNotBlank(placeholder)) {
                            userContent = userContent + "\n\n" + placeholder;
                        }
                    }
                }

                history.add(CuteMessage.builder()
                        .role(CuteMessageRole.USER)
                        .content(userContent)
                        .attachments(cuteAttachments)
                        .build());
            } else if (MessageRole.ASSISTANT == dbMsg.getRole()) {
                if (MessageStatus.FAILED == mStatus || MessageStatus.CANCELED == mStatus) {
                    continue;
                }
                List<CuteToolCall> toolCallsList = new ArrayList<>();
                if (StringUtils.isNotBlank(dbMsg.getToolCalls())) {
                    toolCallsList = new ArrayList<>(ToolCallCodec.parseList(dbMsg.getToolCalls()));
                }
                history.add(CuteMessage.builder()
                        .role(CuteMessageRole.ASSISTANT)
                        .content(dbMsg.getContent())
                        .reasoningContent(dbMsg.getReasoningContent())
                        .toolCalls(toolCallsList)
                        .build());

            } else if (MessageRole.TOOL == dbMsg.getRole()) {
                String rawResult = dbMsg.getContent();
                MessageStatus toolStatus = dbMsg.getStatus() != null ? dbMsg.getStatus() : MessageStatus.SUCCESS;
                if (MessageStatus.REJECTED == toolStatus) {
                    rawResult = "{\"error\": \"Permission denied by user.\"}";
                } else if (MessageStatus.CANCELED == toolStatus) {
                    rawResult = "{\"error\": \"Execution canceled by user.\"}";
                } else if (MessageStatus.WAITING_APPROVAL == toolStatus) {
                    rawResult = "{\"status\": \"WAITING_APPROVAL\", \"message\": \"该工具调用正在等待用户审批，尚未执行。\"}";
                }

                String toolCallId = null;
                String toolName = null;
                if (StringUtils.isNotBlank(dbMsg.getToolCalls())) {
                    CuteToolCall call = ToolCallCodec.parseSingle(dbMsg.getToolCalls());
                    toolCallId = StringUtils.isNotBlank(call.getId()) ? call.getId() : null;
                    toolName = StringUtils.isNotBlank(call.getName()) ? call.getName() : null;
                }

                if (StringUtils.isBlank(rawResult)) {
                    rawResult = buildEmptyToolResultPlaceholder(dbMsg.getStatus(), toolName);
                }

                List<CuteAttachment> cuteAttachments = null;
                if (StringUtils.isNotBlank(dbMsg.getAttachments())) {
                    cuteAttachments = loadAttachments(dbMsg.getAttachments(), context, isMultimodal);
                }

                history.add(CuteMessage.builder()
                        .role(CuteMessageRole.TOOL)
                        .toolCallId(toolCallId)
                        .toolName(toolName)
                        .content(rawResult)
                        .attachments(cuteAttachments)
                        .build());
            } else if (MessageRole.BRANCH == dbMsg.getRole()) {
                // 子 Agent 汇报消息：以 USER 角色发给大模型，内容前拼接来源前缀
                if (MessageStatus.CANCELED != mStatus) {
                    String branchContent = "来自其他Agent：\n" + dbMsg.getContent();
                    history.add(CuteMessage.builder()
                            .role(CuteMessageRole.USER)
                            .content(branchContent)
                            .build());
                }
            } else if (MessageRole.COMPRESSED == dbMsg.getRole()) {
                if (MessageStatus.SUCCESS == mStatus) {
                    history.add(CuteMessage.builder()
                            .role(CuteMessageRole.USER)
                            .content(dbMsg.getContent())
                            .build());
                }
            }
        }

        return history;
    }

    /**
     * 构建状态感知的空结果占位文案
     */
    private String buildEmptyToolResultPlaceholder(MessageStatus status, String toolName) {
        String name = StringUtils.isNotBlank(toolName) ? toolName : "unknown";
        if (status == null) {
            return String.format(
                    "{\"status\": \"UNKNOWN\", \"error\": \"工具 %s 的结果与状态均缺失，请勿采信本条记录。\"}", name);
        }
        return switch (status) {
            case SUCCESS -> String.format(
                    "{\"status\": \"SUCCESS\", \"message\": \"工具 %s 已执行成功，但本次调用无任何输出内容。\"}", name);
            case FAILED -> String.format(
                    "{\"status\": \"FAILED\", \"error\": \"工具 %s 执行失败，但错误详情未能保留。"
                            + "如后续步骤依赖该结果，请重新调用该工具获取。\"}", name);
            case PENDING, RUNNING -> {
                log.warn("渲染到停留在中间态的空结果 TOOL 消息: toolName={}, status={}", name, status);
                yield String.format(
                        "{\"status\": \"INTERRUPTED\", \"error\": \"工具 %s 的执行记录停留在待执行/执行中状态，"
                                + "疑似服务中断导致结果丢失，本条记录不可作为成功依据。如需该结果请重新调用工具。\"}", name);
            }
            default -> String.format(
                    "{\"status\": \"UNKNOWN\", \"error\": \"工具 %s 的结果与状态均缺失，请勿采信本条记录。\"}", name);
        };
    }

    /**
     * 解析并加载消息关联的多模态附件物理数据
     */
    private List<CuteAttachment> loadAttachments(String rawAttachments, AgentContext context, boolean isMultimodal) {
        if (StringUtils.isBlank(rawAttachments) || attachmentContentLoader.isEmpty()) {
            return Collections.emptyList();
        }
        return attachmentContentLoader.get().loadAttachments(rawAttachments, context, isMultimodal);
    }

    /**
     * 为历史用户消息中的附件构建轻量 Markdown 占位符
     */
    private String buildAttachmentPlaceholder(String rawAttachments) {
        if (StringUtils.isBlank(rawAttachments) || attachmentContentLoader.isEmpty()) {
            return null;
        }
        return attachmentContentLoader.get().buildAttachmentPlaceholder(rawAttachments);
    }
}
