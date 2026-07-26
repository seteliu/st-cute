package com.stioc.cute.llm;

import lombok.Builder;
import lombok.Data;

/**
 * 大模型发起的单个工具调用指令数据类
 */
@Data
@Builder
public class CuteToolCall {

    /**
     * 工具调用的唯一 ID
     */
    private final String id;

    /**
     * 工具名称
     */
    private final String name;

    /**
     * 参数 JSON 格式字符串
     */
    private final String arguments;
}
