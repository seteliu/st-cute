package com.stioc.cute.agent.event;

import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.conversation.access.ConversationEntity;
import com.stioc.cute.security.access.PermissionMode;
import com.stioc.cute.repository.ConversationMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 第二层：内存 Context 状态缓存同步监听器 (Order = 2)
 * 职责：在第一层写盘成功后，同步更新 JVM 内存中 AgentContext 的状态。若出错则触发熔断抛出异常。
 */
@Component
@Slf4j
public class EventListenerCacheSync implements AgentEventListener {

    @Resource
    private ConversationMapper conversationMapper;

    @Override
    public ListenerTier getTier() {
        return ListenerTier.CACHE;
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
        if (!(event.getPayload() instanceof ConversationEntity entity)) {
            return;
        }
        AgentContext context = event.getAgentContext();
        if (context == null) {
            throw new IllegalStateException("EventListenerCacheSync 发生未知异常：AgentContext 必须存在");
        }

        log.debug("EventListenerCacheSync: 同步内存缓存数据: cid={}", context.getCid());
        try {
            // 通过 entity 中的 ID 直接去查最新的完整会话实体（全局配置禁掉 MyBatis 缓存后，此查询 100% 拿到物理最新状态）
            ConversationEntity latest = conversationMapper.selectOneById(entity.getId());
            if (latest == null) {
                return;
            }

            // 依次向内存缓存属性直接赋值，无需繁琐的 null 防空校验
            context.setLoopRunning(latest.getLoopRunning() != null && latest.getLoopRunning() == 1);
            context.setPermissionMode(latest.getPermissionMode() != null ? PermissionMode.fromName(latest.getPermissionMode()) : PermissionMode.READ_ONLY);
            context.setInputTokens(latest.getInputTokens() != null ? latest.getInputTokens() : 0);
            context.setOutputTokens(latest.getOutputTokens() != null ? latest.getOutputTokens() : 0);
            context.setCachedTokens(latest.getCachedTokens() != null ? latest.getCachedTokens() : 0);
            context.setLoopCount(latest.getLoopCount() != null ? latest.getLoopCount() : 0);
            context.setCallToolCount(latest.getCallToolCount() != null ? latest.getCallToolCount() : 0);
            context.setProviderGroup(latest.getProviderGroup());
            context.setProviderModelName(latest.getProviderModelName());
            context.setWorktreePath(latest.getWorktreePath());
            context.setWorktreeBranch(latest.getWorktreeBranch());

            // unlockedTools 集合的逗号分隔解析填充
            context.getUnlockedTools().clear();
            if (latest.getUnlockedToolNames() != null && !latest.getUnlockedToolNames().isBlank()) {
                for (String name : latest.getUnlockedToolNames().split(",")) {
                    context.getUnlockedTools().add(name.trim());
                }
            }

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
