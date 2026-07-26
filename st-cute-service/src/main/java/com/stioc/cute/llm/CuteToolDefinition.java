package com.stioc.cute.llm;

import lombok.Builder;
import lombok.Data;

/**
 * 声明给大模型可见的工具定义实体类
 */
@Data
@Builder
public class CuteToolDefinition {

    /**
     * 工具名称
     */
    private final String name;

    /**
     * 工具功能描述描述信息
     */
    private final String description;

    /**
     * 工具入参 JSON Schema 描述结构
     */
    private final String inputSchema;
}
