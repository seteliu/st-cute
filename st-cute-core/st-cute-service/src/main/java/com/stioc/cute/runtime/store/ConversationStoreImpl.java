package com.stioc.cute.runtime.store;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.util.UpdateEntity;
import com.stioc.cute.conversation.ConversationEntity;
import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.common.LambdaFieldResolver;
import com.stioc.cute.engine.common.SFunction;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.engine.store.types.ConversationQuery;
import com.stioc.cute.engine.store.types.SortDirection;
import com.stioc.cute.repository.ConversationMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 引擎会话存储供血实现（宿主 SQLite 落库，纯 CRUD，无循环业务语义）。
 * <p>
 * 遵循 {@link ConversationStore} 6 段式方法排列规范。
 * 本类承担：引擎模型 ↔ 宿主 Entity 映射、Patch → ORM 差量更新翻译、
 * 差量原子更新的「读最新 → 变换 → 写回」（cid 数据锁内，与引擎事件链同源可重入）。
 * </p>
 */
@Slf4j
@Component
public class ConversationStoreImpl implements ConversationStore {

    @Resource
    private ConversationMapper conversationMapper;

    /**
     * 引擎根对象：仅用于经 {@link AgentEngine#getEngineLock()} 获取与引擎同源的锁供血，
     * 差量原子更新的「读最新 → 变换 → 写回」临界区必须与引擎事件链共用同一物理锁源
     */
    @Resource
    @Lazy
    private AgentEngine agentEngine;

    // ── 1. 单实体查询 ──

    @Override
    public Conversation getById(Long cid) {
        if (cid == null) {
            return null;
        }
        ConversationEntity entity = conversationMapper.selectOneById(cid);
        if (entity == null) {
            return null;
        }
        return toModel(entity);
    }

    @Override
    public Conversation getByQuery(ConversationQuery query) {
        if (query == null) {
            return null;
        }
        QueryWrapper wrapper = buildQueryWrapper(query);
        wrapper.limit(1);
        ConversationEntity entity = conversationMapper.selectOneByQuery(wrapper);
        if (entity == null) {
            return null;
        }
        return toModel(entity);
    }

    // ── 2. 集合与批量查询 ──

    @Override
    public List<Conversation> listByQuery(ConversationQuery query) {
        if (query == null) {
            return List.of();
        }
        return conversationMapper.selectListByQuery(buildQueryWrapper(query)).stream()
                .map(this::toModel).toList();
    }

    // ── 3. 存在性轻量查询 ──


    @Override
    public boolean existsByQuery(ConversationQuery query) {
        if (query == null) {
            return false;
        }
        return conversationMapper.selectCountByQuery(buildQueryWrapper(query)) > 0;
    }

    // ── 4. 实体新增与整实体更新 ──

    @Override
    public Conversation insert(Conversation entity) {
        if (entity == null) {
            return null;
        }
        if (entity.getCreateTime() == null) {
            entity.setCreateTime(LocalDateTime.now());
        }
        if (entity.getUpdateTime() == null) {
            entity.setUpdateTime(LocalDateTime.now());
        }
        ConversationEntity po = toEntity(entity);
        conversationMapper.insert(po);
        entity.setId(po.getId());
        return entity;
    }

    @Override
    public List<Conversation> insertBatch(List<Conversation> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        for (Conversation entity : entities) {
            insert(entity);
        }
        return entities;
    }

    @Override
    public int updateById(Conversation entity) {
        if (entity == null || entity.getId() == null) {
            return 0;
        }
        entity.setUpdateTime(LocalDateTime.now());
        ConversationEntity po = toEntity(entity);
        return conversationMapper.update(po);
    }

    // ── 5. 差量与条件批量更新 ──

    @Override
    public int updateByPatch(ConversationPatch patch) {
        if (patch == null || patch.getId() == null) {
            return 0;
        }
        // 引擎 Patch → ORM 差量实体翻译（含 null 清空语义；字段键经 getter 方法引用推导，
        // 强转由泛型推导取代）。
        // 集合字段（waitingToolIds/waitingSubCids）非 null 的 "+id"/"-id" 差量已由引擎持久化层
        // 剥离并经 updateByDelta 原子消费，能落到本方法的集合字段值均为 null 清空直写
        ConversationEntity updater = UpdateEntity.of(ConversationEntity.class);
        updater.setId(patch.getId());
        if (patch.has(Conversation::getTitle)) {
            updater.setTitle(patch.get(Conversation::getTitle));
        }
        if (patch.has(Conversation::getCreateTime)) {
            updater.setCreateTime(patch.get(Conversation::getCreateTime));
        }
        if (patch.has(Conversation::getUpdateTime)) {
            updater.setUpdateTime(patch.get(Conversation::getUpdateTime));
        } else {
            updater.setUpdateTime(LocalDateTime.now());
        }
        if (patch.has(Conversation::getWorkspaceId)) {
            updater.setWorkspaceId(patch.get(Conversation::getWorkspaceId));
        }
        if (patch.has(Conversation::getProviderGroup)) {
            updater.setProviderGroup(patch.get(Conversation::getProviderGroup));
        }
        if (patch.has(Conversation::getProviderModelName)) {
            updater.setProviderModelName(patch.get(Conversation::getProviderModelName));
        }
        if (patch.has(Conversation::getPermissionMode)) {
            updater.setPermissionMode(patch.get(Conversation::getPermissionMode));
        }
        if (patch.has(Conversation::getParentCid)) {
            updater.setParentCid(patch.get(Conversation::getParentCid));
        }
        if (patch.has(Conversation::getInputTokens)) {
            updater.setInputTokens(patch.get(Conversation::getInputTokens));
        }
        if (patch.has(Conversation::getOutputTokens)) {
            updater.setOutputTokens(patch.get(Conversation::getOutputTokens));
        }
        if (patch.has(Conversation::getCachedTokens)) {
            updater.setCachedTokens(patch.get(Conversation::getCachedTokens));
        }
        if (patch.has(Conversation::getCallToolCount)) {
            updater.setCallToolCount(patch.get(Conversation::getCallToolCount));
        }
        if (patch.has(Conversation::getLoopCount)) {
            updater.setLoopCount(patch.get(Conversation::getLoopCount));
        }
        if (patch.has(Conversation::getLoopRunning)) {
            updater.setLoopRunning(patch.get(Conversation::getLoopRunning));
        }
        if (patch.has(Conversation::getWaitingToolIds)) {
            updater.setWaitingToolIds(patch.get(Conversation::getWaitingToolIds));
        }
        if (patch.has(Conversation::getWaitingSubCids)) {
            updater.setWaitingSubCids(patch.get(Conversation::getWaitingSubCids));
        }
        return conversationMapper.update(updater);
    }

    @Override
    public int updateByQuery(Conversation entity, ConversationQuery query) {
        if (entity == null || query == null) {
            return 0;
        }
        if (entity.getUpdateTime() == null) {
            entity.setUpdateTime(LocalDateTime.now());
        }
        return conversationMapper.updateByQuery(toEntity(entity), buildQueryWrapper(query));
    }

    /**
     * 集合差量字段强类型存取器（读原值 Function + 写新值 BiConsumer）
     */
    private record DeltaFieldAccessor(
            Function<ConversationEntity, String> getter,
            BiConsumer<ConversationEntity, String> setter
    ) {}

    /**
     * 差量集合字段存取器注册表（键由 Conversation::getXxx 方法引用推导，消除魔数字符串）
     */
    private static final Map<String, DeltaFieldAccessor> DELTA_FIELD_ACCESSORS = Map.of(
            LambdaFieldResolver.resolve(Conversation::getWaitingToolIds),
            new DeltaFieldAccessor(ConversationEntity::getWaitingToolIds, ConversationEntity::setWaitingToolIds),

            LambdaFieldResolver.resolve(Conversation::getWaitingSubCids),
            new DeltaFieldAccessor(ConversationEntity::getWaitingSubCids, ConversationEntity::setWaitingSubCids)
    );

    @Override
    public void updateByDelta(Long cid, SFunction<Conversation, String> fieldGetter, String delta) {
        String fieldName = LambdaFieldResolver.resolve(fieldGetter);
        DeltaFieldAccessor accessor = DELTA_FIELD_ACCESSORS.get(fieldName);
        if (accessor == null) {
            throw new IllegalArgumentException("不支持的差量集合字段: " + fieldName);
        }

        log.debug("updateByDelta[{}] 开始处理: cid={}, delta={}", fieldName, cid, delta);
        Lock lock = agentEngine.getEngineLock().getConversationDataLock(cid);
        lock.lock();
        try {
            ConversationEntity conv = conversationMapper.selectOneById(cid);
            if (conv == null) {
                log.warn("updateByDelta[{}]: 会话不存在, cid={}", fieldName, cid);
                return;
            }
            String currentVal = accessor.getter().apply(conv);
            String newVal = processDeltaString(currentVal, delta);

            ConversationEntity updater = UpdateEntity.of(ConversationEntity.class);
            updater.setId(cid);
            accessor.setter().accept(updater, newVal);
            updater.setUpdateTime(LocalDateTime.now());
            conversationMapper.update(updater);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 差量集合字符串运算（行为等价原 ConversationServiceImpl#processDeltaString）
     */
    private String processDeltaString(String currentVal, String deltaInput) {
        if (deltaInput == null) {
            return currentVal;
        }
        if (deltaInput.isEmpty()) {
            return null;
        }

        Set<String> items = new LinkedHashSet<>();
        if (currentVal != null && !currentVal.isBlank()) {
            for (String part : currentVal.split(",")) {
                if (!part.isBlank()) {
                    items.add(part.trim());
                }
            }
        }

        if (deltaInput.startsWith("+")) {
            String toAddStr = deltaInput.substring(1).trim();
            for (String item : toAddStr.split(",")) {
                if (!item.isBlank()) {
                    items.add(item.trim());
                }
            }
        } else if (deltaInput.startsWith("-")) {
            String toRemoveStr = deltaInput.substring(1).trim();
            for (String item : toRemoveStr.split(",")) {
                if (!item.isBlank()) {
                    items.remove(item.trim());
                }
            }
        } else {
            // 非差量形式：全量直写语义
            return deltaInput;
        }

        return items.isEmpty() ? null : String.join(",", items);
    }

    // ── 6. 物理删除与批量删除 ──

    @Override
    public void deleteById(Long cid) {
        if (cid != null) {
            conversationMapper.deleteById(cid);
        }
    }

    @Override
    public int deleteByQuery(ConversationQuery query) {
        if (query == null) {
            return 0;
        }
        return conversationMapper.deleteByQuery(buildQueryWrapper(query));
    }

    // ── 模型 ↔ Entity 映射 ──

    private Conversation toModel(ConversationEntity entity) {
        return Conversation.builder()
                .id(entity.getId())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .workspaceId(entity.getWorkspaceId())
                .title(entity.getTitle())
                .providerGroup(entity.getProviderGroup())
                .providerModelName(entity.getProviderModelName())
                .permissionMode(entity.getPermissionMode())
                .parentCid(entity.getParentCid())
                .inputTokens(entity.getInputTokens())
                .outputTokens(entity.getOutputTokens())
                .cachedTokens(entity.getCachedTokens())
                .callToolCount(entity.getCallToolCount())
                .waitingToolIds(entity.getWaitingToolIds())
                .waitingSubCids(entity.getWaitingSubCids())
                .loopCount(entity.getLoopCount())
                .loopRunning(entity.getLoopRunning())
                .build();
    }

    private ConversationEntity toEntity(Conversation m) {
        return ConversationEntity.builder()
                .id(m.getId())
                .createTime(m.getCreateTime())
                .updateTime(m.getUpdateTime())
                .workspaceId(m.getWorkspaceId())
                .title(m.getTitle())
                .providerGroup(m.getProviderGroup())
                .providerModelName(m.getProviderModelName())
                .permissionMode(m.getPermissionMode())
                .parentCid(m.getParentCid())
                .inputTokens(m.getInputTokens())
                .outputTokens(m.getOutputTokens())
                .cachedTokens(m.getCachedTokens())
                .callToolCount(m.getCallToolCount())
                .waitingToolIds(m.getWaitingToolIds())
                .waitingSubCids(m.getWaitingSubCids())
                .loopCount(m.getLoopCount())
                .loopRunning(m.getLoopRunning())
                .build();
    }

    // ── QueryWrapper 条件构造 ──

    private QueryWrapper buildQueryWrapper(ConversationQuery query) {
        QueryWrapper wrapper = QueryWrapper.create();
        if (query == null) {
            return wrapper;
        }

        if (query.getId() != null) {
            wrapper.where(ConversationEntity::getId).eq(query.getId());
        }
        if (query.getCreateTime() != null) {
            wrapper.and(ConversationEntity::getCreateTime).eq(query.getCreateTime());
        }
        if (query.getUpdateTime() != null) {
            wrapper.and(ConversationEntity::getUpdateTime).eq(query.getUpdateTime());
        }
        if (StringUtils.isNotBlank(query.getWorkspaceId())) {
            wrapper.and(ConversationEntity::getWorkspaceId).eq(query.getWorkspaceId());
        }
        if (StringUtils.isNotBlank(query.getTitle())) {
            wrapper.and(ConversationEntity::getTitle).eq(query.getTitle());
        }
        if (query.getParentCid() != null) {
            wrapper.and(ConversationEntity::getParentCid).eq(query.getParentCid());
        }
        if (query.getLoopRunning() != null) {
            wrapper.and(ConversationEntity::getLoopRunning).eq(query.getLoopRunning());
        }

        if (query.getIds() != null && !query.getIds().isEmpty()) {
            wrapper.and(ConversationEntity::getId).in(query.getIds());
        }

        if (query.getSortDirection() != null) {
            boolean isAsc = SortDirection.ASC == query.getSortDirection();
            String field = StringUtils.defaultIfBlank(query.getSortField(), "id");
            wrapper.orderBy(toUnderlineCase(field), isAsc);
        }

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
