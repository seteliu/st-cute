package com.stioc.cute.agent;

import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.agent.event.AgentEventFactory;
import com.stioc.cute.conversation.access.ConversationEntity;
import com.mybatisflex.core.util.UpdateEntity;
import com.stioc.cute.llm.CuteUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 大模型 Token 消耗及计费记录器
 */
@Slf4j
@Component
public class AgentTokenUsageRecorder {

    /**
     * 记录一次真实的大模型调用，并更新当前上下文窗口的用量快照。
     */
    public void recordLlmCall(AgentContext context, CuteUsage usage, long callDurationMs) {
        Long cid = context.getCid();
        long inputTokens = 0;
        long outputTokens = 0;
        long cachedTokens = 0;

        if (usage != null) {
            inputTokens = usage.getInputTokens() != null ? usage.getInputTokens() : 0L;
            outputTokens = usage.getOutputTokens() != null ? usage.getOutputTokens() : 0L;
            cachedTokens = usage.getCachedTokens() != null ? usage.getCachedTokens() : 0L;
        }

        // 发送会话更新命令，触发分层：写库 -> 同步内存缓存 -> WS推送
        ConversationEntity tokenUpdate = UpdateEntity.of(ConversationEntity.class);
        tokenUpdate.setId(cid);
        tokenUpdate.setInputTokens(inputTokens);
        tokenUpdate.setOutputTokens(outputTokens);
        tokenUpdate.setCachedTokens(cachedTokens);
        context.publishEvent(AgentEventFactory.createConversationUpdate(context, tokenUpdate));

        log.debug("已完成单次 LLM 调用 Token 快照广播: cid={}, prompt={}, completion={}, duration={}ms",
                cid, inputTokens, outputTokens, callDurationMs);
    }

    /**
     * 推送当前会话在内存中的最新 Token 窗口用量快照事件
     */
    public void publishWindowUsageSnapshot(AgentContext context) {
        ConversationEntity tokenUpdate = UpdateEntity.of(ConversationEntity.class);
        tokenUpdate.setId(context.getCid());
        tokenUpdate.setInputTokens(context.getInputTokens());
        tokenUpdate.setOutputTokens(context.getOutputTokens());
        tokenUpdate.setCachedTokens(context.getCachedTokens());
        context.publishEvent(AgentEventFactory.createConversationUpdate(context, tokenUpdate));
    }
}
