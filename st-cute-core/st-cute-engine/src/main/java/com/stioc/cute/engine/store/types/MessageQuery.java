package com.stioc.cute.engine.store.types;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * 消息动态查询参数载荷（纯 Java POJO，零 ORM 依赖）。
 * <p>
 * 属性顺序严格对齐 Message 领域模型实体定义，复合查询与控制字段统一放于末尾。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageQuery {

    // ── 1. 实体标准属性对齐（顺序完全对齐 Message 实体） ──

    /**
     * 消息主键 ID
     */
    private Long id;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 所属会话 ID
     */
    private Long cid;

    /**
     * 父级助手消息 ID
     */
    private Long parentMessageId;

    /**
     * 消息角色
     */
    private MessageRole role;

    /**
     * 工具调用唯一标识 ID（仅 TOOL 角色行）
     */
    private String callId;

    /**
     * 消息当前状态
     */
    private MessageStatus status;

    /**
     * 该消息是否对前端用户可见
     */
    private Boolean visibleToUser;

    /**
     * 该消息在组装上下文历史时是否对大模型可见
     */
    private Boolean visibleToModel;

    // ── 2. 扩展查询条件（批量、范围、排除条件） ──

    /**
     * 批量主键 ID 集合 (id IN (...))
     */
    private Collection<Long> ids;

    /**
     * 排除的主键 ID 集合 (id NOT IN (...))
     */
    private Collection<Long> excludedIds;

    /**
     * 批量所属会话 ID 集合 (cid IN (...))
     */
    private Collection<Long> cids;

    /**
     * ID 闭区间下界 (id >= minId)
     */
    private Long minId;

    /**
     * ID 闭区间上界 (id <= maxId)
     */
    private Long maxId;

    /**
     * ID 开区间下界 (id > greaterThanId)
     */
    private Long greaterThanId;

    /**
     * 创建时间上界 (create_time < createTimeBefore)
     */
    private LocalDateTime createTimeBefore;

    /**
     * 多角色筛选集合 (role IN (...))
     */
    private Collection<MessageRole> roles;

    /**
     * 多状态筛选集合 (status IN (...))
     */
    private Collection<MessageStatus> statuses;

    // ── 3. 排序、分页与轻量投影控制 ──

    /**
     * 排序字段名（默认 "id"）
     */
    @Builder.Default
    private String sortField = "id";

    /**
     * 排序方向（默认为 null，即不显式增加 ORDER BY）
     */
    private SortDirection sortDirection;

    /**
     * 最大返回数量限制 (LIMIT)
     */
    private Integer limit;

    /**
     * 是否执行轻查询（true 时仅 SELECT 边界判定必要小字段，规避 TEXT 溢出页 I/O）
     */
    @Builder.Default
    private boolean light = false;
}
