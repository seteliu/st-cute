package com.stioc.cute.engine.store.types;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * 会话动态查询参数载荷（纯 Java POJO，零 ORM 依赖）。
 * <p>
 * 属性顺序严格对齐 Conversation 领域模型实体定义，复合查询与控制字段统一放于末尾。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationQuery {

    // ── 1. 实体标准属性对齐（顺序完全对齐 Conversation 实体） ──

    /**
     * 会话主键 ID
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
     * 工作区标识
     */
    private String workspaceId;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 父会话唯一 ID
     */
    private Long parentCid;

    /**
     * 当前 ReAct 循环是否正在运行中 (1是 0否)
     */
    private Integer loopRunning;

    // ── 2. 扩展查询条件 ──

    /**
     * 批量会话主键 ID 集合 (id IN (...))
     */
    private Collection<Long> ids;

    // ── 3. 排序与分页控制 ──

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
}
