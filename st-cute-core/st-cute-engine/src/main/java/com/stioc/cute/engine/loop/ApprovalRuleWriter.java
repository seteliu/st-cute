package com.stioc.cute.engine.loop;

/**
 * 引擎审批授权持久化供血接口（宿主实现）。
 * <p>
 * 用户在人在回路审批中选择「总是放行」时的授权规则落盘属宿主安全域，
 * 引擎经本接口回调持久化，不感知规则的存储格式与位置。
 * </p>
 */
public interface ApprovalRuleWriter {

    /**
     * 向指定工作区本地配置写入一条持久化的放行授信规则。
     *
     * @param toolName       工具名
     * @param contentPattern 参数内容匹配模式
     * @param workspaceId    目标工作区标识（可为 null，表示写入全局）
     */
    void writeAllowRule(String toolName, String contentPattern, String workspaceId);
}
