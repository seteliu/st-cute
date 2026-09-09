package com.stioc.cute.engine.store.types;

import com.stioc.cute.engine.common.LambdaFieldResolver;
import com.stioc.cute.engine.common.SFunction;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息差量更新载荷（引擎侧事件载体，替代宿主 ORM 的 UpdateEntity 角色）。
 * <p>
 * 仅携带发生变化的字段：put 进 Map 的键值对表示"该字段需更新为新值"。
 * 宿主 Store 的 updateById 实现负责翻译为 ORM 更新语句。
 * </p>
 * <p>
 * 字段键全部经 lambda 方法引用（{@code Message::getXxx}）在写入/读取时推导，
 * 实体字段改名时编译期即报错，杜绝硬编码字符串静默断链。
 * </p>
 */
public class MessagePatch {

    /**
     * 目标消息主键
     */
    private final Long id;

    /**
     * 发生变化的字段集（字段名 → 新值；插入顺序即字段声明序，落库顺序稳定）
     */
    private final Map<String, Object> changed = new LinkedHashMap<>();

    public MessagePatch(Long id) {
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
    public MessagePatch set(String field, Object value) {
        changed.put(field, value);
        return this;
    }

    /**
     * 类型安全写入：字段键由 getter 方法引用推导
     */
    public <R> MessagePatch set(SFunction<Message, R> getter, R value) {
        changed.put(LambdaFieldResolver.resolve(getter), value);
        return this;
    }

    /**
     * 类型安全读取：字段键由 getter 方法引用推导，返回值类型随 getter 推导。
     * （强转安全：键与值经同一 getter 写入，运行期类型必然一致）
     */
    @SuppressWarnings("unchecked")
    public <R> R get(SFunction<Message, R> getter) {
        return (R) changed.get(LambdaFieldResolver.resolve(getter));
    }

    /**
     * 类型安全存在性判定：字段键由 getter 方法引用推导
     */
    public boolean has(SFunction<Message, ?> getter) {
        return changed.containsKey(LambdaFieldResolver.resolve(getter));
    }

    /**
     * 类型安全移除：字段键由 getter 方法引用推导
     */
    public void remove(SFunction<Message, ?> getter) {
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

    // ── 常用字段快捷方法（与 Message 字段一一对应，键经方法引用编译期锁定）──

    /**
     * 消息所属会话 ID（归一走 changed Map 落库，宿主翻译层可读取校验归属）
     */
    public MessagePatch cid(Long v) { return set(Message::getCid, v); }

    public MessagePatch parentMessageId(Long v) { return set(Message::getParentMessageId, v); }

    public MessagePatch role(MessageRole v) { return set(Message::getRole, v); }

    public MessagePatch content(String v) { return set(Message::getContent, v); }

    public MessagePatch reasoningContent(String v) { return set(Message::getReasoningContent, v); }

    public MessagePatch toolCalls(String v) { return set(Message::getToolCalls, v); }

    public MessagePatch callId(String v) { return set(Message::getCallId, v); }

    public MessagePatch status(MessageStatus v) { return set(Message::getStatus, v); }

    public MessagePatch visibleToUser(Boolean v) { return set(Message::getVisibleToUser, v); }

    public MessagePatch visibleToModel(Boolean v) { return set(Message::getVisibleToModel, v); }

    public MessagePatch inputTokens(Long v) { return set(Message::getInputTokens, v); }

    public MessagePatch outputTokens(Long v) { return set(Message::getOutputTokens, v); }

    public MessagePatch cachedTokens(Long v) { return set(Message::getCachedTokens, v); }

    public MessagePatch executionDurationMs(Long v) { return set(Message::getExecutionDurationMs, v); }

    public MessagePatch attachments(String v) { return set(Message::getAttachments, v); }

    public MessagePatch createTime(LocalDateTime v) { return set(Message::getCreateTime, v); }

    public MessagePatch updateTime(LocalDateTime v) { return set(Message::getUpdateTime, v); }
}
