package com.stioc.cute.llm;

import lombok.Builder;
import lombok.Data;

/**
 * 大模型调用 Token 消耗明细度量类
 */
@Data
@Builder
public class CuteUsage {

    /**
     * 输入 Prompt 消耗的 Token 数
     */
    private final Long inputTokens;

    /**
     * 输出 Completion 消耗的 Token 数
     */
    private final Long outputTokens;

    /**
     * 本地或大模型侧缓存命中的 Token 数
     */
    private final Long cachedTokens;
}
