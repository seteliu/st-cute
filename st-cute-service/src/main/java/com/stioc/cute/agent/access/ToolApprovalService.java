package com.stioc.cute.agent.access;

/**
 * 工具审批人在回路决策服务接口
 */
public interface ToolApprovalService {

    /**
     * 审核工具执行决策并分流执行/拒绝流向
     */
    boolean approveTool(Long cid, String toolCallId, String decision, boolean alwaysAllow,
                        String toolName, String contentPattern, String customArgOverride);
}
