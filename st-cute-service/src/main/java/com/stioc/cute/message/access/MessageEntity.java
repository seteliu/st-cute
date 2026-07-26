package com.stioc.cute.message.access;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.LocalDateTime;

/**
 * 对应 SQLite 数据库中 t_messages 消息记录表，记录每轮会话的正文、思考链以及工具调用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "t_message")
public class MessageEntity {

    /**
     * 消息主键 ID
     */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

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
     * 消息文本折叠/压缩前的原始数据备份
     */
    private String beforeCompactContent;

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

}

