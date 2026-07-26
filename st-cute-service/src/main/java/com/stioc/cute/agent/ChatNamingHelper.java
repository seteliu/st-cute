package com.stioc.cute.agent;

import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.agent.access.AgentContextManager;
import com.stioc.cute.agent.event.AgentEventFactory;
import com.stioc.cute.conversation.access.ConversationEntity;
import com.stioc.cute.conversation.access.ConversationService;
import com.stioc.cute.message.access.MessageEntity;
import com.stioc.cute.message.access.MessageService;
import com.stioc.cute.provider.ProviderService;
import com.stioc.cute.platform.contract.Provider;
import com.stioc.cute.llm.*;
import com.mybatisflex.core.util.UpdateEntity;
import com.google.common.util.concurrent.Striped;
import java.util.concurrent.locks.Lock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 自动为新创建的会话命名的助手组件
 */
@Slf4j
@Component
public class ChatNamingHelper {

    @Resource
    private ConversationService conversationService;
    @Resource
    private MessageService messageService;
    @Resource
    private ProviderService providerService;
    @Resource
    private AgentContextManager agentContextManager;
    @Resource
    private ChatOptionsFactory chatOptionsFactory;

    private final Striped<Lock> namingLock = Striped.lock(64);

    /**
     * 判断会话是否为“新会话”，如果是则通过 AI 自动命名
     *
     * @param cid 会话 ID
     */
    public void autoRenameChatIfNew(Long cid) {
        Lock lock = namingLock.get("naming:" + cid);
        if (!lock.tryLock()) {
            log.debug("[ChatNamingHelper] 未能获取会话自动命名锁，直接退出: cid={}", cid);
            return;
        }
        try {
            Optional<ConversationEntity> conversationOpt = conversationService.findById(cid);
            if (conversationOpt.isEmpty()) {
                return;
            }
            ConversationEntity conversation = conversationOpt.get();
            // 如果会话名称不是“新会话”，说明已经命名过，跳过
            if (!"新会话".equals(conversation.getTitle())) {
                return;
            }

            // 获取该会话下的所有历史消息
            List<MessageEntity> messages = messageService.findByCidOrderByIdAsc(cid);
            if (messages.isEmpty()) {
                return;
            }

            StringBuilder historyBuilder = new StringBuilder();
            for (MessageEntity msg : messages) {
                // 筛选出前端可见且包含实际内容的消息（主要是 USER/ASSISTANT）
                if (msg.getVisibleToUser() != null && msg.getVisibleToUser()
                        && StringUtils.hasText(msg.getContent())) {
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
            CuteChat cuteChat = providerService.getCuteChat(context);
            Provider activeConfig = providerService.getProviderConfigForContext(context);
            CuteChatOptions options = chatOptionsFactory.buildOptions(activeConfig, List.of());

            List<CuteMessage> requestMessages = new ArrayList<>();

            // 专门设计的短标题生成提示词
            String systemPrompt = """
                    你是一个会话主题提取助手。请根据用户提供的一段历史对话内容，为其总结并生成一个非常简短、清晰、生动的会话标题。
                    要求：
                    1. 标题必须紧扣对话的核心主题（例如用户想写什么代码、解决什么问题）。
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
                String newTitle = response.getContent().trim();

                // 进一步净化 AI 生成的标题，剔除首尾的多余引号、多行文本或长标题
                newTitle = newTitle.replace("\"", "").replace("'", "").replace("“", "").replace("”", "").trim();
                if (newTitle.contains("\n")) {
                    newTitle = newTitle.split("\n")[0].trim();
                }
                if (newTitle.length() > 15) {
                    newTitle = newTitle.substring(0, 15).trim();
                }

                if (StringUtils.hasText(newTitle)) {
                    ConversationEntity updatePayload = UpdateEntity.of(ConversationEntity.class);
                    updatePayload.setId(cid);
                    updatePayload.setTitle(newTitle);
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
