package com.stioc.cute.engine.store.types;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息模型（引擎纯 POJO，零持久化绑定）。
 * <p>
 * 记录每轮会话的正文、思考链以及工具调用。表结构映射与物理落库是宿主持久化细节，
 * 由宿主 PO 承载，引擎侧模型仅在 Store 供血接口与循环链路中流转。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

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
     * 所属的会话 ID
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
     * 消息展示正文
     */
    private String content;

    /**
     * 大模型推理思考过程内容
     */
    private String reasoningContent;

    /**
     * 绑定的工具调用详情（JSON-RPC 2.0 格式）
     */
    private String toolCalls;

    /**
     * 大模型生成的工具调用唯一标识 ID（仅 TOOL 角色行写入）。
     * 作为 callId 与数据库自增 id 之间的稳定关联键，替代原先的内存映射缓存。
     */
    private String callId;

    /**
     * 消息的当前运行/业务状态
     */
    private MessageStatus status;

    /**
     * 该消息是否对前端用户可见
     */
    @Builder.Default
    private Boolean visibleToUser = true;

    /**
     * 该消息在组装上下文历史时是否对大模型可见
     */
    @Builder.Default
    private Boolean visibleToModel = true;

    /**
     * 本次生成助手回复的输入 Token 消耗数
     */
    private Long inputTokens;

    /**
     * 本次生成助手回复的输出 Token 消耗数
     */
    private Long outputTokens;

    /**
     * 本次生成助手回复的提示词缓存命中 Token 数
     */
    private Long cachedTokens;

    /**
     * 本次模型生成的真实物理耗时 (单位：毫秒)
     */
    private Long executionDurationMs;

    /**
     * 消息关联的附件列表 JSON 数组（相对路径、文件名、大小、MIME类型等）
     */
    private String attachments;

}
