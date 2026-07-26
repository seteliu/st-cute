package com.stioc.cute.llm;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 传递给大模型协议层使用的统一消息模型
 */
@Data
@Builder
public class CuteMessage {

    /**
     * 消息角色
     */
    private final CuteMessageRole role;

    /**
     * 消息文本内容
     */
    private final String content;

    /**
     * 大模型思考内容
     */
    private final String reasoningContent;
    
    /**
     * TOOL 专用：请求调用的唯一 ID
     */
    private final String toolCallId;

    /**
     * TOOL 专用：工具名称
     */
    private final String toolName;
    
    /**
     * ASSISTANT 专用：本次助手轮次发起的工具调用集合
     */
    private final List<CuteToolCall> toolCalls;
}
