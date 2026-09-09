package com.stioc.cute.engine.tool.types;

/**
 * 智能体工具执行权限决策类型枚举。
 */
public enum ToolPermissionDecision {

    /**
     * 放行，允许执行
     */
    ALLOW,

    /**
     * 需要人在回路人工审批
     */
    ASK,

    /**
     * 拦截拒绝
     */
    DENY
}
