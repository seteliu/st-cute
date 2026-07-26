package com.stioc.cute.hook.access;

import com.stioc.cute.agent.access.AgentContext;
import java.util.List;

/**
 * 智能体运行事件钩子（Hook）触发与引擎机制接口
 */
public interface HookEngineService {

    /**
     * 动态热装载指定项目工作区下的专属生命周期切面钩子规则
     */
    void loadProjectHooks(AgentContext context, String projectBasePath);

    /**
     * 获取指定会话环境下已加载生效的全部钩子挂钩规则
     */
    List<HookRule> getHookRules(AgentContext context);

    /**
     * 触发特定切面类型的钩子规则执行流程
     */
    void triggerHook(HookEventType event, HookContext context) throws Exception;
}
