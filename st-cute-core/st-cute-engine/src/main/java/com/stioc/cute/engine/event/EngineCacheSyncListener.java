package com.stioc.cute.engine.event;

import com.stioc.cute.engine.assembly.EngineStores;
import com.stioc.cute.engine.event.types.AgentEvent;
import com.stioc.cute.engine.event.types.ListenerTier;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import lombok.extern.slf4j.Slf4j;

/**
 * 引擎第二层：内存 Context 状态缓存同步监听器（CACHE，同步执行）。
 * <p>
 * 在第一层写盘成功后，同步更新 JVM 内存中 AgentContext 的运行时状态，
 * 形成「写盘 → 回填 → 判定」单一临界区。若出错则触发熔断抛出异常。
 * （职责迁自宿主 EventListenerCacheSync，行为等价；查最新库值复用 Store）
 * </p>
 */
@Slf4j
public class EngineCacheSyncListener implements AgentEventListener {

    private final ConversationStore conversationStore;

    /**
     * 收存储对聚合，构造器内解包（本层只消费会话存储）
     */
    public EngineCacheSyncListener(EngineStores stores) {
        this.conversationStore = stores.getConversations();
    }

    @Override
    public ListenerTier getTier() {
        return ListenerTier.CACHE;
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (event == null || event.getType() == null) {
            return;
        }

        switch (event.getType()) {
            case CONVERSATION_UPDATE -> handleConversationUpdate(event);
            default -> {
                // MESSAGE_CREATE/UPDATE 等内存无冗余缓存状态，或流式思考直接在此层静默过路
            }
        }
    }

    private void handleConversationUpdate(AgentEvent event) {
        Long cid = null;
        if (event.getPayload() instanceof ConversationPatch patch) {
            cid = patch.getId();
        } else if (event.getPayload() instanceof Conversation entity) {
            cid = entity.getId();
        } else if (event.getAgentContext() != null) {
            cid = event.getAgentContext().getCid();
        }
        if (cid == null) {
            return;
        }
        AgentContext context = event.getAgentContext();
        if (context == null) {
            throw new IllegalStateException("EngineCacheSyncListener 发生未知异常：AgentContext 必须存在");
        }

        log.debug("EngineCacheSyncListener: 同步内存缓存数据: cid={}", context.getCid());
        try {
            // 通过实体中的 ID 查询最新完整会话实体作为回填基准（引擎 Store 供血，读库拿物理最新状态）
            Conversation latest = conversationStore.getById(cid);
            if (latest == null) {
                return;
            }

            // 依次向内存缓存属性直接赋值，无需繁琐的 null 防空校验
            context.setLoopRunning(latest.getLoopRunning() != null && latest.getLoopRunning() == 1);
            // permissionMode 已字符串化：引擎不设硬编码兜底，直接透传数据库物理值（由宿主做安全判定保底）
            context.setPermissionMode(latest.getPermissionMode());
            context.setInputTokens(latest.getInputTokens() != null ? latest.getInputTokens() : 0);
            context.setOutputTokens(latest.getOutputTokens() != null ? latest.getOutputTokens() : 0);
            context.setCachedTokens(latest.getCachedTokens() != null ? latest.getCachedTokens() : 0);
            context.setLoopCount(latest.getLoopCount() != null ? latest.getLoopCount() : 0);
            context.setCallToolCount(latest.getCallToolCount() != null ? latest.getCallToolCount() : 0);
            context.setProviderGroup(latest.getProviderGroup());
            context.setProviderModelName(latest.getProviderModelName());
            context.setWorkspaceId(latest.getWorkspaceId());
            // waitingToolIds 集合的逗号分隔解析填充
            context.getWaitingToolIds().clear();
            if (latest.getWaitingToolIds() != null && !latest.getWaitingToolIds().isBlank()) {
                for (String id : latest.getWaitingToolIds().split(",")) {
                    context.getWaitingToolIds().add(id.trim());
                }
            }

            // waitingSubCids 集合的逗号分隔解析填充
            context.getWaitingSubCids().clear();
            if (latest.getWaitingSubCids() != null && !latest.getWaitingSubCids().isBlank()) {
                for (String id : latest.getWaitingSubCids().split(",")) {
                    context.getWaitingSubCids().add(Long.valueOf(id.trim()));
                }
            }
        } catch (Exception e) {
            log.error("同步内存缓存出错，触发熔断: cid={}", context.getCid(), e);
            throw new RuntimeException("同步内存缓存失败，阻断当前流程", e);
        }
    }
}
