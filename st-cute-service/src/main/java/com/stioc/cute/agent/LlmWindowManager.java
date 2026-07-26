package com.stioc.cute.agent;

import com.stioc.cute.agent.access.AgentContext;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.message.access.MessageRole;
import com.stioc.cute.message.access.MessageService;
import com.stioc.cute.message.access.MessageStatus;
import com.stioc.cute.provider.ProviderService;
import com.stioc.cute.platform.contract.Provider;
import com.stioc.cute.prompt.SystemPromptGenerator;
import com.stioc.cute.message.access.MessageEntity;
import com.stioc.cute.conversation.access.ConversationService;
import com.stioc.cute.skill.access.Skill;
import com.stioc.cute.llm.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.stioc.cute.agent.event.AgentEventFactory;
import com.stioc.cute.agent.event.AgentEventType;
import com.stioc.cute.conversation.access.ConversationEntity;
import com.mybatisflex.core.util.UpdateEntity;

import jakarta.annotation.Resource;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;


/**
 * 统一管理会话 Token 用量计算、大日志过滤折叠、以及滑动窗口物理防爆裁剪的 LLM 窗口管理器
 */
@Slf4j
@Component
public class LlmWindowManager {

    @Resource
    private MessageService messageService;
    @Resource
    private ProviderService providerService;
    @Resource
    private ConversationService conversationService;
    @Resource
    private SystemPromptGenerator systemPromptGenerator;
    @Resource
    private ChatOptionsFactory chatOptionsFactory;

    /**
     * 统一封装的系统提示词构建方法，生成最终完整的 System Prompt 包含 Custom Instructions。
     */
    public String buildFullSystemPrompt(Long cid, AgentContext context) {
        Provider activeConfig = providerService.getProviderConfigForContext(context);
        return systemPromptGenerator.generateSystemPrompt(
                activeConfig, 
                getProjectBasePath(context), 
                context.getPermissionMode(),
                context.getSkills(),
                context.getRules()
        );
    }

    private static final EncodingRegistry encodingRegistry = Encodings.newDefaultEncodingRegistry();
    private static final Encoding cl100kBaseEncoding = encodingRegistry.getEncoding(EncodingType.CL100K_BASE);

    /**
     * 对话历史还原与同步（从 SQLite 数据库拉取大模型可见的全部历史消息）
     */
    public List<CuteMessage> rebuildHistory(AgentContext context) {
        Long cid = context.getCid();
        List<MessageEntity> allMsgs = messageService.findByCidAndVisibleToModelTrueOrderByIdAsc(cid);
        return rebuildHistory(context, allMsgs);
    }

    public List<CuteMessage> rebuildHistory(AgentContext context, List<MessageEntity> allMsgs) {
        Long cid = context.getCid();
        Long excludeAssistantMsgId = context.getActiveAssistantMsgId();
        List<CuteMessage> history = new ArrayList<>();
        String systemPrompt = buildFullSystemPrompt(cid, context);

        // 1. 无条件把最新的系统提示词作为历史消息列表的第一条（大模型上下文的第一条）
        history.add(CuteMessage.builder().role(CuteMessageRole.SYSTEM).content(systemPrompt).build());

        // 2. 过滤出 visibleToModel == true 的消息，同时排除 SYSTEM 角色消息（屏蔽数据库残留的系统消息）
        List<MessageEntity> dbMsgs = allMsgs.stream()
                .filter(m -> Boolean.TRUE.equals(m.getVisibleToModel()) && MessageRole.SYSTEM != m.getRole())
                .collect(Collectors.toList());

        // 3. 对 dbMsgs 进行重新排序以确保 MessageRole.COMPRESSED 消息排在最前面，其他普通消息紧随其后。
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

        for (MessageEntity dbMsg : dbMsgs) {
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
                history.add(CuteMessage.builder().role(CuteMessageRole.USER).content(dbMsg.getContent()).build());
            } else if (MessageRole.ASSISTANT == dbMsg.getRole()) {
                if (MessageStatus.FAILED == mStatus || MessageStatus.CANCELED == mStatus) {
                    continue;
                }
                List<CuteToolCall> toolCallsList = new ArrayList<>();
                if (StringUtils.hasText(dbMsg.getToolCalls())) {
                    try {
                        toolCallsList = JSON.parseArray(dbMsg.getToolCalls(), CuteToolCall.class);
                    } catch (Exception e) {
                        log.warn("解析缓存工具调用参数异常", e);
                    }
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
                }

                if (!StringUtils.hasText(rawResult)) {
                    rawResult = "msg miss";
                }

                String toolCallId = null;
                String toolName = null;
                if (StringUtils.hasText(dbMsg.getToolCalls())) {
                    try {
                        JSONObject obj = JSON.parseObject(dbMsg.getToolCalls().trim());
                        toolCallId = obj.getString("id");
                        toolName = obj.getString("name");
                    } catch (Exception e) {
                        // ignore
                    }
                }

                history.add(CuteMessage.builder()
                        .role(CuteMessageRole.TOOL)
                        .toolCallId(toolCallId)
                        .toolName(toolName)
                        .content(rawResult)
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
     * 从当前会话绑定的 Provider 配置读取 contextSize，作为上下文窗口大小。
     * 未配置（null 或 0）时兜底使用 100K。
     */
    private long resolveContextWindow(AgentContext context) {
        try {
            Provider config = providerService.getProviderConfigForContext(context);
            if (config != null && config.getContextSize() != null && config.getContextSize() > 0) {
                return config.getContextSize();
            }
        } catch (Exception e) {
            log.warn("读取 Provider contextSize 失败，使用默认窗口大小: {}", e.getMessage());
        }
        return 100_000L;
    }

    /**
     * 估算一组消息列表的 Token 总数
     */
    public long calculateMessageTokens(List<CuteMessage> messages) {
        long total = 0;
        for (CuteMessage msg : messages) {
            total += estimateTokens(msg.getContent());
        }
        return total;
    }

    /**
     * 结合上一次调用的真实 Token 消耗，精准预估一组消息列表的 Token 总数
     */
    public long calculateMessageTokens(AgentContext context, List<CuteMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        if (context == null) {
            return calculateMessageTokens(messages);
        }

        // 1. 寻找最后一个 ASSISTANT 消息的索引
        int lastAssistantIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (CuteMessageRole.ASSISTANT == messages.get(i).getRole()) {
                lastAssistantIdx = i;
                break;
            }
        }

        // 2. 如果不存在 ASSISTANT 消息，直接用 estimateTokens 估算全部消息
        if (lastAssistantIdx == -1) {
            return calculateMessageTokens(messages);
        }

        // 3. 存在 ASSISTANT 消息，直接使用 context 中已有的缓存值
        long inputTokens = context.getInputTokens();
        long outputTokens = context.getOutputTokens();

        if (inputTokens <= 0) {
            return calculateMessageTokens(messages);
        }

        // 4. lastAssistantIdx 之后的新增消息（如 TOOL、USER、BRANCH），使用 estimateTokens 估算
        long newPartTokens = 0;
        for (int i = lastAssistantIdx + 1; i < messages.size(); i++) {
            newPartTokens += estimateTokens(messages.get(i).getContent());
        }

        // 5. 最后一个 ASSISTANT 及其之前的所有消息直接使用真实 token 之和
        return inputTokens + outputTokens + newPartTokens;
    }

    /**
     * 使用 jtokkit 估算单段文本的 Token 数量
     */
    public long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            return cl100kBaseEncoding.countTokens(text);
        } catch (Exception e) {
            log.warn("jtokkit token 计算异常，fallback 至字符数/2 估算: {}", e.getMessage());
            return text.length() / 2 + 1;
        }
    }



    /**
     * 同步上下文防爆管理与大模型摘要压缩
     *
     * @return 压缩成功或无需压缩返回 true，如果失败返回 false
     */
    public boolean manageContextWindowSync(AgentContext context) {
        Long cid = context.getCid();
        long maxWindow = resolveContextWindow(context);

        List<MessageEntity> originalVisibleMsgs = messageService.findByCidAndVisibleToModelTrueOrderByIdAsc(cid);
        List<CuteMessage> originalHistory = rebuildHistory(context, originalVisibleMsgs);
        long currentTokens = calculateMessageTokens(context, originalHistory);

        log.debug("LlmWindowManager: 当前预估 token = {}, maxWindow = {}, 85% 门限 = {}",
                currentTokens, maxWindow, (long)(maxWindow * 0.85));

        if (currentTokens <= maxWindow * 0.85) {
            log.debug("未超出 85%，不需要压缩");
            return true;
        }

        log.debug("超出 85%，触发同步上下文压缩。当前 token: {}", currentTokens);

        // 在创建压缩占位符和发起大语言模型调用前，先将本轮挂起的用户输入全部标记为 SUCCESS 并广播
        // 从而让前端气泡立即停止呼吸，避免大模型压缩过程耗时较长导致用户消息一直在 PENDING
        List<MessageEntity> pendingInputs = messageService.findByCidOrderByIdAsc(cid).stream()
                .filter(m -> (MessageRole.USER == m.getRole() || MessageRole.BRANCH == m.getRole()) && MessageStatus.PENDING == m.getStatus())
                .collect(Collectors.toList());
        for (MessageEntity input : pendingInputs) {
            input.setStatus(MessageStatus.SUCCESS);
            context.publishEvent(AgentEventFactory.createMessageUpdate(context, input));
        }

        // 发起调用之前，先创建compressed消息占位
        MessageEntity compMsg = MessageEntity.builder()
                .cid(cid)
                .role(MessageRole.COMPRESSED)
                .content("")
                .status(MessageStatus.RUNNING)
                .visibleToUser(true)
                .visibleToModel(true)
                .createdAt(LocalDateTime.now())
                .build();
        context.publishEvent(AgentEventFactory.createMessageCreate(context, compMsg));

        // 1. 查找最后一个 ASSISTANT 的位置，以其作为分水岭将消息划分为“归档段”和“保留段”
        int lastAssistantIdx = -1;
        for (int i = originalVisibleMsgs.size() - 1; i >= 0; i--) {
            if (MessageRole.ASSISTANT == originalVisibleMsgs.get(i).getRole()) {
                lastAssistantIdx = i;
                break;
            }
        }

        List<MessageEntity> msgsToCompress;
        List<MessageEntity> msgsToKeep;

        if (lastAssistantIdx != -1) {
            msgsToCompress = new ArrayList<>(originalVisibleMsgs.subList(0, lastAssistantIdx));
            msgsToKeep = new ArrayList<>(originalVisibleMsgs.subList(lastAssistantIdx, originalVisibleMsgs.size()));
        } else {
            msgsToCompress = new ArrayList<>(originalVisibleMsgs);
            msgsToKeep = new ArrayList<>();
        }

        long msgsToKeepTokens = calculateMessageTokens(context, rebuildHistory(context, msgsToKeep));
        long msgsToCompressTokens = calculateMessageTokens(context, rebuildHistory(context, msgsToCompress));

        double[] thresholds = {0.95, 0.90, 0.85};
        boolean success = false;
        String summaryText = null;

        for (int attempt = 0; attempt < 3; attempt++) {
            double threshold = thresholds[attempt];
            // 计算归档段允许的最大 Token 限额（总限额减去保留段所占 Token）
            double targetLimit = maxWindow * threshold - msgsToKeepTokens;
            // 兜底保障压缩部分至少拥有 20% 窗口空间
            targetLimit = Math.max(targetLimit, maxWindow * 0.2);

            log.debug("尝试压缩，第 {} 次重试/尝试，历史裁剪门限: {}%, targetLimit={}",
                    attempt + 1, (int)(threshold * 100), (long)targetLimit);

            // 克隆并在内存中裁剪，不影响数据库 visible 状态
            List<MessageEntity> tempCompressMsgs = new ArrayList<>(msgsToCompress);
            tempCompressMsgs = cropHistoryToThresholdInMemory(context, tempCompressMsgs, msgsToCompressTokens, targetLimit);

            try {
                summaryText = callLlmToCompressInMemory(context, tempCompressMsgs);
                if (StringUtils.hasText(summaryText)) {
                    success = true;
                    break;
                }
            } catch (Exception e) {
                log.warn("第 {} 次压缩大模型调用失败: {}", attempt + 1, e.getMessage());
            }
        }

        if (!success) {
            log.error("上下文压缩失败，已重试2次（共3次尝试）均失败。按状态机助手消息失败处理并提前退出。");
            compMsg.setStatus(MessageStatus.FAILED);
            context.publishEvent(AgentEventFactory.createMessageUpdate(context, compMsg));
            handleCompressFailure(context);
            return false;
        }

        log.debug("上下文压缩成功，压缩结果长度: {} 字符", summaryText.length());

        // 2. 压缩成功后，仅将归档前半段的消息在数据库中的 visibleToModel 设为 false
        for (MessageEntity m : msgsToCompress) {
            messageService.updateMessageVisibleToModel(cid, m.getId(), false);
        }

        // 3. 更新压缩消息状态与具体压缩正文并广播更新事件
        compMsg.setContent("[System Memory Summary]: " + summaryText);
        compMsg.setStatus(MessageStatus.SUCCESS);
        context.publishEvent(AgentEventFactory.createMessageUpdate(context, compMsg));

        return true;
    }

    private List<MessageEntity> cropHistoryToThresholdInMemory(AgentContext context, List<MessageEntity> visibleMsgs, long initialTokens, long maxWindow, double threshold) {
        return cropHistoryToThresholdInMemory(context, visibleMsgs, initialTokens, maxWindow * threshold);
    }

    private List<MessageEntity> cropHistoryToThresholdInMemory(AgentContext context, List<MessageEntity> visibleMsgs, long initialTokens, double targetLimit) {
        long currentTokens = initialTokens;
        if (currentTokens < targetLimit) {
            return visibleMsgs;
        }

        log.warn("内存裁剪: 当前估算 token = {}, 目标上限 = {}", currentTokens, (long)targetLimit);

        // 阶段 1: 优先裁剪 TOOL 消息
        for (int i = 0; i < visibleMsgs.size(); ) {
            if (currentTokens < targetLimit) {
                break;
            }
            MessageEntity msg = visibleMsgs.get(i);
            if (MessageRole.SYSTEM == msg.getRole()) {
                i++;
                continue;
            }
            if (MessageRole.TOOL == msg.getRole()) {
                visibleMsgs.remove(i);
                long msgEstimated = estimateTokens(msg.getContent());
                currentTokens -= msgEstimated;
            } else {
                i++;
            }
        }

        // 阶段 2: 如果 TOOL 消息剪完了，还是大于限制，就从第一条非系统提示词开始裁剪
        while (currentTokens >= targetLimit) {
            int targetIndex = -1;
            for (int i = 0; i < visibleMsgs.size(); i++) {
                if (MessageRole.SYSTEM != visibleMsgs.get(i).getRole()) {
                    targetIndex = i;
                    break;
                }
            }
            if (targetIndex == -1) {
                break;
            }
            MessageEntity msg = visibleMsgs.remove(targetIndex);
            long msgEstimated = estimateTokens(msg.getContent());
            currentTokens -= msgEstimated;
        }

        log.debug("内存裁剪完毕，裁剪后内存预估 token: {}", currentTokens);
        return visibleMsgs;
    }

    private String callLlmToCompressInMemory(AgentContext context, List<MessageEntity> tempVisibleMsgs) {
        Long cid = context.getCid();
        CuteChat cuteChat = providerService.getCuteChat(context);
        Provider activeConfig = providerService.getProviderConfigForContext(context);
        CuteChatOptions options = chatOptionsFactory.buildOptions(activeConfig, List.of());

        List<CuteMessage> requestMessages = rebuildHistory(context, tempVisibleMsgs);
        String compressPrompt = """
                你是一个专门负责进行对话状态压缩与快照持久化的系统助手。请阅读上述的历史对话与工具执行过程，生成一份详尽的「状态检查点与摘要（State Checkpoint & Summary）」。

                你必须在摘要中包含且清晰区分以下三个核心部分：
                1. 【用户的核心意图与最终目标】：用户最初发起的任务、修改目标或需要解决的核心问题是什么。
                2. 【已完成的工作与修改总结】：目前为止我们已经定位了哪些文件，进行了什么修改（列出修改的文件和逻辑），或者得出了什么结论。
                3. 【当前正在干嘛与后续待办】：我们当前处于任务的哪个阶段？如果最近有正在执行或刚刚返回结果的工具调用，请务必指出其背景（例如：“刚刚通过read_file读取了文件X，下一步应当基于此内容开始分析Y并修改Z”），明确大模型接下来的工作大方向，引导其继续调用工具，防止大模型在接收到单个工具返回后脱离整体意图。

                请尽量详细。用清晰的标题划分这三点，不要包含任何多余的客套或解释。
                """;
        requestMessages.add(CuteMessage.builder()
                .role(CuteMessageRole.USER)
                .content(compressPrompt)
                .build());

        String llmCallId = java.util.UUID.randomUUID().toString();
        CutePrompt prompt = CutePrompt.builder()
                .messages(requestMessages)
                .options(options)
                .callListener(call -> context.registerLlmCall(llmCallId, call, options.getModel()))
                .build();

        CuteChatResponse response;
        try {
            response = cuteChat.call(prompt);
        } finally {
            context.unregisterLlmCall(llmCallId);
        }
        if (response != null && StringUtils.hasText(response.getContent())) {
            return response.getContent();
        }
        return null;
    }

    private void handleCompressFailure(AgentContext context) {
        Long cid = context.getCid();
        MessageEntity lastMsg = messageService.findLastMessage(cid).orElse(null);
        if (lastMsg != null) {
            if (MessageRole.USER == lastMsg.getRole() || MessageRole.BRANCH == lastMsg.getRole()) {
                if (MessageStatus.PENDING == lastMsg.getStatus()) {
                    MessageEntity updateLast = UpdateEntity.of(MessageEntity.class);
                    updateLast.setId(lastMsg.getId());
                    updateLast.setRole(lastMsg.getRole());
                    updateLast.setStatus(MessageStatus.SUCCESS);
                    updateLast.setContent(lastMsg.getContent());
                    context.publishEvent(AgentEventFactory.createMessageUpdate(context, updateLast));
                }
            }
        }

        String errorMsg = "【系统错误】上下文窗口溢出，压缩重试 3 次后仍然失败。";
        MessageEntity astMsg = MessageEntity.builder()
                .cid(cid)
                .role(MessageRole.ASSISTANT)
                .content(errorMsg)
                .status(MessageStatus.FAILED)
                .createdAt(LocalDateTime.now())
                .build();
        context.publishEvent(AgentEventFactory.createMessageCreate(context, astMsg));
        Long activeAssistantMsgId = astMsg.getId();
        context.setActiveAssistantMsgId(activeAssistantMsgId);

        ConversationEntity loopEndPayload = UpdateEntity.of(ConversationEntity.class);
        loopEndPayload.setId(cid);
        loopEndPayload.setLoopRunning(0);
        context.publishEvent(AgentEventFactory.createConversationUpdate(context, loopEndPayload));
    }

    private String getProjectBasePath(AgentContext context) {
        if (context == null) {
            return null;
        }
        if (StringUtils.hasText(context.getWorktreePath())) {
            return context.getWorktreePath();
        }
        return conversationService.getProjectPath(context.getCid());
    }
}
