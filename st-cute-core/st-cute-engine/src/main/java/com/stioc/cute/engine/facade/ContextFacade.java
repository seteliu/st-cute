package com.stioc.cute.engine.facade;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.loop.core.AgentContextManager;
import lombok.RequiredArgsConstructor;

import java.util.Collection;

/**
 * 会话上下文门面：AgentContext 生命周期管理能力。
 * <p>获取/恢复/移除/热重载会话运行上下文。</p>
 */
@RequiredArgsConstructor
public class ContextFacade {

    private final AgentContextManager contextManager;

    /**
     * 获取或创建指定会话的运行上下文（含运行时状态恢复与宿主装载回调）
     */
    public AgentContext getOrCreateContext(Long cid) {
        return contextManager.getOrCreateContext(cid);
    }

    /**
     * 获取指定会话的当前活动内存上下文（不存在返回 null）
     */
    public AgentContext getActiveContext(Long cid) {
        return contextManager.getActiveContext(cid);
    }

    /**
     * 获取内存中全部活跃上下文快照
     */
    public Collection<AgentContext> getAllContexts() {
        return contextManager.getAllContexts();
    }

    /**
     * 移除会话运行上下文（级联子会话与物理副作用清理）
     */
    public void removeContext(Long cid) {
        contextManager.removeContext(cid);
    }

    /**
     * 热重载会话工作区专属资产（转发宿主装载扩展点）
     */
    public void reloadContextAssets(Long cid) {
        AgentContext context = contextManager.getOrCreateContext(cid);
        if (context != null) {
            contextManager.loadContextAssets(context);
        }
    }
}
