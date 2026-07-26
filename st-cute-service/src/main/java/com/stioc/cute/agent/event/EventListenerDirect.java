package com.stioc.cute.agent.event;

import com.stioc.cute.conversation.access.ConversationEntity;
import com.stioc.cute.conversation.access.ConversationService;
import com.stioc.cute.message.access.MessageEntity;
import com.stioc.cute.message.access.MessageService;
import com.stioc.cute.repository.ConversationMapper;
import com.stioc.cute.repository.MessageMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import com.mybatisflex.core.update.UpdateWrapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.util.UpdateEntity;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.platform.contract.ContractLock;
import java.time.LocalDateTime;
import java.util.concurrent.locks.Lock;

/**
 * 第一层：直接层监听器 (Order = 1)
 * 职责：同步执行消息/会话等实体的持久化落库写盘操作。
 * 第一层执行失败抛出 RuntimeException 以熔断阻断后续二三层流转。
 */
@Component
@Slf4j
public class EventListenerDirect implements AgentEventListener {

    @Resource
    private ConversationService conversationService;
    @Resource
    private MessageService messageService;
    @Resource
    private ConversationMapper conversationMapper;

    @Override
    public ListenerTier getTier() {
        return ListenerTier.DIRECT;
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (event == null || event.getType() == null) {
            return;
        }

        switch (event.getType()) {
            // 持久化写命令
            case CONVERSATION_CREATE -> handleConversationCreate(event);
            case CONVERSATION_UPDATE -> handleConversationUpdate(event);
            case CONVERSATION_DELETE -> handleConversationDelete(event);
            case MESSAGE_CREATE -> handleMessageCreate(event);
            case MESSAGE_UPDATE -> handleMessageUpdate(event);
            case MESSAGE_DELETE -> handleMessageDelete(event);

            default -> {
                // 流式思考等非直接控制或写盘事件，在第一层直接层中静默过路
            }
        }
    }

    private void handleConversationCreate(AgentEvent event) {
        if (event.getPayload() instanceof ConversationEntity entity) {
            log.debug("EventListenerDirect: 持久化新建会话: cid={}", entity.getId());
            if (entity.getId() == null) {
                conversationService.createConversation(entity);
            } else {
                conversationService.insertConversation(entity);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private void handleConversationUpdate(AgentEvent event) {
        if (!(event.getPayload() instanceof ConversationEntity entity)) {
            return;
        }
        Long cid = event.getAgentContext().getCid();
        log.debug("EventListenerDirect: 更新会话数据持久化: cid={}", cid);

        // 1. 委托给 Service 层去执行增减加锁更新
        if (entity.getWaitingToolIds() != null) {
            conversationService.updateWaitingToolIds(cid, entity.getWaitingToolIds());
        }
        if (entity.getWaitingSubCids() != null) {
            conversationService.updateWaitingSubCids(cid, entity.getWaitingSubCids());
        }
        if (entity.getUnlockedToolNames() != null) {
            conversationService.updateUnlockedToolNames(cid, entity.getUnlockedToolNames());
        }

        // 2. 清除委托字段的 updates 记录，避免差量值覆盖真实更新值，且在无其它字段需要更新时直接拦截
        if (entity instanceof UpdateWrapper wrapper) {
            Map updates = wrapper.getUpdates();
            if (updates != null) {
                updates.remove("waitingToolIds");
                updates.remove("waitingSubCids");
                updates.remove("unlockedToolNames");
                updates.remove("id");
                if (updates.isEmpty()) {
                    // 无需物理 update
                    return;
                }
            }
        }

        // 3. 一句话 mapper 调用，无需加锁，直接写盘
        conversationMapper.update(entity);
    }

    private void handleConversationDelete(AgentEvent event) {
        if (event.getPayload() instanceof Long cid) {
            log.debug("EventListenerDirect: 物理删除会话及其级联消息: cid={}", cid);
            messageService.deleteByCid(cid);
            conversationMapper.deleteById(cid);
        }
    }

    private void handleMessageCreate(AgentEvent event) {
        if (event.getPayload() instanceof MessageEntity entity) {
            log.debug("EventListenerDirect: 持久化新消息: id={}, role={}", entity.getId(), entity.getRole());
            messageService.insert(entity);
        }
    }

    private void handleMessageUpdate(AgentEvent event) {
        if (event.getPayload() instanceof MessageEntity entity) {
            log.debug("EventListenerDirect: 更新消息状态持久化: id={}, status={}", entity.getId(), entity.getStatus());
            messageService.updateById(entity);
        }
    }

    private void handleMessageDelete(AgentEvent event) {
        if (event.getPayload() instanceof MessageEntity entity) {
            log.debug("EventListenerDirect: 删除会话 {} 中 ID 大于 {} 的消息", entity.getCid(), entity.getId());
            messageService.deleteByCidAndIdGreaterThan(entity.getCid(), entity.getId());
        }
    }
}
