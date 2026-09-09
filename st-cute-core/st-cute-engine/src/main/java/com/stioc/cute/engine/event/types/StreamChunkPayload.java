package com.stioc.cute.engine.event.types;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式增量切片事件公用载荷对象。
 * <p>
 * 供大模型思考流（AGENT_THINKING_STREAM）、正文流（AGENT_CONTENT_STREAM）
 * 以及工具控制台日志流（TOOL_LOG_STREAM）统一复用。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamChunkPayload {

    /**
     * 目标实体标识：助手消息流为 Long messageId，工具日志流为 String toolCallId
     */
    private Object id;

    /**
     * 流式增量文本内容
     */
    private String text;

    public StreamChunkPayload(Long messageId, String text) {
        this.id = messageId;
        this.text = text;
    }

    public StreamChunkPayload(String toolCallId, String text) {
        this.id = toolCallId;
        this.text = text;
    }

    /**
     * 获取解析后的消息 ID（用于助手流式处理）
     */
    public Long getMessageId() {
        if (id instanceof Number n) {
            return n.longValue();
        }
        if (id instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
