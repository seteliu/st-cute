package com.stioc.cute.agent.access;

import java.util.Collection;

/**
 * 智能体运行上下文管理接口，负责管理内存中所有活动会话的 AgentContext 状态
 */
public interface AgentContextManager {

    /**
     * 获取指定会话 ID 的智能体上下文环境，若不存在则自动加载与初始化
     */
    AgentContext getOrCreateContext(Long cid);

    /**
     * 获取指定会话 ID 的当前活动内存上下文，若不存在则返回 null
     */
    AgentContext getActiveContext(Long cid);

    /**
     * 动态加载会话关联的项目资产（如 Skill、Hook 规则及 MCP 客户端等）至上下文
     */
    void loadContextAssets(AgentContext context, String projectBasePath);

    /**
     * 强行取消并打断指定会话 ID 的当前 ReAct 运行循环进程
     */
    void cancelContext(Long cid);

    /**
     * 从内存中彻底销毁并清除指定会话 ID 的上下文及资源
     */
    void removeContext(Long cid);

    /**
     * 获取当前在内存中注册的全部智能体上下文环境集合
     */
    Collection<AgentContext> getAllContexts();
}
