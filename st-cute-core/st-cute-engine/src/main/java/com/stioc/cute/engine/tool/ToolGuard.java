package com.stioc.cute.engine.tool;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.types.ToolPermissionVerdict;

import java.util.Map;

/**
 * 引擎工具安全守卫接口（宿主实现）。
 * <p>
 * 将权限评估从引擎剥离：引擎的工具执行链在运行前询问宿主「允许吗」。
 * Coding Agent 宿主接权限矩阵，SaaS 宿主可换成沙箱策略。
 * </p>
 */
public interface ToolGuard {

    /**
     * 工具执行前的权限评估。
     *
     * @param tool    待评估的工具实例（引擎解析所得，恒非 null：未知工具在进入本守卫前已早失败拦截），
     *                工具名等标识信息由 {@link CuteTool#getName()} 自取，
     *                并含工具自声明的审计资源 {@link CuteTool#getTargetResource(Map)} 等元数据，
     *                宿主无需再按参数名猜测资源特征或反向反查注册中心
     * @param args    大模型传递的参数 Map
     * @param context 当前会话上下文
     * @return 强类型决策结果：ALLOW（放行）/ ASK（人在回路审批）/ DENY:原因（拒绝）
     */
    ToolPermissionVerdict evaluate(CuteTool tool, Map<String, Object> args, AgentContext context);
}
