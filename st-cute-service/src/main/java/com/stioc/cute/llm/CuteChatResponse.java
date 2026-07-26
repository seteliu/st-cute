package com.stioc.cute.llm;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 大模型调用响应实体类
 */
@Data
@Builder
public class CuteChatResponse {

    /**
     * 大模型输出文本内容
     */
    private final String content;

    /**
     * 大模型思考过程/推理内容
     */
    private final String reasoningContent;

    /**
     * 大模型请求的工具调用列表
     */
    private final List<CuteToolCall> toolCalls;

    /**
     * 本次调用的 Token 消耗度量信息
     */
    private final CuteUsage usage;

    /**
     * 本次大模型调用物理耗时 (单位：毫秒)
     */
    private final Long executionDurationMs;
}
