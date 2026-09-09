package com.stioc.cute.message;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息持久化实体（宿主侧，绑定 SQLite t_message 表）。
 * <p>引擎模型 {@link Message} 为纯 POJO，本类承担表结构映射，
 * 转换逻辑收口在 {@code MessageStoreImpl}。</p>
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
     * 大模型生成的工具调用唯一标识 ID（仅 TOOL 角色行写入）
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
     * 消息关联的附件列表 JSON 数组
     */
    private String attachments;
}
