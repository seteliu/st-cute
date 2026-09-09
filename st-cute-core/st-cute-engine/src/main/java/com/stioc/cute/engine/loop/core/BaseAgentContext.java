package com.stioc.cute.engine.loop.core;

/**
 * 智能体运行上下文基础契约。
 * <p>
 * 定义通用的上下文清理与生命周期回收行为。
 * {@link AgentContext} 与各类伴生扩展上下文（如宿主 {@code RuntimeContext}）均实现此契约。
 * </p>
 */
public interface BaseAgentContext {

    /**
     * 清理当前上下文关联的活跃副作用与物理资源。
     * 默认空实现，子类按需重写资源释放与状态重置逻辑（如强退网络连接、强杀物理进程等）。
     */
    default void clear() {
    }
}
