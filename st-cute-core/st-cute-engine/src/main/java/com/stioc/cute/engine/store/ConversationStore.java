package com.stioc.cute.engine.store;

import com.stioc.cute.engine.common.SFunction;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.engine.store.types.ConversationQuery;

import java.util.List;

/**
 * 引擎会话存储供血接口（宿主实现）。
 * <p>
 * 纯 CRUD 存储层契约，方法严格遵循以下逻辑顺序排列：
 * 1. 单实体查询（get 前缀，返回单个实体，查无返回 null）
 * 2. 集合与批量查询（list 前缀，返回 List&lt;Conversation&gt;）
 * 3. 存在性轻量查询（exists 前缀）
 * 4. 实体新增与整实体更新（insert / updateById）
 * 5. 差量与条件批量更新（updateByPatch / updateByQuery / updateByDelta）
 * 6. 物理删除与批量删除（delete 前缀）
 * </p>
 */
public interface ConversationStore {

    // ── 1. 单实体查询 ──

    /**
     * 根据主键 ID 查询会话实体（查无返回 null）
     */
    Conversation getById(Long cid);

    /**
     * 根据动态查询条件查询单个会话实体（查无返回 null，匹配多个取首条）
     */
    Conversation getByQuery(ConversationQuery query);

    // ── 2. 集合与批量查询 ──

    /**
     * 根据动态查询条件获取会话列表
     */
    List<Conversation> listByQuery(ConversationQuery query);

    // ── 3. 存在性轻量查询 ──

    /**
     * 判断是否存在满足指定条件的会话实体
     */
    boolean existsByQuery(ConversationQuery query);

    // ── 4. 实体新增与整实体更新 ──

    /**
     * 会话新增（纯 CRUD 插入，若 entity.getId() 为空则自增并回填，若已存在则物理插入）
     */
    Conversation insert(Conversation entity);

    /**
     * 批量新增会话实体
     */
    List<Conversation> insertBatch(List<Conversation> entities);

    /**
     * 整实体更新（按 ID 更新非空字段）
     *
     * @return 实际更新影响行数
     */
    int updateById(Conversation entity);

    // ── 5. 差量与条件批量更新 ──

    /**
     * 按差量载荷更新会话（仅 Patch 携带的变化字段生效，null 值表示清空该字段）。
     * 集合字段（waitingToolIds/waitingSubCids）非 null 的 "+id"/"-id" 差量
     * 已由引擎持久化层先行剥离并经 updateByDelta 消费；
     * 值为 null 的集合字段（清空语义）则保留在 Patch 中经本方法直写置空。
     *
     * @return 实际更新影响行数
     */
    int updateByPatch(ConversationPatch patch);

    /**
     * 根据动态查询条件批量更新会话实体非空字段
     *
     * @return 实际更新影响行数
     */
    int updateByQuery(Conversation entity, ConversationQuery query);

    /**
     * 差量原子更新会话的集合字段（delta 形如 "+id"/"-id"，字段由 getter 方法引用强类型指定）。
     * 警告：仅供引擎事件持久化层调用，其它业务场景一律禁止越权直接调用！
     *
     * @param cid 会话主键 ID
     * @param fieldGetter 目标字段的 getter 方法引用（如 Conversation::getWaitingToolIds）
     * @param delta 差量描述字符串（如 "+toolCallId"、"-toolCallId"）
     */
    void updateByDelta(Long cid, SFunction<Conversation, String> fieldGetter, String delta);

    // ── 6. 物理删除与批量删除 ──

    /**
     * 按主键物理删除会话行（消息级联删除由引擎监听器先调 MessageStore 完成）
     */
    void deleteById(Long cid);

    /**
     * 根据动态查询条件批量物理删除会话记录
     *
     * @return 实际删除影响行数
     */
    int deleteByQuery(ConversationQuery query);
}
