package com.stioc.cute.engine.testkit;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.ToolGuard;
import com.stioc.cute.engine.tool.types.ToolPermissionVerdict;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * 可编程的工具安全守卫替身。
 * <p>
 * 引擎把人在回路审批的判定权完全下放宿主（{@link ToolGuard}），故闭环测试需要能任意编排
 * 「放行 / 待审批 / 拒绝」三种裁决，才能覆盖审批挂起与审批恢复两条关键路径。
 * 裁决策略可在运行期整体替换（volatile），审批恢复用例正是借此在挂起后改为放行。
 * </p>
 */
public class ScriptedToolGuard implements ToolGuard {

    /**
     * 默认策略：一律放行（不阻断任何工具，便于聚焦循环与并发语义）
     */
    private volatile BiFunction<CuteTool, Map<String, Object>, ToolPermissionVerdict> policy =
            (tool, args) -> ToolPermissionVerdict.allow();

    /**
     * 评估次数计数（供断言守卫被真实调用）
     */
    private final AtomicInteger evaluateCount = new AtomicInteger();

    /**
     * 设置裁决策略（工具实例 + 参数 → 裁决）
     */
    public ScriptedToolGuard policy(BiFunction<CuteTool, Map<String, Object>, ToolPermissionVerdict> policy) {
        this.policy = policy != null ? policy : (tool, args) -> ToolPermissionVerdict.allow();
        return this;
    }

    /**
     * 设置固定裁决（所有工具统一返回同一结果）
     */
    public ScriptedToolGuard alwaysReturn(ToolPermissionVerdict verdict) {
        return policy((tool, args) -> verdict);
    }

    @Override
    public ToolPermissionVerdict evaluate(CuteTool tool, Map<String, Object> args, AgentContext context) {
        evaluateCount.incrementAndGet();
        return policy.apply(tool, args);
    }

    /**
     * 读取累计评估次数
     */
    public int getEvaluateCount() {
        return evaluateCount.get();
    }
}
