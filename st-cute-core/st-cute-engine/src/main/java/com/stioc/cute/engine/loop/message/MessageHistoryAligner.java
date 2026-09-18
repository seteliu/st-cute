package com.stioc.cute.engine.loop.message;

import com.stioc.cute.engine.llm.CuteChatFactory;
import com.stioc.cute.engine.llm.types.CuteMessage;
import com.stioc.cute.engine.llm.types.CuteMessageRole;
import com.stioc.cute.engine.llm.types.CuteToolCall;
import com.stioc.cute.engine.loop.types.MessageIndexInfo;
import com.stioc.cute.engine.loop.types.MessagePayload;
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
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 历史消息与输入对齐工具（Message History Aligner）。
 * <p>
 * 专门负责两项关键对齐工作：
 * 1. 【运行输入对齐】：将数据库末尾消息对齐到下一次模型推理步骤，消费挂起输入，创建/激活当前活跃 ASSISTANT 占位符；
 * 2. 【历史数据还原】：从数据库还原大模型可见的全部历史消息，处理多模态附件、空结果状态感知占位符、COMPRESSED 排序与 SYSTEM 提示词装配。
 * 还原出的每条消息（含引擎合成的 SYSTEM 首条）均经过 {@link MessageInterceptor} 拦截器链（order 升序），
 * 正文与附件的宿主加工细节（时间戳追加、附件装载/占位等）全部下沉宿主拦截器实现。
 * </p>
 * <p>
 * 两参数 {@link #rebuildHistory(AgentContext, List)} 信任调用方传入的 dbMsgs 已完成
 * visibleToModel 过滤与 SYSTEM 角色排除（单参数入口与压缩链路均在查询条件中完成），
 * 方法内不做二次过滤，仅做 COMPRESSED 前置排序与当前轮占位符排除。
 * </p>
 */
@Slf4j
public class MessageHistoryAligner {

    private final MessageStore messageStore;
    private final SystemPromptAssembler systemPromptAssembler;
    private final CuteChatFactory chatClientFactory;
    private final List<MessageInterceptor> messageInterceptors;
    private final MessageDataReporter messageDataReporter;

    public MessageHistoryAligner(MessageStore messageStore,
                                 SystemPromptAssembler systemPromptAssembler,
                                 CuteChatFactory chatClientFactory,
                                 List<MessageInterceptor> messageInterceptors,
                                 MessageDataReporter messageDataReporter) {
        this.messageStore = messageStore;
        this.systemPromptAssembler = systemPromptAssembler;
        this.chatClientFactory = chatClientFactory;
        // 构造时按 order 升序排定一次，历史重建每次直接复用，避免重复排序
        this.messageInterceptors = messageInterceptors == null || messageInterceptors.isEmpty()
                ? List.of()
                : messageInterceptors.stream()
                        .sorted(Comparator.comparingInt(MessageInterceptor::order))
                        .collect(Collectors.toList());
        this.messageDataReporter = messageDataReporter;
    }

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
     * 对话历史还原与同步（从数据库拉取大模型可见的全部历史消息）。
     * 数据库残留的 SYSTEM 角色消息与还原渲染互斥，直接在查询条件中排除。
     */
    public List<CuteMessage> rebuildHistory(AgentContext context) {
        Long cid = context.getCid();
        List<Message> allMsgs = messageStore.listByQuery(MessageQuery.builder()
                .cid(cid)
                .visibleToModel(true)
                .excludedRoles(List.of(MessageRole.SYSTEM))
                .sortField("id")
                .sortDirection(SortDirection.ASC)
                .build());
        return rebuildHistory(context, allMsgs);
    }

    /**
     * 基于指定消息集合还原大模型历史会话。
     * 信任入参 dbMsgs 已完成 visibleToModel 过滤与 SYSTEM 角色排除，方法内不再二次过滤。
     */
    public List<CuteMessage> rebuildHistory(AgentContext context, List<Message> allMsgs) {
        Long excludeAssistantMsgId = context.getActiveAssistantMsgId();
        List<CuteMessage> history = new ArrayList<>();
        List<MessageInterceptor> interceptors = messageInterceptors;

        Provider activeConfig = chatClientFactory.getProviderConfigForContext(context);
        boolean isMultimodal = activeConfig != null && Boolean.TRUE.equals(activeConfig.getMultimodal());

        // COMPRESSED 压缩摘要消息前置，其他消息按 ID 升序紧随其后
        List<Message> dbMsgs = new ArrayList<>(allMsgs);
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

        // 角色索引信息：一次性构建，供拦截器感知各角色首/末条消息序位
        MessageIndexInfo indexInfo = MessageIndexInfo.buildFrom(dbMsgs, excludeAssistantMsgId);

        // 无条件把最新的系统提示词作为历史消息列表的第一条（大模型上下文的第一条），
        // 单独构建载体（无 thisMsg/allMsgs/indexInfo，引擎合成消息不参与序位索引），同样经过拦截器链
        MessagePayload systemPayload = MessagePayload.builder()
                .multimodal(isMultimodal)
                .context(context)
                .content(systemPromptAssembler.assemble(context))
                .build();
        applyInterceptors(interceptors, systemPayload);
        history.add(CuteMessage.builder().role(CuteMessageRole.SYSTEM).content(systemPayload.getContent()).build());

        for (Message msg : dbMsgs) {
            // 排除当前轮占位符
            if (excludeAssistantMsgId != null && msg.getId().equals(excludeAssistantMsgId)) {
                continue;
            }

            MessageStatus mStatus = msg.getStatus();
            if (mStatus == null) {
                mStatus = MessageStatus.SUCCESS;
            }

            // 通用骨架：thisMsg 与兜底 content 逐条必设，allMsgs/indexInfo/multimodal/context 本次重建恒定；
            // 各角色分支仅需覆写自身差异字段（如 TOOL 的状态感知结果、BRANCH 的来源前缀）
            Supplier<MessagePayload.MessagePayloadBuilder> payloadBase = () -> MessagePayload.builder()
                    .thisMsg(msg)
                    .content(msg.getContent() != null ? msg.getContent() : "")
                    .allMsgs(dbMsgs)
                    .indexInfo(indexInfo)
                    .multimodal(isMultimodal)
                    .context(context);

            if (MessageRole.USER == msg.getRole()) {
                if (MessageStatus.CANCELED == mStatus) {
                    continue;
                }

                MessagePayload payload = payloadBase.get().build();
                applyInterceptors(interceptors, payload);

                history.add(CuteMessage.builder()
                        .role(CuteMessageRole.USER)
                        .content(payload.getContent())
                        .attachments(payload.getAttachments())
                        .build());
            } else if (MessageRole.ASSISTANT == msg.getRole()) {
                if (MessageStatus.FAILED == mStatus || MessageStatus.CANCELED == mStatus) {
                    continue;
                }
                List<CuteToolCall> toolCallsList = new ArrayList<>();
                if (StringUtils.isNotBlank(msg.getToolCalls())) {
                    toolCallsList = new ArrayList<>(ToolCallCodec.parseList(msg.getToolCalls()));
                }

                MessagePayload payload = payloadBase.get().build();
                applyInterceptors(interceptors, payload);

                history.add(CuteMessage.builder()
                        .role(CuteMessageRole.ASSISTANT)
                        .content(payload.getContent())
                        .reasoningContent(msg.getReasoningContent())
                        .toolCalls(toolCallsList)
                        .build());

            } else if (MessageRole.TOOL == msg.getRole()) {
                String rawResult = msg.getContent();
                MessageStatus toolStatus = msg.getStatus() != null ? msg.getStatus() : MessageStatus.SUCCESS;
                if (MessageStatus.REJECTED == toolStatus) {
                    rawResult = "{\"error\": \"Permission denied by user.\"}";
                } else if (MessageStatus.CANCELED == toolStatus) {
                    rawResult = "{\"error\": \"Execution canceled by user.\"}";
                } else if (MessageStatus.WAITING_APPROVAL == toolStatus) {
                    rawResult = "{\"status\": \"WAITING_APPROVAL\", \"message\": \"该工具调用正在等待用户审批，尚未执行。\"}";
                }

                String toolCallId = null;
                String toolName = null;
                if (StringUtils.isNotBlank(msg.getToolCalls())) {
                    CuteToolCall call = ToolCallCodec.parseSingle(msg.getToolCalls());
                    toolCallId = StringUtils.isNotBlank(call.getId()) ? call.getId() : null;
                    toolName = StringUtils.isNotBlank(call.getName()) ? call.getName() : null;
                }

                if (StringUtils.isBlank(rawResult)) {
                    rawResult = buildEmptyToolResultPlaceholder(msg.getStatus(), toolName);
                }

                // TOOL 分支覆写 content：状态感知结果（拒批/取消/待审批占位与空结果兜底）优先于原文
                MessagePayload payload = payloadBase.get()
                        .content(rawResult)
                        .build();
                applyInterceptors(interceptors, payload);

                history.add(CuteMessage.builder()
                        .role(CuteMessageRole.TOOL)
                        .toolCallId(toolCallId)
                        .toolName(toolName)
                        .content(payload.getContent())
                        .attachments(payload.getAttachments())
                        .build());
            } else if (MessageRole.BRANCH == msg.getRole()) {
                // 子 Agent 汇报消息：以 USER 角色发给大模型，内容前拼接来源前缀
                if (MessageStatus.CANCELED != mStatus) {
                    // BRANCH 分支覆写 content：拼接来源前缀
                    MessagePayload payload = payloadBase.get()
                            .content("来自其他Agent：\n" + msg.getContent())
                            .build();
                    applyInterceptors(interceptors, payload);

                    history.add(CuteMessage.builder()
                            .role(CuteMessageRole.USER)
                            .content(payload.getContent())
                            .build());
                }
            } else if (MessageRole.COMPRESSED == msg.getRole()) {
                if (MessageStatus.SUCCESS == mStatus) {
                    MessagePayload payload = payloadBase.get().build();
                    applyInterceptors(interceptors, payload);

                    history.add(CuteMessage.builder()
                            .role(CuteMessageRole.USER)
                            .content(payload.getContent())
                            .build());
                }
            }
        }

        return history;
    }

    /**
     * 将消息载体依次交由拦截器链加工（order 已在链构建时排好序）
     */
    private void applyInterceptors(List<MessageInterceptor> interceptors, MessagePayload payload) {
        for (MessageInterceptor interceptor : interceptors) {
            interceptor.intercept(payload);
        }
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
}
