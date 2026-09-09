package com.stioc.cute.engine.loop;

import com.stioc.cute.engine.loop.core.AgentContext;

/**
 * 引擎上下文装载扩展点（宿主实现）。
 * <p>
 * AgentContextManager 在上下文创建/恢复完成时回调全部 Initializer，
 * 宿主在此装载会话伴生数据（技能、规则、Hook、动态工具源等）。
 * 引擎不感知任何文件系统约定（AGENTS.md、skills 目录），装载细节全在宿主。
 * </p>
 */
public interface AgentContextInitializer {

    /**
     * 上下文创建/恢复完成时回调，宿主装载伴生数据与语义化扩展点。
     *
     * @param context 已完成运行时状态恢复的引擎上下文
     */
    void onContextRestored(AgentContext context);
}
