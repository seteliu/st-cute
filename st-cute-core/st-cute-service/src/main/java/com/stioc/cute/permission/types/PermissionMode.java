package com.stioc.cute.permission.types;

/**
 * 权限兜底矩阵模式
 */
public enum PermissionMode {
    /**
     * 严格审批模式: 仅开放只读工具直接放行(Allow)，写、敏感工具与命令需要确认(Ask)
     */
    STRICT_APPROVAL,

    /**
     * 宽松审批: 只读与文件修改/写入放行(Allow)，安全只读命令放行(Allow)，
     * 敏感级工具（如删除文件、任意终端命令）不放行需确认(Ask)
     */
    RELAXED_APPROVAL,

    /**
     * 全部放行模式: 所有只读、写、命令都直接 Allow
     */
    ALL_ALLOW;

    static {
        // 三档模式与 ToolAccessLevel 的放行矩阵说明（与 PermissionService#getToolCategory 联动）：
        // READ      -> 全模式 Allow
        // WRITE     -> STRICT_APPROVAL 下 Ask，RELAXED_APPROVAL 下 Allow（已读白名单可快速放行），ALL_ALLOW 下 Allow
        // SENSITIVE -> STRICT_APPROVAL / RELAXED_APPROVAL 下均 Ask（宽松审批不放行敏感级），ALL_ALLOW 下 Allow
        // 存量兼容：历史值 READ_ONLY / ASK_APPROVAL / SMART_APPROVAL 均不再是合法枚举名，
        // fromName 匹配不到时统一安全兜底为 STRICT_APPROVAL
        // （其中存量智能审批会话的写操作将由放行变为需审批，属已接受的行为变更，不做历史值映射以免长期包袱）
    }

    public static PermissionMode fromName(String name) {
        if (name == null) {
            return STRICT_APPROVAL;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return STRICT_APPROVAL;
        }
    }
}
