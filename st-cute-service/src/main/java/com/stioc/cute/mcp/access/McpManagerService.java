package com.stioc.cute.mcp.access;

import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.tool.access.CuteTool;
import java.util.List;

/**
 * MCP (Model Context Protocol) 管理接口，控制 MCP 服务的启动、挂载及状态维护
 */
public interface McpManagerService {

    void loadAndStartForContext(AgentContext context, String projectBasePath);

    void shutdownForContext(AgentContext context);

    List<CuteTool> getGlobalExposedTools();

    List<McpStatusVo> getMcpStatusList();

    List<McpStatusVo> getMcpStatusList(AgentContext context);

    void cleanup();
}
