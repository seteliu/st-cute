package com.stioc.cute.llm;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 大模型调用参数配置类
 */
@Data
@Builder
public class CuteChatOptions {

    /**
     * 大模型名称
     */
    private final String model;

    /**
     * 采样温度
     */
    private final Double temperature;

    /**
     * 最大单次输出 Token 数限制
     */
    private final Integer maxTokens;

    /**
     * 思考级别，例如 low, medium, high
     */
    private final String reasoningEffort;

    /**
     * 可用工具定义列表
     */
    private final List<CuteToolDefinition> tools;
}
