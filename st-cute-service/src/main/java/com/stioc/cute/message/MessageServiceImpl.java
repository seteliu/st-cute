package com.stioc.cute.message;

import com.stioc.cute.message.access.MessageVo;
import com.stioc.cute.message.access.MessageRole;
import com.stioc.cute.message.access.MessageStatus;
import com.stioc.cute.message.access.MessageEntity;
import com.stioc.cute.message.access.*;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.util.UpdateEntity;
import com.stioc.cute.conversation.access.ConversationEntity;
import com.stioc.cute.agent.event.AgentEvent;
import com.stioc.cute.agent.event.AgentEventFactory;
import com.stioc.cute.agent.event.AgentEventType;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.platform.common.BusinessException;

import java.util.concurrent.locks.Lock;
import com.stioc.cute.platform.contract.ContractLock;
import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.repository.MessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

import java.util.*;

import jakarta.annotation.Resource;

/**
 * 消息处理业务逻辑层
 */
@Slf4j
@Service
public class MessageServiceImpl implements MessageService {

    @Resource
    private MessageMapper messageMapper;
    @Resource
    private ContractProperty contractProperty;

    /**
     * 根据会话 ID 删除所有消息
     */
    @Transactional
    public void deleteByCid(Long cid) {
        log.info("物理删除会话消息: cid={}", cid);
        QueryWrapper query = QueryWrapper.create()
                .where(MessageEntity::getCid).eq(cid);
        messageMapper.deleteByQuery(query);
    }

    /**
     * 获取会话的消息列表（带最大数量限制及 USER 首发逻辑）
     */
    public LimitMessageDto getConversationMessages(Long cid) {
        int limit = contractProperty.getMaxViewHistoryLimit();
        if (limit <= 0) {
            limit = 2000;
        }

        // 多查询 100 条以供 USER 首发逻辑回溯
        int queryLimit = limit + 100;

        QueryWrapper query = QueryWrapper.create()
                .where(MessageEntity::getCid).eq(cid)
                .and(MessageEntity::getVisibleToUser).eq(true)
                .orderBy(MessageEntity::getId, false)
                .limit(queryLimit);
        List<MessageEntity> descList = messageMapper.selectListByQuery(query);

        if (descList == null || descList.isEmpty()) {
            return new LimitMessageDto(new ArrayList<>(), false);
        }

        List<MessageEntity> list = new ArrayList<>(descList);
        Collections.reverse(list);

        int total = list.size();
        int startIndex = 0;
        boolean truncated = false;

        if (total > limit) {
            truncated = true;
            int targetIndex = total - limit;
            while (targetIndex >= 0) {
                MessageEntity m = list.get(targetIndex);
                if (MessageRole.USER == m.getRole()) {
                    startIndex = targetIndex;
                    break;
                }
                targetIndex--;
            }
            if (targetIndex < 0) {
                startIndex = 0;
            }
        }

        List<MessageVo> dtos = new ArrayList<>();
        for (int i = startIndex; i < total; i++) {
            MessageEntity m = list.get(i);
            if (MessageRole.SYSTEM == m.getRole()) {
                continue;
            }
            MessageVo dto = MessageVo.fromEntity(m);
            if (dto != null) {
                dtos.add(dto);
            }
        }

        return new LimitMessageDto(dtos, truncated);
    }

    /**
     * 物理清空会话消息
     */
    @Transactional
    public void clearConversationMessages(Long cid) {
        log.info("物理清空会话消息: {}", cid);
        QueryWrapper query = QueryWrapper.create()
                .where(MessageEntity::getCid).eq(cid);
        messageMapper.deleteByQuery(query);
    }

    /**
     * 重置到特定的消息节点
     */
    @Transactional
    public void resetConversationMessages(AgentContext loopContext, Long messageId) {
        Long cid = loopContext.getCid();
        log.info("开始重置会话 {} 至消息节点 {}", cid, messageId);
        MessageEntity targetMsg = messageMapper.selectOneById(messageId);
        if (targetMsg == null || !targetMsg.getCid().equals(cid)) {
            log.warn("重置失败：未找到对应的消息或消息不属于该会话，messageId={}", messageId);
            return;
        }

        // 0. 校验是否有后续的会话压缩消息，若有则禁止重置
        QueryWrapper checkQuery = QueryWrapper.create()
                .where(MessageEntity::getCid).eq(cid)
                .and(MessageEntity::getId).gt(messageId)
                .and(MessageEntity::getRole).eq(MessageRole.COMPRESSED);
        long compressedCount = messageMapper.selectCountByQuery(checkQuery);
        if (compressedCount > 0) {
            throw new BusinessException("后续消息中存在已压缩的上下文，无法重置到此节点");
        }

        // 1. 上报删除消息事件（删除该会话中 ID 大于等于 messageId 的所有消息记录，传入 messageId - 1 达到 id > messageId - 1 即 id >= messageId 的效果）
        MessageEntity deleteCondition = MessageEntity.builder()
                .cid(cid)
                .id(messageId - 1)
                .build();
        loopContext.publishEvent(AgentEventFactory.createMessageDelete(loopContext, deleteCondition));

        log.info("已通过事件总线上报会话重置物理删除命令: cid={}, messageId={}", cid, messageId);
    }

    /**
     * 发送新用户消息并唤醒 ReAct 执行循环
     */
    public void sendMessage(AgentContext loopContext, String text) {
        Long cid = loopContext.getCid();
        MessageEntity userEntity = MessageEntity.builder()
                .cid(cid)
                .role(MessageRole.USER)
                .content(text)
                .status(MessageStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        // 抛出 MESSAGE_CREATE 消息创建请求，由 Listener 链执行：写库 -> 同步内存缓存 -> WS推送
        loopContext.publishEvent(AgentEventFactory.createMessageCreate(loopContext, userEntity));
    }

    /**
     * 重试特定消息
     */
    public void retryMessage(AgentContext loopContext, Long messageId) {
        MessageEntity message = messageMapper.selectOneById(messageId);
        if (message == null) {
            throw new BusinessException("未找到待重试的消息");
        }

        Long cid = loopContext.getCid();
        // 1. 通过发布事件，确保缓存同步和前端 WebSocket 渲染完备
        if (MessageRole.ASSISTANT == message.getRole()) {
            MessageEntity updateMsg = UpdateEntity.of(MessageEntity.class);
            updateMsg.setId(messageId);
            updateMsg.setCid(cid);
            updateMsg.setStatus(MessageStatus.PENDING);
            updateMsg.setContent("");
            updateMsg.setReasoningContent("");
            updateMsg.setToolCalls(null);
            loopContext.publishEvent(AgentEventFactory.createMessageUpdate(loopContext, updateMsg));
        } else if (MessageRole.USER == message.getRole()) {
            resetConversationMessages(loopContext, messageId);
        }
    }

    public MessageEntity findToolMessage(Long cid, String toolCallId) {
        try {
            QueryWrapper query = QueryWrapper.create()
                    .where(MessageEntity::getCid).eq(cid)
                    .and(MessageEntity::getCallId).eq(toolCallId);
            return messageMapper.selectOneByQuery(query);
        } catch (Exception e) {
            log.warn("findToolMessage 失败: cid={}, toolCallId={}", cid, toolCallId, e);
        }
        return null;
    }

    /**
     * 查找会话内所有处于非终态（PENDING/RUNNING/WAITING_APPROVAL）的 TOOL 消息，
     * 供 ToolStatusHandler 清理悬挂工具用。
     */
    public List<MessageEntity> findInflightToolMessages(Long cid) {
        QueryWrapper query = QueryWrapper.create()
                .where(MessageEntity::getCid).eq(cid)
                .and(MessageEntity::getStatus).in(Set.of(
                        MessageStatus.PENDING, MessageStatus.RUNNING, MessageStatus.WAITING_APPROVAL));
        return messageMapper.selectListByQuery(query)
                .stream()
                .filter(m -> MessageRole.TOOL == m.getRole())
                .toList();
    }

    public List<MessageEntity> findByCidOrderByIdAsc(Long cid) {
        QueryWrapper query = QueryWrapper.create()
                .where(MessageEntity::getCid).eq(cid)
                .orderBy(MessageEntity::getId, true);
        return messageMapper.selectListByQuery(query);
    }

    public List<MessageEntity> findByCidAndVisibleToModelTrueOrderByIdAsc(Long cid) {
        QueryWrapper query = QueryWrapper.create()
                .where(MessageEntity::getCid).eq(cid)
                .and(MessageEntity::getVisibleToModel).eq(true)
                .orderBy(MessageEntity::getId, true);
        return messageMapper.selectListByQuery(query);
    }

    public Optional<MessageEntity> findLastMessage(Long cid) {
        QueryWrapper query = QueryWrapper.create()
                .where(MessageEntity::getCid).eq(cid)
                .orderBy(MessageEntity::getId, false)
                .limit(1);
        return Optional.ofNullable(messageMapper.selectOneByQuery(query));
    }

    public Optional<MessageEntity> findById(Long id) {
        return Optional.ofNullable(messageMapper.selectOneById(id));
    }

    public void updateMessageVisibleToModel(Long cid, Long messageId, boolean visibleToModel) {
        MessageEntity updateModelVisibleMsg = UpdateEntity.of(MessageEntity.class);
        updateModelVisibleMsg.setId(messageId);
        updateModelVisibleMsg.setVisibleToModel(visibleToModel);
        messageMapper.update(updateModelVisibleMsg);
    }

    @Transactional
    public void updateAllMessagesVisibleToModel(Long cid, boolean visibleToModel) {
        List<MessageEntity> msgs = findByCidOrderByIdAsc(cid);
        for (MessageEntity m : msgs) {
            updateMessageVisibleToModel(cid, m.getId(), visibleToModel);
        }
    }

    public void insert(MessageEntity entity) {
        messageMapper.insert(entity);
    }

    public void updateById(MessageEntity entity) {
        messageMapper.update(entity);
    }

    public void deleteByCidAndIdGreaterThan(Long cid, Long messageId) {
        QueryWrapper query = QueryWrapper.create()
                .where(MessageEntity::getCid).eq(cid)
                .and(MessageEntity::getId).gt(messageId);
        messageMapper.deleteByQuery(query);
    }

    @Override
    public boolean hasPendingMessages(Long cid) {
        QueryWrapper query = QueryWrapper.create()
                .where(MessageEntity::getCid).eq(cid)
                .and(MessageEntity::getStatus).eq(MessageStatus.PENDING)
                .and(MessageEntity::getVisibleToModel).eq(true);
        return messageMapper.selectCountByQuery(query) > 0;
    }
}
