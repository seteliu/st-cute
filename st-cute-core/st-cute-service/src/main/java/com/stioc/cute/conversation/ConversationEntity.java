package com.stioc.cute.conversation;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.stioc.cute.engine.store.types.Conversation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话持久化实体（宿主侧，绑定 SQLite t_conversation 表）。
 * <p>引擎模型 {@link Conversation} 为纯 POJO，本类承担表结构映射，
 * 转换逻辑收口在 {@code ConversationStoreImpl}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "t_conversation")
public class ConversationEntity {

    /**
     * 会话主键 ID
     */
    @Id(keyType = KeyType.Auto)
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
     * 工作区标识（Coding：项目 ID 字符串；SaaS：租户 ID 等）
     */
    private String workspaceId;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 选用的模型供应商分组名
     */
    private String providerGroup;

    /**
     * 选用的具体模型名称
     */
    private String providerModelName;

    /**
     * 权限兜底模式名称
     */
    private String permissionMode;

    /**
     * 父会话唯一 ID
     */
    private Long parentCid;

    /**
     * 最近一次 LLM 调用返回的输入 token 快照。
     */
    private Long inputTokens;

    /**
     * 最近一次 LLM 调用返回的输出 token 快照。
     */
    private Long outputTokens;

    /**
     * 最近一次 LLM 调用返回的缓存 token 快照。
     */
    private Long cachedTokens;

    /**
     * 当前轮次已发出的工具调用总数。
     */
    private Integer callToolCount;

    /**
     * 本轮正在等待执行结果的工具 toolCallId 集合，逗号分隔字符串。
     */
    private String waitingToolIds;

    /**
     * 当前正在后台运行、尚未完成的子 Agent 会话 ID 集合，逗号分隔字符串。
     */
    private String waitingSubCids;

    /**
     * 当前会话循环轮次（第几轮）。
     */
    private Integer loopCount;

    /**
     * 当前 ReAct 循环是否正在运行中 (1是 0否)
     */
    private Integer loopRunning;
}
