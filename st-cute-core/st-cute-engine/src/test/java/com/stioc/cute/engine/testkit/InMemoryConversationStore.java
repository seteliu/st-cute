package com.stioc.cute.engine.testkit;

import com.stioc.cute.engine.common.LambdaFieldResolver;
import com.stioc.cute.engine.common.SFunction;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.engine.store.types.ConversationQuery;
import com.stioc.cute.engine.store.types.SortDirection;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内存版会话存储替身，语义严格对齐宿主 MyBatis-Flex 实现。
 * <p>
 * 与 {@link InMemoryMessageStore} 同源同构，额外承载会话侧特有的「集合差量更新」语义：
 * 引擎的等待屏障（waitingToolIds / waitingSubCids）经 {@code updateByDelta} 以 {@code "+id"} /
 * {@code "-id"} 差量形式原子增减，宿主实现是在 cid 数据锁内「读最新 → 变换 → 写回」，
 * 此处同样在内部互斥区完成，否则并发只读批的完成回调会丢更新、屏障永不为空、下一轮永不拉起。
 * </p>
 * <p>
 * 差量字符串的四形态语义逐条对齐宿主 {@code processDeltaString}：
 * <ul>
 *   <li>{@code null} → <b>空操作</b>（不是清空，这点极易做错）；</li>
 *   <li>{@code ""}（空串）→ 清空为 null；</li>
 *   <li>{@code "+a"} / {@code "+a,b"} → 追加（去重保序）；</li>
 *   <li>{@code "-a"} / {@code "-a,b"} → 移除；</li>
 *   <li>非 {@code +/-} 开头 → 全量直写；</li>
 *   <li>结果集为空 → 归一为 null。</li>
 * </ul>
 * </p>
 */
public class InMemoryConversationStore implements ConversationStore {

    /**
     * 内部互斥锁：事件链、循环线程与工具批线程会并发触达
     */
    private final Object lock = new Object();

    /**
     * 会话表（按插入序保持稳定遍历顺序）
     */
    private final Map<Long, Conversation> rows = new LinkedHashMap<>();

    /**
     * 自增主键序列
     */
    private long seq = 0L;

    // ── 1. 单实体查询 ──

    @Override
    public Conversation getById(Long cid) {
        if (cid == null) {
            return null;
        }
        synchronized (lock) {
            return copy(rows.get(cid));
        }
    }

    @Override
    public Conversation getByQuery(ConversationQuery query) {
        if (query == null) {
            return null;
        }
        synchronized (lock) {
            List<Conversation> matched = filterAndSort(query);
            // 宿主实现无条件 limit(1)
            return matched.isEmpty() ? null : copy(matched.get(0));
        }
    }

    // ── 2. 集合与批量查询 ──

    @Override
    public List<Conversation> listByQuery(ConversationQuery query) {
        if (query == null) {
            return List.of();
        }
        synchronized (lock) {
            List<Conversation> matched = filterAndSort(query);
            Integer limit = query.getLimit();
            if (limit != null && limit > 0 && matched.size() > limit) {
                matched = new ArrayList<>(matched.subList(0, limit));
            }
            List<Conversation> result = new ArrayList<>(matched.size());
            for (Conversation c : matched) {
                result.add(copy(c));
            }
            return result;
        }
    }

    // ── 3. 存在性轻量查询 ──

    @Override
    public boolean existsByQuery(ConversationQuery query) {
        if (query == null) {
            return false;
        }
        synchronized (lock) {
            return !filterAndSort(query).isEmpty();
        }
    }

    // ── 4. 实体新增与整实体更新 ──

    @Override
    public Conversation insert(Conversation entity) {
        if (entity == null) {
            return null;
        }
        synchronized (lock) {
            LocalDateTime now = LocalDateTime.now();
            if (entity.getCreateTime() == null) {
                entity.setCreateTime(now);
            }
            if (entity.getUpdateTime() == null) {
                entity.setUpdateTime(now);
            }
            // 宿主 @Id(keyType = Auto)：传入的显式 id 会被丢弃，统一自增并回填
            long id = ++seq;
            entity.setId(id);
            rows.put(id, copy(entity));
            return entity;
        }
    }

    @Override
    public List<Conversation> insertBatch(List<Conversation> entities) {
        if (entities == null) {
            return List.of();
        }
        // 宿主实现逐条调用 insert（保证逐条回填 id）
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
        synchronized (lock) {
            Conversation row = rows.get(entity.getId());
            if (row == null) {
                return 0;
            }
            // 整实体语义：仅非 null 字段覆盖，updateTime 无条件刷新
            applyNonNull(row, entity);
            row.setUpdateTime(LocalDateTime.now());
            return 1;
        }
    }

    // ── 5. 差量与条件批量更新 ──

    @Override
    public int updateByPatch(ConversationPatch patch) {
        if (patch == null || patch.getId() == null) {
            return 0;
        }
        synchronized (lock) {
            Conversation row = rows.get(patch.getId());
            if (row == null) {
                return 0;
            }
            boolean touchedUpdateTime = false;
            for (Map.Entry<String, Object> entry : patch.getChanged().entrySet()) {
                // Patch 分支：值 null 即清空（不做 ignoreNulls 过滤）
                applyField(row, entry.getKey(), entry.getValue());
                if ("updateTime".equals(entry.getKey())) {
                    touchedUpdateTime = true;
                }
            }
            if (!touchedUpdateTime) {
                row.setUpdateTime(LocalDateTime.now());
            }
            return 1;
        }
    }

    @Override
    public int updateByQuery(Conversation entity, ConversationQuery query) {
        if (entity == null || query == null) {
            return 0;
        }
        synchronized (lock) {
            requireCondition(query, "更新");
            LocalDateTime updateTime = entity.getUpdateTime() != null
                    ? entity.getUpdateTime() : LocalDateTime.now();
            List<Conversation> matched = filterAndSort(query);
            for (Conversation row : matched) {
                applyNonNull(row, entity);
                row.setUpdateTime(updateTime);
            }
            return matched.size();
        }
    }

    @Override
    public void updateByDelta(Long cid, SFunction<Conversation, String> fieldGetter, String delta) {
        if (cid == null || fieldGetter == null) {
            return;
        }
        String fieldName = LambdaFieldResolver.resolve(fieldGetter);
        synchronized (lock) {
            Conversation row = rows.get(cid);
            if (row == null) {
                // 宿主实现在会话不存在时 warn 后静默返回（不抛异常）
                return;
            }
            // null delta 是空操作：原值原样保留
            if (delta == null) {
                return;
            }
            String current = currentValue(row, fieldName);
            String next = processDeltaString(current, delta);
            applyField(row, fieldName, next);
            row.setUpdateTime(LocalDateTime.now());
        }
    }

    // ── 6. 物理删除与批量删除 ──

    @Override
    public void deleteById(Long cid) {
        if (cid == null) {
            return;
        }
        synchronized (lock) {
            rows.remove(cid);
        }
    }

    @Override
    public int deleteByQuery(ConversationQuery query) {
        if (query == null) {
            return 0;
        }
        synchronized (lock) {
            requireCondition(query, "删除");
            List<Conversation> matched = filterAndSort(query);
            for (Conversation row : matched) {
                rows.remove(row.getId());
            }
            return matched.size();
        }
    }

    // ── 测试辅助 ──

    /**
     * 当前存储中的会话总数
     */
    public int size() {
        synchronized (lock) {
            return rows.size();
        }
    }

    // ── 内部实现 ──

    /**
     * 读取集合字段当前值（仅支持两个差量白名单字段）
     */
    private String currentValue(Conversation row, String fieldName) {
        return switch (fieldName) {
            case "waitingToolIds" -> row.getWaitingToolIds();
            case "waitingSubCids" -> row.getWaitingSubCids();
            default -> throw new IllegalArgumentException("不支持的差量集合字段: " + fieldName);
        };
    }

    /**
     * 差量字符串变换（"+a"/"-a"/全量直写/空串清空四形态，集合以逗号串承载）
     */
    private String processDeltaString(String currentVal, String delta) {
        if (delta == null) {
            return currentVal;
        }
        if (delta.isEmpty()) {
            return null;
        }
        // 以逗号串为集合载体：去重保序（宿主用 LinkedHashSet）
        Set<String> items = new LinkedHashSet<>();
        if (currentVal != null && !currentVal.isBlank()) {
            for (String s : currentVal.split(",")) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) {
                    items.add(trimmed);
                }
            }
        }
        char prefix = delta.charAt(0);
        if (prefix == '+') {
            for (String s : delta.substring(1).split(",")) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) {
                    items.add(trimmed);
                }
            }
        } else if (prefix == '-') {
            for (String s : delta.substring(1).split(",")) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) {
                    items.remove(trimmed);
                }
            }
        } else {
            // 非差量前缀：全量直写
            return delta;
        }
        if (items.isEmpty()) {
            return null;
        }
        return String.join(",", items);
    }

    /**
     * 条件过滤 + 排序（排序仅在显式指定方向时生效，与宿主一致）
     */
    private List<Conversation> filterAndSort(ConversationQuery query) {
        List<Conversation> matched = new ArrayList<>();
        for (Conversation row : rows.values()) {
            if (matches(row, query)) {
                matched.add(row);
            }
        }
        SortDirection direction = query.getSortDirection();
        if (direction != null) {
            String field = StringUtils.isNotBlank(query.getSortField()) ? query.getSortField() : "id";
            matched.sort((a, b) -> {
                int cmp = compareField(a, b, field);
                return direction == SortDirection.ASC ? cmp : -cmp;
            });
        }
        return matched;
    }

    private int compareField(Conversation a, Conversation b, String field) {
        if ("createTime".equals(field)) {
            return compareTime(a.getCreateTime(), b.getCreateTime());
        }
        if ("updateTime".equals(field)) {
            return compareTime(a.getUpdateTime(), b.getUpdateTime());
        }
        return a.getId().compareTo(b.getId());
    }

    private int compareTime(LocalDateTime a, LocalDateTime b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        return a.compareTo(b);
    }

    private boolean matches(Conversation row, ConversationQuery q) {
        if (!eq(row.getId(), q.getId())) {
            return false;
        }
        if (!eq(row.getCreateTime(), q.getCreateTime())) {
            return false;
        }
        if (!eq(row.getUpdateTime(), q.getUpdateTime())) {
            return false;
        }
        if (!eq(row.getWorkspaceId(), q.getWorkspaceId())) {
            return false;
        }
        if (!eq(row.getTitle(), q.getTitle())) {
            return false;
        }
        if (!eq(row.getParentCid(), q.getParentCid())) {
            return false;
        }
        if (!eq(row.getLoopRunning(), q.getLoopRunning())) {
            return false;
        }
        if (q.getIds() != null && !q.getIds().isEmpty() && !q.getIds().contains(row.getId())) {
            return false;
        }
        return true;
    }

    private boolean eq(Object actual, Object expected) {
        return expected == null || expected.equals(actual);
    }

    /**
     * 写删路径全表守卫（宿主 ORM 拒绝无 WHERE 的 update/delete）
     */
    private void requireCondition(ConversationQuery query, String action) {
        boolean hasCondition = query.getId() != null
                || query.getCreateTime() != null
                || query.getUpdateTime() != null
                || query.getWorkspaceId() != null
                || query.getTitle() != null
                || query.getParentCid() != null
                || query.getLoopRunning() != null
                || (query.getIds() != null && !query.getIds().isEmpty());
        if (!hasCondition) {
            throw new IllegalArgumentException("内存 Store 拒绝无条件全表" + action + "（对齐宿主全表守卫）");
        }
    }

    private void applyField(Conversation row, String field, Object value) {
        switch (field) {
            case "title" -> row.setTitle((String) value);
            case "workspaceId" -> row.setWorkspaceId((String) value);
            case "providerGroup" -> row.setProviderGroup((String) value);
            case "providerModelName" -> row.setProviderModelName((String) value);
            case "permissionMode" -> row.setPermissionMode((String) value);
            case "parentCid" -> row.setParentCid((Long) value);
            case "inputTokens" -> row.setInputTokens((Long) value);
            case "outputTokens" -> row.setOutputTokens((Long) value);
            case "cachedTokens" -> row.setCachedTokens((Long) value);
            case "callToolCount" -> row.setCallToolCount((Integer) value);
            case "waitingToolIds" -> row.setWaitingToolIds((String) value);
            case "waitingSubCids" -> row.setWaitingSubCids((String) value);
            case "loopCount" -> row.setLoopCount((Integer) value);
            case "loopRunning" -> row.setLoopRunning((Integer) value);
            case "createTime" -> row.setCreateTime((LocalDateTime) value);
            case "updateTime" -> row.setUpdateTime((LocalDateTime) value);
            default -> {
                // 未知字段静默忽略
            }
        }
    }

    private void applyNonNull(Conversation row, Conversation source) {
        if (source.getWorkspaceId() != null) {
            row.setWorkspaceId(source.getWorkspaceId());
        }
        if (source.getTitle() != null) {
            row.setTitle(source.getTitle());
        }
        if (source.getProviderGroup() != null) {
            row.setProviderGroup(source.getProviderGroup());
        }
        if (source.getProviderModelName() != null) {
            row.setProviderModelName(source.getProviderModelName());
        }
        if (source.getPermissionMode() != null) {
            row.setPermissionMode(source.getPermissionMode());
        }
        if (source.getParentCid() != null) {
            row.setParentCid(source.getParentCid());
        }
        if (source.getInputTokens() != null) {
            row.setInputTokens(source.getInputTokens());
        }
        if (source.getOutputTokens() != null) {
            row.setOutputTokens(source.getOutputTokens());
        }
        if (source.getCachedTokens() != null) {
            row.setCachedTokens(source.getCachedTokens());
        }
        if (source.getCallToolCount() != null) {
            row.setCallToolCount(source.getCallToolCount());
        }
        if (source.getWaitingToolIds() != null) {
            row.setWaitingToolIds(source.getWaitingToolIds());
        }
        if (source.getWaitingSubCids() != null) {
            row.setWaitingSubCids(source.getWaitingSubCids());
        }
        if (source.getLoopCount() != null) {
            row.setLoopCount(source.getLoopCount());
        }
        if (source.getLoopRunning() != null) {
            row.setLoopRunning(source.getLoopRunning());
        }
        if (source.getCreateTime() != null) {
            row.setCreateTime(source.getCreateTime());
        }
    }

    /**
     * 深拷贝会话实体（模拟数据库往返得到的新对象）
     */
    private Conversation copy(Conversation c) {
        if (c == null) {
            return null;
        }
        return Conversation.builder()
                .id(c.getId())
                .createTime(c.getCreateTime())
                .updateTime(c.getUpdateTime())
                .workspaceId(c.getWorkspaceId())
                .title(c.getTitle())
                .providerGroup(c.getProviderGroup())
                .providerModelName(c.getProviderModelName())
                .permissionMode(c.getPermissionMode())
                .parentCid(c.getParentCid())
                .inputTokens(c.getInputTokens())
                .outputTokens(c.getOutputTokens())
                .cachedTokens(c.getCachedTokens())
                .callToolCount(c.getCallToolCount())
                .waitingToolIds(c.getWaitingToolIds())
                .waitingSubCids(c.getWaitingSubCids())
                .loopCount(c.getLoopCount())
                .loopRunning(c.getLoopRunning())
                .build();
    }
}
