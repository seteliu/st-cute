package com.stioc.cute.permission.types;

/**
 * 权限兜底矩阵模式
 */
public enum PermissionMode {
    /**
     * 只读模式: 只读工具直接放行(Allow)，写、敏感工具与命令需要确认(Ask)
     */
    READ_ONLY,

    /**
     * 智能审批: 只读与文件修改/写入放行(Allow)，安全只读命令放行(Allow)，
     * 敏感级工具（如删除文件、任意终端命令）不放行需确认(Ask)
     */
    SMART_APPROVAL,

    /**
     * 全部放行模式: 所有只读、写、命令都直接 Allow
     */
    ALL_ALLOW;

    static {
        // 三档模式与 ToolAccessLevel 的放行矩阵说明（与 PermissionService#getToolCategory 联动）：
        // READ      -> 全模式 Allow
        // WRITE     -> READ_ONLY 下 Ask，SMART_APPROVAL 下 Allow（已读白名单可快速放行），ALL_ALLOW 下 Allow
        // SENSITIVE -> READ_ONLY / SMART_APPROVAL 下均 Ask（智能审批不放行），ALL_ALLOW 下 Allow
    }

    public static PermissionMode fromName(String name) {
        if (name == null) {
            return READ_ONLY;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return READ_ONLY;
        }
    }
}
