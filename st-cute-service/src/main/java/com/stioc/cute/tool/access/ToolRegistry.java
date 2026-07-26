package com.stioc.cute.tool.access;

import com.stioc.cute.agent.access.AgentContext;

import java.util.List;

/**
 * 智能体工具注册中心，提供内置工具与 MCP 动态工具的发现和匹配能力。
 */
public interface ToolRegistry {

    /**
     * 获取当前 AgentContext 可用的工具列表。
     */
    List<CuteTool> getAllTools(AgentContext context);

    /**
     * 根据工具协议名查找工具，忽略大小写。
     */
    CuteTool getTool(String name, AgentContext context);
}
