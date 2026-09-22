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
     * 目标消息 ID：思考流/正文流为 ASSISTANT 消息 ID，工具日志流为 TOOL 消息 ID。
     * <p>
     * 工具日志流历史上承载 toolCallId 字符串，与助手流模型不统一，前端需两套定位逻辑；
     * 现统一为消息 ID，三条流共用同一套「按消息 ID 归属」模型。
     * </p>
     */
    private Long id;

    /**
     * 流式增量文本内容
     */
    private String text;

    /**
     * 切片语义类型：{@code null}（默认）表示常规增量内容；非空时为流控制信号（如清空重放）。
     * <p>
     * 为 {@code null} 时不参与序列化（宿主外推未启用 WriteNulls），常规 chunk 报文与引入前一致。
     * </p>
     */
    private StreamChunkType type;

    public StreamChunkPayload(Long messageId, String text) {
        this.id = messageId;
        this.text = text;
    }

    /**
     * 构造一个「清空」控制信号载荷（无文本）
     *
     * @param messageId 目标消息 ID
     */
    public static StreamChunkPayload clear(Long messageId) {
        StreamChunkPayload payload = new StreamChunkPayload(messageId, null);
        payload.type = StreamChunkType.CLEAR;
        return payload;
    }

    /**
     * 是否为「清空」控制信号
     */
    public boolean isClear() {
        return type == StreamChunkType.CLEAR;
    }
}
