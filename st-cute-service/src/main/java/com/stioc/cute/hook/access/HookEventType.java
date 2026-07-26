package com.stioc.cute.hook.access;

/**
 * 智能体生命周期切面 Hook 事件类型枚举
 */
public enum HookEventType {
    /**
     * 会话环境启动初始化切面
     */
    ON_CONTEXT_START,

    /**
     * 智能体单次推理开始切面
     */
    ON_LOOP_START,

    /**
     * 智能体发起工具调用时（尚未执行）切面
     */
    ON_TOOL_CALL,

    /**
     * 本地/MCP 工具调用物理执行完毕切面
     */
    ON_TOOL_COMPLETE,

    /**
     * 智能体单次推理循环完结（AI 收尾不再调用工具）切面
     */
    ON_LOOP_END
}
