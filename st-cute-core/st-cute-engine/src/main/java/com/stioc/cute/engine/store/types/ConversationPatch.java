package com.stioc.cute.engine.store.types;

import com.stioc.cute.engine.common.LambdaFieldResolver;
import com.stioc.cute.engine.common.SFunction;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话差量更新载荷（引擎侧事件载体，替代宿主 ORM 的 UpdateEntity 角色）。
 * <p>
 * 仅携带发生变化的字段：put 进 Map 的键值对表示"该字段需更新为新值"，
 * 值为 null 表示"清空该字段"（需配合 {@link #setChanged} 显式标记，避免 null 歧义）。
 * 差量集合字段（waitingToolIds/waitingSubCids）支持 "+id"/"-id" 前缀语义，
 * 由宿主 Store 的 applyXxxDelta 原子方法消费（引擎持久化监听器先剥离再整实体落库其余字段）。
 * </p>
 * <p>
 * 字段键全部经 lambda 方法引用（{@code Conversation::getXxx}）在写入/读取时推导，
 * 实体字段改名时编译期即报错，杜绝硬编码字符串静默断链。
 * </p>
 */
public class ConversationPatch {

    /**
     * 目标会话主键
     */
    private final Long id;

    /**
     * 发生变化的字段集（字段名 → 新值；插入顺序即字段声明序，落库顺序稳定）
     */
    private final Map<String, Object> changed = new LinkedHashMap<>();

    public ConversationPatch(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public Map<String, Object> getChanged() {
        return changed;
    }

    /**
     * 通用写入（字符串键，保留给内核与既有消费方；业务侧请优先使用带 getter 的快捷方法）
     */
    public ConversationPatch set(String field, Object value) {
        changed.put(field, value);
        return this;
    }

    /**
     * 类型安全写入：字段键由 getter 方法引用推导
     */
    public <R> ConversationPatch set(SFunction<Conversation, R> getter, R value) {
        changed.put(LambdaFieldResolver.resolve(getter), value);
        return this;
    }

    /**
     * 类型安全读取：字段键由 getter 方法引用推导，返回值类型随 getter 推导。
     * （强转安全：键与值经同一 getter 写入，运行期类型必然一致）
     */
    @SuppressWarnings("unchecked")
    public <R> R get(SFunction<Conversation, R> getter) {
        return (R) changed.get(LambdaFieldResolver.resolve(getter));
    }

    /**
     * 类型安全存在性判定：字段键由 getter 方法引用推导
     */
    public boolean has(SFunction<Conversation, ?> getter) {
        return changed.containsKey(LambdaFieldResolver.resolve(getter));
    }

    /**
     * 类型安全移除：字段键由 getter 方法引用推导
     */
    public void remove(SFunction<Conversation, ?> getter) {
        changed.remove(LambdaFieldResolver.resolve(getter));
    }

    /**
     * 通用读取（字符串键，保留给内核与既有消费方）
     */
    public Object get(String field) {
        return changed.get(field);
    }

    /**
     * 通用存在性判定（字符串键，保留给内核与既有消费方）
     */
    public boolean has(String field) {
        return changed.containsKey(field);
    }

    public boolean isEmpty() {
        return changed.isEmpty();
    }

    // ── 常用字段快捷方法（与 Conversation 字段一一对应，键经方法引用编译期锁定）──

    public ConversationPatch title(String v) { return set(Conversation::getTitle, v); }

    public ConversationPatch createTime(LocalDateTime v) { return set(Conversation::getCreateTime, v); }

    public ConversationPatch updateTime(LocalDateTime v) { return set(Conversation::getUpdateTime, v); }

    public ConversationPatch workspaceId(String v) { return set(Conversation::getWorkspaceId, v); }

    public ConversationPatch providerGroup(String v) { return set(Conversation::getProviderGroup, v); }

    public ConversationPatch providerModelName(String v) { return set(Conversation::getProviderModelName, v); }

    public ConversationPatch permissionMode(String v) { return set(Conversation::getPermissionMode, v); }

    public ConversationPatch parentCid(Long v) { return set(Conversation::getParentCid, v); }

    public ConversationPatch inputTokens(Long v) { return set(Conversation::getInputTokens, v); }

    public ConversationPatch outputTokens(Long v) { return set(Conversation::getOutputTokens, v); }

    public ConversationPatch cachedTokens(Long v) { return set(Conversation::getCachedTokens, v); }

    public ConversationPatch callToolCount(Integer v) { return set(Conversation::getCallToolCount, v); }

    /**
     * 差量更新等待工具集合：value 支持 "+callId"/"-callId"/全量直写/null 清空四种形态
     * （null 由引擎持久化层留给 updateByPatch 置 null 落库）
     */
    public ConversationPatch waitingToolIds(String v) { return set(Conversation::getWaitingToolIds, v); }

    /**
     * 差量更新等待子会话集合：value 支持 "+subCid"/"-subCid"/全量直写/null 清空四种形态
     * （null 由引擎持久化层留给 updateByPatch 置 null 落库）
     */
    public ConversationPatch waitingSubCids(String v) { return set(Conversation::getWaitingSubCids, v); }

    public ConversationPatch loopCount(Integer v) { return set(Conversation::getLoopCount, v); }

    public ConversationPatch loopRunning(Integer v) { return set(Conversation::getLoopRunning, v); }
}
