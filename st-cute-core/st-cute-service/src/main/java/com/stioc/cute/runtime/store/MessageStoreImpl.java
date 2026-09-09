package com.stioc.cute.runtime.store;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.util.UpdateEntity;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessagePatch;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.store.types.SortDirection;
import com.stioc.cute.message.MessageEntity;
import com.stioc.cute.repository.MessageMapper;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 引擎消息存储供血实现（宿主 SQLite 落库，纯 CRUD，无循环业务语义）。
 * <p>
 * 遵循 {@link MessageStore} 6 段式方法排列规范。
 * 承担引擎模型 ↔ 宿主 Entity 映射与 Patch → ORM 差量更新翻译；
 * 事件链的翻译与编排已内置引擎。
 * </p>
 */
@Component
public class MessageStoreImpl implements MessageStore {

    @Resource
    private MessageMapper messageMapper;

    // ── 1. 单实体查询 ──

    @Override
    public Message getById(Long id) {
        if (id == null) {
            return null;
        }
        MessageEntity po = messageMapper.selectOneById(id);
        if (po == null) {
            return null;
        }
        return toModel(po);
    }

    @Override
    public Message getByQuery(MessageQuery query) {
        if (query == null) {
            return null;
        }
        QueryWrapper wrapper = buildQueryWrapper(query);
        wrapper.limit(1);
        MessageEntity po = messageMapper.selectOneByQuery(wrapper);
        if (po == null) {
            return null;
        }
        return toModel(po);
    }

    // ── 2. 集合与批量查询 ──

    @Override
    public List<Message> listByQuery(MessageQuery query) {
        if (query == null) {
            return List.of();
        }
        return messageMapper.selectListByQuery(buildQueryWrapper(query)).stream()
                .map(this::toModel).toList();
    }

    // ── 3. 存在性与状态轻量查询 ──

    @Override
    public boolean existsByQuery(MessageQuery query) {
        if (query == null) {
            return false;
        }
        return messageMapper.selectCountByQuery(buildQueryWrapper(query)) > 0;
    }


    // ── 4. 实体新增与整实体更新 ──

    @Override
    public void insert(Message entity) {
        if (entity == null) {
            return;
        }
        if (entity.getCreateTime() == null) {
            entity.setCreateTime(LocalDateTime.now());
        }
        if (entity.getUpdateTime() == null) {
            entity.setUpdateTime(LocalDateTime.now());
        }
        MessageEntity po = toEntity(entity);
        messageMapper.insert(po);
        entity.setId(po.getId());
    }

    @Override
    public void insertBatch(List<Message> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        for (Message entity : entities) {
            insert(entity);
        }
    }

    @Override
    public int updateById(Message entity) {
        if (entity == null || entity.getId() == null) {
            return 0;
        }
        entity.setUpdateTime(LocalDateTime.now());
        return messageMapper.update(toEntity(entity));
    }

    // ── 5. 差量与批量更新 ──

    @Override
    public int updateByPatch(MessagePatch patch) {
        if (patch == null || patch.getId() == null) {
            return 0;
        }
        // 引擎 Patch → ORM 差量实体翻译（字段键经 getter 方法引用推导，强转由泛型推导取代）
        MessageEntity updater = UpdateEntity.of(MessageEntity.class);
        updater.setId(patch.getId());
        if (patch.has(Message::getCid)) {
            updater.setCid(patch.get(Message::getCid));
        }
        if (patch.has(Message::getParentMessageId)) {
            updater.setParentMessageId(patch.get(Message::getParentMessageId));
        }
        if (patch.has(Message::getRole)) {
            updater.setRole(patch.get(Message::getRole));
        }
        if (patch.has(Message::getContent)) {
            updater.setContent(patch.get(Message::getContent));
        }
        if (patch.has(Message::getReasoningContent)) {
            updater.setReasoningContent(patch.get(Message::getReasoningContent));
        }
        if (patch.has(Message::getToolCalls)) {
            updater.setToolCalls(patch.get(Message::getToolCalls));
        }
        if (patch.has(Message::getCallId)) {
            updater.setCallId(patch.get(Message::getCallId));
        }
        if (patch.has(Message::getStatus)) {
            updater.setStatus(patch.get(Message::getStatus));
        }
        if (patch.has(Message::getVisibleToUser)) {
            updater.setVisibleToUser(patch.get(Message::getVisibleToUser));
        }
        if (patch.has(Message::getVisibleToModel)) {
            updater.setVisibleToModel(patch.get(Message::getVisibleToModel));
        }
        if (patch.has(Message::getInputTokens)) {
            updater.setInputTokens(patch.get(Message::getInputTokens));
        }
        if (patch.has(Message::getOutputTokens)) {
            updater.setOutputTokens(patch.get(Message::getOutputTokens));
        }
        if (patch.has(Message::getCachedTokens)) {
            updater.setCachedTokens(patch.get(Message::getCachedTokens));
        }
        if (patch.has(Message::getExecutionDurationMs)) {
            updater.setExecutionDurationMs(patch.get(Message::getExecutionDurationMs));
        }
        if (patch.has(Message::getAttachments)) {
            updater.setAttachments(patch.get(Message::getAttachments));
        }
        if (patch.has(Message::getCreateTime)) {
            updater.setCreateTime(patch.get(Message::getCreateTime));
        }
        if (patch.has(Message::getUpdateTime)) {
            updater.setUpdateTime(patch.get(Message::getUpdateTime));
        } else {
            updater.setUpdateTime(LocalDateTime.now());
        }
        return messageMapper.update(updater);
    }

    @Override
    public int updateByQuery(Message entity, MessageQuery query) {
        if (entity == null || query == null) {
            return 0;
        }
        if (entity.getUpdateTime() == null) {
            entity.setUpdateTime(LocalDateTime.now());
        }
        return messageMapper.updateByQuery(toEntity(entity), buildQueryWrapper(query));
    }

    // ── 6. 物理删除与批量删除 ──

    @Override
    public void deleteById(Long id) {
        if (id != null) {
            messageMapper.deleteById(id);
        }
    }

    @Override
    public int deleteByQuery(MessageQuery query) {
        if (query == null) {
            return 0;
        }
        return messageMapper.deleteByQuery(buildQueryWrapper(query));
    }

    // ── 模型 ↔ Entity 映射 ──

    private Message toModel(MessageEntity po) {
        return Message.builder()
                .id(po.getId())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .cid(po.getCid())
                .parentMessageId(po.getParentMessageId())
                .role(po.getRole())
                .content(po.getContent())
                .reasoningContent(po.getReasoningContent())
                .toolCalls(po.getToolCalls())
                .callId(po.getCallId())
                .status(po.getStatus())
                .visibleToUser(po.getVisibleToUser())
                .visibleToModel(po.getVisibleToModel())
                .inputTokens(po.getInputTokens())
                .outputTokens(po.getOutputTokens())
                .cachedTokens(po.getCachedTokens())
                .executionDurationMs(po.getExecutionDurationMs())
                .attachments(po.getAttachments())
                .build();
    }

    private MessageEntity toEntity(Message m) {
        return MessageEntity.builder()
                .id(m.getId())
                .createTime(m.getCreateTime())
                .updateTime(m.getUpdateTime())
                .cid(m.getCid())
                .parentMessageId(m.getParentMessageId())
                .role(m.getRole())
                .content(m.getContent())
                .reasoningContent(m.getReasoningContent())
                .toolCalls(m.getToolCalls())
                .callId(m.getCallId())
                .status(m.getStatus())
                .visibleToUser(m.getVisibleToUser())
                .visibleToModel(m.getVisibleToModel())
                .inputTokens(m.getInputTokens())
                .outputTokens(m.getOutputTokens())
                .cachedTokens(m.getCachedTokens())
                .executionDurationMs(m.getExecutionDurationMs())
                .attachments(m.getAttachments())
                .build();
    }

    // ── QueryWrapper 条件构造 ──

    private QueryWrapper buildQueryWrapper(MessageQuery query) {
        QueryWrapper wrapper = QueryWrapper.create();
        if (query == null) {
            return wrapper;
        }

        // ── 1. 轻量投影控制 ──
        if (query.isLight()) {
            wrapper.select(MessageEntity::getId, MessageEntity::getParentMessageId,
                    MessageEntity::getRole, MessageEntity::getStatus);
        }

        // ── 2. 实体标准属性对齐 ──
        if (query.getId() != null) {
            wrapper.where(MessageEntity::getId).eq(query.getId());
        }
        if (query.getCreateTime() != null) {
            wrapper.and(MessageEntity::getCreateTime).eq(query.getCreateTime());
        }
        if (query.getUpdateTime() != null) {
            wrapper.and(MessageEntity::getUpdateTime).eq(query.getUpdateTime());
        }
        if (query.getCid() != null) {
            wrapper.and(MessageEntity::getCid).eq(query.getCid());
        }
        if (query.getParentMessageId() != null) {
            wrapper.and(MessageEntity::getParentMessageId).eq(query.getParentMessageId());
        }
        if (query.getRole() != null) {
            wrapper.and(MessageEntity::getRole).eq(query.getRole());
        }
        if (StringUtils.isNotBlank(query.getCallId())) {
            wrapper.and(MessageEntity::getCallId).eq(query.getCallId());
        }
        if (query.getStatus() != null) {
            wrapper.and(MessageEntity::getStatus).eq(query.getStatus());
        }
        if (query.getVisibleToUser() != null) {
            wrapper.and(MessageEntity::getVisibleToUser).eq(query.getVisibleToUser());
        }
        if (query.getVisibleToModel() != null) {
            wrapper.and(MessageEntity::getVisibleToModel).eq(query.getVisibleToModel());
        }

        // ── 3. 扩展查询条件 ──
        if (query.getIds() != null && !query.getIds().isEmpty()) {
            wrapper.and(MessageEntity::getId).in(query.getIds());
        }
        if (query.getExcludedIds() != null && !query.getExcludedIds().isEmpty()) {
            wrapper.and(MessageEntity::getId).notIn(query.getExcludedIds());
        }
        if (query.getCids() != null && !query.getCids().isEmpty()) {
            wrapper.and(MessageEntity::getCid).in(query.getCids());
        }
        if (query.getMinId() != null) {
            wrapper.and(MessageEntity::getId).ge(query.getMinId());
        }
        if (query.getMaxId() != null) {
            wrapper.and(MessageEntity::getId).le(query.getMaxId());
        }
        if (query.getGreaterThanId() != null) {
            wrapper.and(MessageEntity::getId).gt(query.getGreaterThanId());
        }
        if (query.getCreateTimeBefore() != null) {
            wrapper.and(MessageEntity::getCreateTime).lt(query.getCreateTimeBefore());
        }
        if (query.getRoles() != null && !query.getRoles().isEmpty()) {
            wrapper.and(MessageEntity::getRole).in(query.getRoles());
        }
        if (query.getStatuses() != null && !query.getStatuses().isEmpty()) {
            wrapper.and(MessageEntity::getStatus).in(query.getStatuses());
        }

        // ── 4. 排序控制 ──
        if (query.getSortDirection() != null) {
            boolean isAsc = SortDirection.ASC == query.getSortDirection();
            String field = StringUtils.defaultIfBlank(query.getSortField(), "id");
            wrapper.orderBy(toUnderlineCase(field), isAsc);
        }

        // ── 5. 分页数量限制 ──
        if (query.getLimit() != null && query.getLimit() > 0) {
            wrapper.limit(query.getLimit());
        }

        return wrapper;
    }

    private static String toUnderlineCase(String camelCase) {
        if (StringUtils.isBlank(camelCase)) {
            return "id";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
