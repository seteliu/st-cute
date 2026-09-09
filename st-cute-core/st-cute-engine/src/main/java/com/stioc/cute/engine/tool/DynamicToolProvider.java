package com.stioc.cute.engine.tool;

import java.util.List;

/**
 * 引擎动态工具提供者接口（宿主实现）。
 * <p>
 * 语义化承载「外部工具源」概念：MCP 服务器、平台插件工具等宿主侧工具资产
 * 统一适配为本接口注入引擎。分两级：
 * <ul>
 *   <li>会话级：宿主在上下文装载时注入 AgentContext.dynamicToolProviders（MCP 项目级隔离等）</li>
 *   <li>全局级：宿主注册为 Spring Bean，由 ToolRegistry 统一融合（全局共享 MCP 等）</li>
 * </ul>
 * </p>
 */
public interface DynamicToolProvider {

    /**
     * 提供者名称（如 MCP server 名，用于隔离与去重）
     */
    String getName();

    /**
     * 是否处于可用运行态（仅运行中的提供者对外暴露工具）
     */
    boolean isRunning();

    /**
     * 当前暴露给大模型的工具清单
     */
    List<CuteTool> getExposedTools();
}
