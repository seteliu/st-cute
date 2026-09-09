package com.stioc.cute.engine.facade;

import com.stioc.cute.engine.tool.ToolExecutionEngine;
import com.stioc.cute.engine.tool.ToolRegistry;
import com.stioc.cute.engine.tool.types.ToolApprovalRequest;
import lombok.RequiredArgsConstructor;

/**
 * 工具门面：工具注册中心访问与人在回路审批决策。
 */
@RequiredArgsConstructor
public class ToolFacade {

    private final ToolRegistry toolRegistry;
    private final ToolExecutionEngine toolExecutionEngine;

    /**
     * 获取工具注册中心（查工具/查清单）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * 工具审批人在回路决策（ALLOW 恢复执行 / 非 ALLOW 拒绝扣屏障）
     */
    public boolean approveTool(ToolApprovalRequest request) {
        return toolExecutionEngine.approveTool(request);
    }
}
