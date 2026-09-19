package com.stioc.cute.engine.loop.message;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.stioc.cute.engine.llm.ChatOptionsFactory;
import com.stioc.cute.engine.llm.CuteChat;
import com.stioc.cute.engine.llm.CuteChatFactory;
import com.stioc.cute.engine.llm.types.CuteChatOptions;
import com.stioc.cute.engine.llm.types.CuteChatResponse;
import com.stioc.cute.engine.llm.types.CuteMessage;
import com.stioc.cute.engine.llm.types.CuteMessageRole;
import com.stioc.cute.engine.llm.types.CutePrompt;
import com.stioc.cute.engine.llm.types.Provider;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.loop.core.LoopDataReporter;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.SortDirection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 统一管理会话 Token 用量计算、大日志过滤折叠、以及滑动窗口物理防爆裁剪的 LLM 窗口管理器
 */
@Slf4j
@RequiredArgsConstructor
public class LlmWindowManager {

    private final MessageStore messageStore;
    private final CuteChatFactory chatClientFactory;
    private final ChatOptionsFactory chatOptionsFactory;
    private final MessageHistoryAligner messageHistoryAligner;
    private final MessageDataReporter messageDataReporter;
    private final LoopDataReporter loopDataReporter;

    private static final EncodingRegistry encodingRegistry = Encodings.newDefaultEncodingRegistry();
    private static final Encoding cl100kBaseEncoding = encodingRegistry.getEncoding(EncodingType.CL100K_BASE);

    /**
     * 从当前会话绑定的 Provider 配置读取 contextSize，作为上下文窗口大小。
     * 未配置（null 或 0）时兜底使用 100K。
     */
    private long resolveContextWindow(AgentContext context) {
        try {
            Provider config = chatClientFactory.getProviderConfigForContext(context);
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
        if (text == null || text.isEmpty()) {
            return 0;
        }
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

        List<Message> originalVisibleMsgs = messageStore.listByQuery(MessageQuery.builder()
                .cid(cid)
                .visibleToModel(true)
                .excludedRoles(List.of(MessageRole.SYSTEM))
                .sortField("id")
                .sortDirection(SortDirection.ASC)
                .build());
        List<CuteMessage> originalHistory = messageHistoryAligner.rebuildHistory(context, originalVisibleMsgs);
        long currentTokens = calculateMessageTokens(context, originalHistory);

        log.debug("LlmWindowManager: 当前预估 token = {}, maxWindow = {}, 85% 门限 = {}",
                currentTokens, maxWindow, (long) (maxWindow * 0.85));

        if (currentTokens <= maxWindow * 0.85) {
            log.debug("未超出 85%，不需要压缩");
            return true;
        }

        log.debug("超出 85%，触发同步上下文压缩。当前 token: {}", currentTokens);

        // 在创建压缩占位符和发起大语言模型调用前，先将本轮挂起的用户输入全部标记为 SUCCESS 并广播
        // 从而让前端气泡立即停止呼吸，避免大模型压缩过程耗时较长导致用户消息一直在 PENDING
        messageDataReporter.updatePendingInputsToSuccess(context);

        // 发起调用之前，委托消息数据上报器创建 COMPRESSED 占位消息
        Long compMsgId = messageDataReporter.createCompressedMessage(context);

        // 1. 查找最后一个 ASSISTANT 的位置，以其作为分水岭将消息划分为“归档段”和“保留段”
        int lastAssistantIdx = -1;
        for (int i = originalVisibleMsgs.size() - 1; i >= 0; i--) {
            if (MessageRole.ASSISTANT == originalVisibleMsgs.get(i).getRole()) {
                lastAssistantIdx = i;
                break;
            }
        }

        List<Message> msgsToCompress;
        List<Message> msgsToKeep;

        if (lastAssistantIdx != -1) {
            msgsToCompress = new ArrayList<>(originalVisibleMsgs.subList(0, lastAssistantIdx));
            msgsToKeep = new ArrayList<>(originalVisibleMsgs.subList(lastAssistantIdx, originalVisibleMsgs.size()));
        } else {
            msgsToCompress = new ArrayList<>(originalVisibleMsgs);
            msgsToKeep = new ArrayList<>();
        }

        long msgsToKeepTokens = calculateMessageTokens(context, messageHistoryAligner.rebuildHistory(context, msgsToKeep));
        long msgsToCompressTokens = calculateMessageTokens(context, messageHistoryAligner.rebuildHistory(context, msgsToCompress));

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
                    attempt + 1, (int) (threshold * 100), (long) targetLimit);

            // 克隆并在内存中裁剪，不影响数据库 visible 状态
            List<Message> tempCompressMsgs = new ArrayList<>(msgsToCompress);
            tempCompressMsgs = cropHistoryToThresholdInMemory(context, tempCompressMsgs, msgsToCompressTokens, targetLimit);

            try {
                summaryText = callLlmToCompressInMemory(context, tempCompressMsgs);
                if (StringUtils.isNotBlank(summaryText)) {
                    success = true;
                    break;
                }
            } catch (Exception e) {
                log.warn("第 {} 次压缩大模型调用失败: {}", attempt + 1, e.getMessage());
            }
        }

        if (!success) {
            log.error("上下文压缩失败，已重试2次（共3次尝试）均失败。按状态机助手消息失败处理并提前退出。");
            messageDataReporter.updateCompressedToFailed(context, compMsgId);
            messageDataReporter.recordCompressFailure(context, "【系统错误】上下文窗口溢出，压缩重试 3 次后仍然失败。");
            // 循环终结（压缩失败）：交由数据上报器查库对账后统一收口 loopRunning=0
            loopDataReporter.updateLoopRunningToFinished(context, false);
            return false;
        }

        log.debug("上下文压缩成功，压缩结果长度: {} 字符", summaryText.length());

        // 2. 压缩成功后，委托上报器将归档前半段的消息在数据库中的 visibleToModel 设为 false
        messageDataReporter.archiveMessagesVisibleToModel(context, msgsToCompress);

        // 3. 委托上报器更新压缩消息状态与具体压缩正文并广播更新事件
        messageDataReporter.updateCompressedToSuccess(context, compMsgId, summaryText);

        return true;
    }

    /**
     * 在内存中把归档段消息裁剪到目标 Token 限额以内（不影响数据库的 visibleToModel 状态）。
     * <p>
     * 裁剪顺序语义（决定「模型还能看见什么」，一旦反了会静默丢关键上下文）：
     * <ol>
     *   <li><b>提前分流</b>：未超目标时原样返回，不做任何删除；</li>
     *   <li><b>阶段 1 优先裁 TOOL</b>：TOOL 消息体量最大且价值最低，先删它们；SYSTEM 恒跳过；</li>
     *   <li><b>阶段 2 再从头删</b>：TOOL 删完仍超限时，才从第一条非 SYSTEM 消息开始删，SYSTEM 永不删。</li>
     * </ol>
     * </p>
     * <p>
     * 可见性为 public 系本方法的顺序语义无法从外部黑盒观测（压缩链路的净效果只能证明「裁过」，
     * 无法证明「按 TOOL 优先、SYSTEM 保底」的先后），故开放给同包测试直接断言裁剪结果。
     * 生产调用方仍只有 {@link #manageContextWindowSync} 一处，请勿在业务链路中新开调用点。
     * </p>
     *
     * @param context       会话上下文（本方法不使用其状态，保留以对齐压缩链路的调用形态）
     * @param visibleMsgs   待裁剪的消息列表（会被就地修改）
     * @param initialTokens 裁剪前的 Token 估算总量
     * @param targetLimit   目标 Token 上限
     * @return 裁剪后的消息列表（未超限时与入参同一实例）
     */
    public List<Message> cropHistoryToThresholdInMemory(AgentContext context, List<Message> visibleMsgs, long initialTokens, double targetLimit) {
        long currentTokens = initialTokens;
        if (currentTokens < targetLimit) {
            return visibleMsgs;
        }

        log.warn("内存裁剪: 当前估算 token = {}, 目标上限 = {}", currentTokens, (long) targetLimit);

        // 阶段 1: 优先裁剪 TOOL 消息
        for (int i = 0; i < visibleMsgs.size(); ) {
            if (currentTokens < targetLimit) {
                break;
            }
            Message msg = visibleMsgs.get(i);
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
            Message msg = visibleMsgs.remove(targetIndex);
            long msgEstimated = estimateTokens(msg.getContent());
            currentTokens -= msgEstimated;
        }

        log.debug("内存裁剪完毕，裁剪后内存预估 token: {}", currentTokens);
        return visibleMsgs;
    }

    private String callLlmToCompressInMemory(AgentContext context, List<Message> tempVisibleMsgs) {
        CuteChat cuteChat = chatClientFactory.getCuteChat(context);
        Provider activeConfig = chatClientFactory.getProviderConfigForContext(context);
        CuteChatOptions options = chatOptionsFactory.buildOptions(activeConfig, List.of());

        List<CuteMessage> requestMessages = messageHistoryAligner.rebuildHistory(context, tempVisibleMsgs);
        String compressPrompt = """
                你是一个专门负责进行对话状态压缩与快照持久化的系统助手。请阅读上述的历史对话与工具执行过程，生成一份详尽的「状态检查点与摘要（State Checkpoint & Summary）」。

                你必须在摘要中包含且清晰区分以下三个核心部分：
                1. 【用户的核心意图与最终目标】：用户最初发起的任务诉求、核心目标或需要解决的关键问题是什么。
                2. 【已完成的工作与进展总结】：目前为止已经执行了哪些关键操作、获取了哪些核心信息、取得了哪些阶段性成果或得出了什么结论。
                3. 【当前进展与后续待办】：我们当前处于任务的哪个阶段？如果最近有正在执行或刚刚返回结果的工具调用，请务必指出其背景与关联结论，明确大模型接下来的工作大方向与具体行动，引导其继续调用工具推进，防止大模型在接收到单个工具返回后脱离整体意图。

                请尽量详细。用清晰的标题划分这三点，不要包含任何多余的客套或解释。
                """;
        requestMessages.add(CuteMessage.builder()
                .role(CuteMessageRole.USER)
                .content(compressPrompt)
                .build());

        String llmCallId = UUID.randomUUID().toString();
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
        if (response != null && StringUtils.isNotBlank(response.getContent())) {
            return response.getContent();
        }
        return null;
    }
}
