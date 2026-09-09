package com.stioc.cute.engine.store;

import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessagePatch;
import com.stioc.cute.engine.store.types.MessageQuery;

import java.util.List;

/**
 * 引擎消息存储供血接口（宿主实现）。
 * <p>
 * 纯 CRUD 存储层契约，方法严格遵循以下逻辑顺序排列：
 * 1. 单实体查询（get 前缀，返回单个实体，查无返回 null）
 * 2. 集合与批量查询（list 前缀，返回 List&lt;Message&gt;）
 * 3. 存在性轻量查询（exists 前缀）
 * 4. 实体新增与整实体更新（insert / updateById）
 * 5. 差量与条件批量更新（updateByPatch / updateByQuery）
 * 6. 物理删除与条件批量删除（delete 前缀）
 * </p>
 */
public interface MessageStore {

    // ── 1. 单实体查询 ──

    /**
     * 根据主键查询消息实体（查无返回 null，保留供未来扩展）
     */
    Message getById(Long id);

    /**
     * 根据动态查询条件查询单个消息实体（查无返回 null，匹配多条按排序规则取首条）
     */
    Message getByQuery(MessageQuery query);

    // ── 2. 集合与批量查询 ──

    /**
     * 根据动态查询条件获取消息列表
     */
    List<Message> listByQuery(MessageQuery query);

    // ── 3. 存在性与状态轻量查询 ──

    /**
     * 判断是否存在满足指定条件的消息实体
     */
    boolean existsByQuery(MessageQuery query);

    // ── 4. 实体新增与整实体更新 ──

    /**
     * 基础消息插入（同步落库并回填 id）
     */
    void insert(Message entity);

    /**
     * 批量消息插入（同步落库并回填 id）
     */
    void insertBatch(List<Message> entities);

    /**
     * 基础消息更新（按 ID 更新非空字段，整实体语义，保留供扩展）
     *
     * @return 实际更新影响行数
     */
    int updateById(Message entity);

    // ── 5. 差量与批量更新 ──

    /**
     * 按差量载荷更新消息（仅 Patch 携带的变化字段生效）
     *
     * @return 实际更新影响行数
     */
    int updateByPatch(MessagePatch patch);

    /**
     * 根据动态查询条件批量更新消息实体非空字段
     *
     * @return 实际更新影响行数
     */
    int updateByQuery(Message entity, MessageQuery query);

    // ── 6. 物理删除与批量删除 ──

    /**
     * 按主键物理删除单条消息（标准 CRUD 对称能力）
     */
    void deleteById(Long id);

    /**
     * 根据动态查询条件批量物理删除消息记录
     *
     * @return 实际删除影响行数
     */
    int deleteByQuery(MessageQuery query);
}
