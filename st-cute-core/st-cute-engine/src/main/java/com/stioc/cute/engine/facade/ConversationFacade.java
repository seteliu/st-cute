package com.stioc.cute.engine.facade;

import com.stioc.cute.engine.event.AgentEventFactory;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.loop.core.AgentContextManager;
import com.stioc.cute.engine.loop.core.AgentLoopCoordinator;
import com.stioc.cute.engine.loop.core.LoopDataReporter;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import lombok.RequiredArgsConstructor;

/**
 * 会话门面：会话生命周期、历史重置/清空、屏障收口、子代理联动与轻量状态事件发布（极薄门面，全量一句话转发）。
 */
@RequiredArgsConstructor
public class ConversationFacade {

    private final LoopDataReporter loopDataReporter;
    private final AgentContextManager contextManager;
    private final AgentLoopCoordinator loopCoordinator;

    /**
     * 清空指定会话的全部历史消息与运行状态指标
     */
    public void clearConversation(Long cid) {
        loopDataReporter.clearConversationData(contextManager.getOrCreateContext(cid));
    }

    /**
     * 回退并重置会话消息历史至指定节点（删除该节点及之后的所有消息）
     */
    public void resetConversationMessages(Long cid, Long messageId) {
        loopCoordinator.resetConversationMessages(cid, messageId);
    }

    /**
     * 创建并持久化新会话（经由引擎事件链完成物理写盘与事件广播）
     */
    public Conversation createConversation(Conversation conversation) {
        return contextManager.createConversation(conversation);
    }

    /**
     * 级联物理删除指定会话及关联底层消息
     */
    public void deleteConversation(Long cid) {
        contextManager.deleteConversation(cid);
    }

    /**
     * 发布会话差量更新事件（改名等轻量更新场景）
     */
    public void publishConversationUpdate(AgentContext context, ConversationPatch patch) {
        context.publishEvent(AgentEventFactory.createConversationUpdate(context, patch));
    }
}
