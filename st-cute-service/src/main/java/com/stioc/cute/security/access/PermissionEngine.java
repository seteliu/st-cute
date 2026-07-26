package com.stioc.cute.security.access;

import com.stioc.cute.agent.access.AgentContext;
import java.util.Map;

/**
 * 智能体工具执行权限决策引擎接口
 */
public interface PermissionEngine {

    /**
     * 评估指定工具调用的权限，作出 ALLOW (允许)、DENY (拒绝) 或 ASK (询问) 决策
     */
    String evaluate(String toolName, Map<String, Object> arguments, AgentContext context);

    /**
     * 向全局用户配置写入一条持久化的权限授信规则
     */
    void writeLocalRule(PermissionRule rule);

    /**
     * 向指定项目工作区本地配置写入一条持久化的权限授信规则
     */
    void writeLocalRule(PermissionRule rule, String projectBasePath);
}
