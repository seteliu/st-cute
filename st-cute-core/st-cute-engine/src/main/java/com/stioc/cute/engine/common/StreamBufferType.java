package com.stioc.cute.engine.common;

/**
 * 流式内容缓存桶类型标识。
 * <p>
 * 替代原先的 {@code boolean isReasoning} 布尔参数：布尔只能表达「两条流」，
 * 引入工具控制台日志流后语义不再够用，且调用点 {@code snapshotStreamText(false, ...)}
 * 之类的实参无法自解释。改用枚举后每条流各占一个桶，调用点语义自明。
 * </p>
 */
public enum StreamBufferType {

    /**
     * 大模型思考链（Reasoning）流：归属 ASSISTANT 消息
     */
    THINKING,

    /**
     * 大模型正文（Content）流：归属 ASSISTANT 消息
     */
    CONTENT,

    /**
     * 工具控制台增量日志流：归属 TOOL 消息（命令执行类工具的 stdout）
     */
    TOOL_LOG
}
