package com.stioc.cute.engine.testkit;

import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessagePatch;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.store.types.SortDirection;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版消息存储替身，语义严格对齐宿主 MyBatis-Flex 实现。
 * <p>
 * 闭环测试必须让「事件链 → 落库 → 内存回填 → 屏障判定」真实跑通，故这里不是空壳替身，
 * 而是按宿主 {@code MessageStoreImpl} 的既有语义逐条复刻：
 * <ul>
 *   <li><b>insert 自增回填</b>：入库时忽略调用方传入的 id，统一分配自增 id 并回填到入参实体——
 *       引擎的 {@code MessageDataReporter.createAssistantMessage} 依赖 {@code entity.getId()}
 *       在事件发布返回后即可读到（宿主 {@code MessageStoreImpl.insert} 同款行为）；</li>
 *   <li><b>insertBatch 逐条单插</b>：宿主的 insertBatch 是循环调 insert（保证逐条回填 id），
 *       并非真正的批量 API，故此处同样逐条插入；</li>
 *   <li><b>updateByPatch 的 null 即清空</b>：Patch 中显式记录的 null 会真实写为 null；</li>
 *   <li><b>updateByQuery 的 null 即跳过</b>：只覆盖非 null 字段——两套 null 规则并存是宿主既有语义，
 *       也是最容易被替身做错的地方，故显式区分；</li>
 *   <li><b>updateTime 兜底</b>：updateById 无条件刷新 updateTime，updateByPatch /
 *       updateByQuery 在载荷未携带时补当前时间（宿主无任何 ORM 自动填充）；</li>
 *   <li><b>读路径放开、写删路径守卫</b>：宿主对无 WHERE 的 update/delete 直接抛全表操作异常，
 *       读路径则不设防；此处照抄，防误清库。</li>
 * </ul>
 * </p>
 * <p>
 * 查询结果一律返回深拷贝：宿主每次查询都是数据库往返得到的新对象，若返回内部实例，
 * 引擎侧的就地修改（如自愈扫描链路改状态后回写）会污染存储，导致测试假绿。
 * </p>
 */
public class InMemoryMessageStore implements MessageStore {

    /**
     * 内部互斥锁：闭环测试中循环线程、工具批线程与事件通知线程会并发触达存储
     */
    private final Object lock = new Object();

    /**
     * 消息表（按插入序保持稳定遍历顺序，对齐 SQLite rowid 自然序）
     */
    private final Map<Long, Message> rows = new LinkedHashMap<>();

    /**
     * 自增主键序列
     */
    private long seq = 0L;

    // ── 1. 单实体查询 ──

    @Override
    public Message getById(Long id) {
        if (id == null) {
            return null;
        }
        synchronized (lock) {
            return copy(rows.get(id));
        }
    }

    @Override
    public Message getByQuery(MessageQuery query) {
        if (query == null) {
            return null;
        }
        synchronized (lock) {
            List<Message> matched = filterAndSort(query);
            // 宿主实现无条件 wrapper.limit(1)，调用方即便设了 limit(N) 也会被压成 1
            return matched.isEmpty() ? null : copy(matched.get(0));
        }
    }

    // ── 2. 集合与批量查询 ──

    @Override
    public List<Message> listByQuery(MessageQuery query) {
        if (query == null) {
            return List.of();
        }
        synchronized (lock) {
            List<Message> matched = filterAndSort(query);
            Integer limit = query.getLimit();
            if (limit != null && limit > 0 && matched.size() > limit) {
                matched = new ArrayList<>(matched.subList(0, limit));
            }
            List<Message> result = new ArrayList<>(matched.size());
            for (Message m : matched) {
                result.add(copy(m));
            }
            return result;
        }
    }

    // ── 3. 存在性轻量查询 ──

    @Override
    public boolean existsByQuery(MessageQuery query) {
        if (query == null) {
            return false;
        }
        synchronized (lock) {
            return !filterAndSort(query).isEmpty();
        }
    }

    // ── 4. 实体新增与整实体更新 ──

    @Override
    public void insert(Message entity) {
        if (entity == null) {
            return;
        }
        synchronized (lock) {
            LocalDateTime now = LocalDateTime.now();
            if (entity.getCreateTime() == null) {
                entity.setCreateTime(now);
            }
            if (entity.getUpdateTime() == null) {
                entity.setUpdateTime(now);
            }
            // 宿主 @Id(keyType = Auto)：显式传入的 id 会被丢弃，统一走自增并回填
            long id = ++seq;
            entity.setId(id);
            rows.put(id, copy(entity));
        }
    }

    @Override
    public void insertBatch(List<Message> entities) {
        if (entities == null) {
            return;
        }
        // 宿主实现逐条调用 insert（既有真正的批量 API 但刻意未用，以保证逐条回填 id）
        for (Message entity : entities) {
            insert(entity);
        }
    }

    @Override
    public int updateById(Message entity) {
        if (entity == null || entity.getId() == null) {
            return 0;
        }
        synchronized (lock) {
            Message row = rows.get(entity.getId());
            if (row == null) {
                return 0;
            }
            // 整实体语义：仅非 null 字段覆盖（ignoreNulls），且 updateTime 无条件刷新
            applyNonNull(row, entity);
            row.setUpdateTime(LocalDateTime.now());
            return 1;
        }
    }

    // ── 5. 差量与批量更新 ──

    @Override
    public int updateByPatch(MessagePatch patch) {
        if (patch == null || patch.getId() == null) {
            return 0;
        }
        synchronized (lock) {
            Message row = rows.get(patch.getId());
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
    public int updateByQuery(Message entity, MessageQuery query) {
        if (entity == null || query == null) {
            return 0;
        }
        synchronized (lock) {
            requireCondition(query, "更新");
            LocalDateTime updateTime = entity.getUpdateTime() != null
                    ? entity.getUpdateTime() : LocalDateTime.now();
            List<Message> matched = filterAndSort(query);
            for (Message row : matched) {
                // 批量条件更新走 ignoreNulls 分支：只覆盖非 null 字段
                applyNonNull(row, entity);
                row.setUpdateTime(updateTime);
            }
            return matched.size();
        }
    }

    // ── 6. 物理删除与批量删除 ──

    @Override
    public void deleteById(Long id) {
        if (id == null) {
            return;
        }
        synchronized (lock) {
            rows.remove(id);
        }
    }

    @Override
    public int deleteByQuery(MessageQuery query) {
        if (query == null) {
            return 0;
        }
        synchronized (lock) {
            requireCondition(query, "删除");
            List<Message> matched = filterAndSort(query);
            for (Message row : matched) {
                rows.remove(row.getId());
            }
            return matched.size();
        }
    }

    // ── 测试辅助 ──

    /**
     * 当前存储中的消息总数（供用例做无残留校验）
     */
    public int size() {
        synchronized (lock) {
            return rows.size();
        }
    }

    // ── 内部实现 ──

    /**
     * 条件过滤 + 排序（排序仅在显式指定方向时生效，与宿主一致）
     */
    private List<Message> filterAndSort(MessageQuery query) {
        List<Message> matched = new ArrayList<>();
        for (Message row : rows.values()) {
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

    /**
     * 按字段名比较（引擎侧实际只用 id 排序，其余字段为防御性支持）
     */
    private int compareField(Message a, Message b, String field) {
        if ("id".equals(field)) {
            return a.getId().compareTo(b.getId());
        }
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

    /**
     * 单行与查询条件的匹配判定（字段全覆盖，与宿主查询条件翻译逐条对齐）
     */
    private boolean matches(Message row, MessageQuery q) {
        if (!eq(row.getId(), q.getId())) {
            return false;
        }
        if (!eq(row.getCreateTime(), q.getCreateTime())) {
            return false;
        }
        if (!eq(row.getUpdateTime(), q.getUpdateTime())) {
            return false;
        }
        if (!eq(row.getCid(), q.getCid())) {
            return false;
        }
        if (!eq(row.getParentMessageId(), q.getParentMessageId())) {
            return false;
        }
        if (!eq(row.getRole(), q.getRole())) {
            return false;
        }
        // callId 走 isNotBlank 判定：空串视为未设置（宿主同款）
        if (StringUtils.isNotBlank(q.getCallId()) && !q.getCallId().equals(row.getCallId())) {
            return false;
        }
        if (!eq(row.getStatus(), q.getStatus())) {
            return false;
        }
        if (!eq(row.getVisibleToUser(), q.getVisibleToUser())) {
            return false;
        }
        if (!eq(row.getVisibleToModel(), q.getVisibleToModel())) {
            return false;
        }
        if (nonEmpty(q.getIds()) && !q.getIds().contains(row.getId())) {
            return false;
        }
        if (nonEmpty(q.getExcludedIds()) && q.getExcludedIds().contains(row.getId())) {
            return false;
        }
        if (nonEmpty(q.getCids()) && !q.getCids().contains(row.getCid())) {
            return false;
        }
        if (q.getMinId() != null && row.getId() < q.getMinId()) {
            return false;
        }
        if (q.getMaxId() != null && row.getId() > q.getMaxId()) {
            return false;
        }
        if (q.getGreaterThanId() != null && row.getId() <= q.getGreaterThanId()) {
            return false;
        }
        if (q.getCreateTimeBefore() != null
                && (row.getCreateTime() == null || !row.getCreateTime().isBefore(q.getCreateTimeBefore()))) {
            return false;
        }
        if (nonEmpty(q.getRoles()) && !q.getRoles().contains(row.getRole())) {
            return false;
        }
        if (nonEmpty(q.getStatuses()) && !q.getStatuses().contains(row.getStatus())) {
            return false;
        }
        if (nonEmpty(q.getExcludedRoles()) && q.getExcludedRoles().contains(row.getRole())) {
            return false;
        }
        if (nonEmpty(q.getExcludedStatuses()) && q.getExcludedStatuses().contains(row.getStatus())) {
            return false;
        }
        return true;
    }

    /**
     * 非空条件判定（null 视为不参与过滤）
     */
    private boolean eq(Object actual, Object expected) {
        return expected == null || expected.equals(actual);
    }

    private boolean nonEmpty(java.util.Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    /**
     * 写删路径的全表守卫：宿主 ORM 对无 WHERE 的 update/delete 直接抛异常，此处复刻以防误清库
     */
    private void requireCondition(MessageQuery query, String action) {
        boolean hasCondition = query.getId() != null
                || query.getCreateTime() != null
                || query.getUpdateTime() != null
                || query.getCid() != null
                || query.getParentMessageId() != null
                || query.getRole() != null
                || StringUtils.isNotBlank(query.getCallId())
                || query.getStatus() != null
                || query.getVisibleToUser() != null
                || query.getVisibleToModel() != null
                || nonEmpty(query.getIds())
                || nonEmpty(query.getExcludedIds())
                || nonEmpty(query.getCids())
                || query.getMinId() != null
                || query.getMaxId() != null
                || query.getGreaterThanId() != null
                || query.getCreateTimeBefore() != null
                || nonEmpty(query.getRoles())
                || nonEmpty(query.getStatuses())
                || nonEmpty(query.getExcludedRoles())
                || nonEmpty(query.getExcludedStatuses());
        if (!hasCondition) {
            throw new IllegalArgumentException("内存 Store 拒绝无条件全表" + action + "（对齐宿主全表守卫）");
        }
    }

    /**
     * 按 Patch 字段名写入单字段（键名由 lambda 方法引用推导，与实体字段一一对应）
     */
    private void applyField(Message row, String field, Object value) {
        switch (field) {
            case "cid" -> row.setCid((Long) value);
            case "parentMessageId" -> row.setParentMessageId((Long) value);
            case "role" -> row.setRole((MessageRole) value);
            case "content" -> row.setContent((String) value);
            case "reasoningContent" -> row.setReasoningContent((String) value);
            case "toolCalls" -> row.setToolCalls((String) value);
            case "callId" -> row.setCallId((String) value);
            case "status" -> row.setStatus((MessageStatus) value);
            case "visibleToUser" -> row.setVisibleToUser((Boolean) value);
            case "visibleToModel" -> row.setVisibleToModel((Boolean) value);
            case "inputTokens" -> row.setInputTokens((Long) value);
            case "outputTokens" -> row.setOutputTokens((Long) value);
            case "cachedTokens" -> row.setCachedTokens((Long) value);
            case "executionDurationMs" -> row.setExecutionDurationMs((Long) value);
            case "attachments" -> row.setAttachments((String) value);
            case "createTime" -> row.setCreateTime((LocalDateTime) value);
            case "updateTime" -> row.setUpdateTime((LocalDateTime) value);
            default -> {
                // 未知字段静默忽略：宿主由 UpdateEntity 动态代理记录、未知键不会出现在 Patch 中
            }
        }
    }

    /**
     * 整实体覆盖：仅非 null 字段生效（ignoreNulls 语义）
     */
    private void applyNonNull(Message row, Message source) {
        if (source.getCid() != null) {
            row.setCid(source.getCid());
        }
        if (source.getParentMessageId() != null) {
            row.setParentMessageId(source.getParentMessageId());
        }
        if (source.getRole() != null) {
            row.setRole(source.getRole());
        }
        if (source.getContent() != null) {
            row.setContent(source.getContent());
        }
        if (source.getReasoningContent() != null) {
            row.setReasoningContent(source.getReasoningContent());
        }
        if (source.getToolCalls() != null) {
            row.setToolCalls(source.getToolCalls());
        }
        if (source.getCallId() != null) {
            row.setCallId(source.getCallId());
        }
        if (source.getStatus() != null) {
            row.setStatus(source.getStatus());
        }
        // 注意：visibleToUser / visibleToModel 带 @Builder.Default = true，
        // 即 builder 构造出的实体这两个字段恒非 null，会照实参与覆盖（宿主既有语义，非缺陷）
        if (source.getVisibleToUser() != null) {
            row.setVisibleToUser(source.getVisibleToUser());
        }
        if (source.getVisibleToModel() != null) {
            row.setVisibleToModel(source.getVisibleToModel());
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
        if (source.getExecutionDurationMs() != null) {
            row.setExecutionDurationMs(source.getExecutionDurationMs());
        }
        if (source.getAttachments() != null) {
            row.setAttachments(source.getAttachments());
        }
        if (source.getCreateTime() != null) {
            row.setCreateTime(source.getCreateTime());
        }
    }

    /**
     * 深拷贝消息实体（模拟数据库往返得到的新对象，避免存储内部实例被就地修改污染）
     */
    private Message copy(Message m) {
        if (m == null) {
            return null;
        }
        return Message.builder()
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
}
