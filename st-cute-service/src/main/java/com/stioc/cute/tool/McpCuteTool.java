package com.stioc.cute.tool;

import com.stioc.cute.mcp.access.McpClientInstance;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolExecutionContext;
import java.util.Map;

/**
 * 包装 MCP 暴露工具为内置规范工具 CuteTool，用以向上层 ReAct Loop 及权限沙箱无缝衔接
 *
 * 本 Tool 无需加入 spring 容器中，是动态创建的
 */
public class McpCuteTool implements CuteTool {

    /**
     * 对接本系统的统一工具名称
     */
    private final String name;

    /**
     * 工具功能描述信息
     */
    private final String description;

    /**
     * 工具输入参数 JSON Schema
     */
    private final String argumentSchema;

    /**
     * MCP 节点原生工具名称
     */
    private final String originalToolName;

    /**
     * 所属的 MCP 进程客户端实例
     */
    private final McpClientInstance clientInstance;

    /**
     * 构造包装 MCP 工具的 CuteTool 实例
     */
    public McpCuteTool(String name, String description, String argumentSchema, String originalToolName, McpClientInstance clientInstance) {
        this.name = name;
        this.description = description;
        this.argumentSchema = argumentSchema;
        this.originalToolName = originalToolName;
        this.clientInstance = clientInstance;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getArgumentSchema() {
        return argumentSchema;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        // 委托底层的 McpClientInstance 进程通道调用对应的工具
        return clientInstance.executeTool(originalToolName, arguments);
    }
}
