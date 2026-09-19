package com.stioc.cute.tool.contexttool;

import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.mcp.McpClientInstance;

import java.util.Map;

/**
 * 包装 MCP 暴露工具为内置规范工具 CuteTool，用以向上层 ReAct Loop 及权限沙箱无缝衔接
 * <p>
 * 本 Tool 无需加入 spring 容器中，是动态创建的
 * </p>
 */
public class McpCuteTool implements CuteTool {

    /**
     * 所属的 MCP 节点服务名称
     */
    private final String serverName;

    /**
     * MCP 节点原生工具名称
     */
    private final String originalToolName;

    /**
     * 工具功能描述信息
     */
    private final String description;

    /**
     * 工具输入参数 JSON Schema
     */
    private final String argumentSchema;

    /**
     * 所属的 MCP 进程客户端实例
     */
    private final McpClientInstance clientInstance;

    /**
     * MCP 工具的外部副作用访问等级（宿主在 MCP 管理侧声明，null 时按敏感级兜底）
     */
    private final ToolAccessLevel accessLevel;

    /**
     * 构造包装 MCP 工具的 CuteTool 实例（默认按敏感级治理，宁严勿漏）
     */
    public McpCuteTool(String serverName, String originalToolName, String description, String argumentSchema, McpClientInstance clientInstance) {
        this(serverName, originalToolName, description, argumentSchema, clientInstance, ToolAccessLevel.SENSITIVE);
    }

    /**
     * 构造包装 MCP 工具的 CuteTool 实例（显式声明访问等级，供宿主按 MCP 工具属性放行并发/审批策略）
     */
    public McpCuteTool(String serverName, String originalToolName, String description, String argumentSchema,
                       McpClientInstance clientInstance, ToolAccessLevel accessLevel) {
        this.serverName = serverName;
        this.originalToolName = originalToolName;
        this.description = description;
        this.argumentSchema = argumentSchema;
        this.clientInstance = clientInstance;
        this.accessLevel = accessLevel != null ? accessLevel : ToolAccessLevel.SENSITIVE;
    }

    @Override
    public ToolAccessLevel getAccessLevel() {
        return accessLevel;
    }

    @Override
    public String getDomain() {
        return "mcp__" + serverName;
    }

    @Override
    public String getRawName() {
        return originalToolName;
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
