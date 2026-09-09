package com.stioc.cute.engine.support;

import com.stioc.cute.engine.llm.types.CuteChatOptions;
import com.stioc.cute.engine.llm.types.CuteMessage;
import com.stioc.cute.engine.llm.types.CuteMessageRole;
import com.stioc.cute.engine.llm.types.CutePrompt;
import com.stioc.cute.engine.llm.types.CuteChatResponse;
import com.stioc.cute.engine.llm.CuteChat;
import com.stioc.cute.engine.llm.CuteChatFactory;
import com.stioc.cute.engine.llm.ChatOptionsFactory;
import com.stioc.cute.engine.llm.types.Provider;
import com.stioc.cute.engine.event.AgentEventFactory;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.loop.core.AgentContextManager;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.engine.store.types.SortDirection;
import com.stioc.cute.engine.common.AgentEngineLock;
import java.util.concurrent.locks.Lock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 自动为新创建的会话命名的助手组件
 */
@Slf4j
@RequiredArgsConstructor
public class ChatNamingHelper {

    private final ConversationStore conversationStore;
    private final MessageStore messageStore;
    private final CuteChatFactory chatClientFactory;
    private final AgentContextManager agentContextManager;
    private final ChatOptionsFactory chatOptionsFactory;
    private final String defaultConversationTitle;

    /**
     * 判断会话是否为“新会话”，如果是则通过 AI 自动命名
     *
     * @param cid 会话 ID
     */
    public void autoRenameChatIfNew(Long cid) {
        Lock lock = AgentEngineLock.CID_NAMING_STRIPED.get(cid);
        if (!lock.tryLock()) {
            log.debug("[ChatNamingHelper] 未能获取会话自动命名锁，直接退出: cid={}", cid);
            return;
        }
        try {
            Conversation conversation = conversationStore.getById(cid);
            if (conversation == null) {
                return;
            }
            // 如果会话名称不是初始标题，说明已经命名过，跳过
            String expectedInitialTitle = StringUtils.isNotBlank(defaultConversationTitle) ? defaultConversationTitle : "新会话";
            if (!Objects.equals(expectedInitialTitle, conversation.getTitle())) {
                return;
            }

            // 获取该会话下的所有历史消息
            List<Message> messages = messageStore.listByQuery(MessageQuery.builder()
                    .cid(cid)
                    .sortDirection(SortDirection.ASC)
                    .build());
            if (messages.isEmpty()) {
                return;
            }

            StringBuilder historyBuilder = new StringBuilder();
            for (Message msg : messages) {
                // 筛选出前端可见且包含实际内容的消息（主要是 USER/ASSISTANT）
                if (msg.getVisibleToUser() != null && msg.getVisibleToUser()
                        && StringUtils.isNotBlank(msg.getContent())) {
                    historyBuilder.append(msg.getRole().name())
                            .append(": ")
                            .append(msg.getContent())
                            .append("\n");
                }
            }
            String historyText = historyBuilder.toString().trim();
            if (historyText.isEmpty()) {
                return;
            }

            log.debug("[ChatNamingHelper] 监听到新会话发送消息，开始异步提取会话标题... cid={}", cid);

            AgentContext context = agentContextManager.getOrCreateContext(cid);
            CuteChat cuteChat = chatClientFactory.getCuteChat(context);
            Provider activeConfig = chatClientFactory.getProviderConfigForContext(context);
            CuteChatOptions options = chatOptionsFactory.buildOptions(activeConfig, List.of());

            List<CuteMessage> requestMessages = new ArrayList<>();

            // 专门设计的短标题生成提示词
            String systemPrompt = """
                    你是一个会话主题提取助手。请根据用户提供的一段历史对话内容，为其总结并生成一个非常简短、清晰、生动的会话标题。
                    要求：
                    1. 标题必须紧扣对话的核心主题（例如用户的核心诉求、咨询的问题）。
                    2. 标题要非常简短，通常在 2 到 6 个字左右，最多不超过 10 个字，不需要任何标点符号。
                    3. 直接返回总结出的标题，禁止包含任何多余的客套、前言、解释或格式包裹（如 Markdown 语法或双引号）。
                    """;
            requestMessages.add(CuteMessage.builder()
                    .role(CuteMessageRole.SYSTEM)
                    .content(systemPrompt)
                    .build());

            String userPrompt = "以下是我们的历史对话内容：\n---\n" + historyText + "\n---\n请为本会话生成标题：";
            requestMessages.add(CuteMessage.builder()
                    .role(CuteMessageRole.USER)
                    .content(userPrompt)
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
                String newTitle = response.getContent().trim();

                // 进一步净化 AI 生成的标题，剔除首尾的多余引号、多行文本或长标题
                newTitle = newTitle.replace("\"", "").replace("'", "").replace("“", "").replace("”", "").trim();
                if (newTitle.contains("\n")) {
                    newTitle = newTitle.split("\n")[0].trim();
                }
                if (newTitle.length() > 15) {
                    newTitle = newTitle.substring(0, 15).trim();
                }

                if (StringUtils.isNotBlank(newTitle)) {
                    ConversationPatch updatePayload = new ConversationPatch(cid)
                            .title(newTitle);
                    // 发起 CONVERSATION_UPDATE 事件，这会触发：写库 -> 同步内存缓存 -> 发送 WebSocket 给前端
                    context.publishEvent(AgentEventFactory.createConversationUpdate(context, updatePayload));
                    log.info("[ChatNamingHelper] 会话自动重命名成功: cid={}, 新标题={}", cid, newTitle);
                }
            }
        } catch (Exception e) {
            log.error("[ChatNamingHelper] 自动命名会话发生异常: cid={}", cid, e);
        } finally {
            lock.unlock();
        }
    }
}
