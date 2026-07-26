package com.stioc.cute.tool.access;

import com.stioc.cute.agent.access.AgentContext;

/**
 * 显式工具执行上下文
 *
 * @param agentContext 智能体运行循环控制上下文
 * @param toolCallId       当前工具调用的唯一 ID
 */
public record ToolExecutionContext(
    AgentContext agentContext,
    String toolCallId
) {}
