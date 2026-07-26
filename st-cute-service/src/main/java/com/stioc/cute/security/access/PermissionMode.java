package com.stioc.cute.security.access;

/**
 * 权限兜底矩阵模式
 */
public enum PermissionMode {
    /**
     * 只读模式: 只读工具直接放行(Allow)，写和命令需要确认(Ask)
     */
    READ_ONLY,

    /**
     * 智能审批: 只读与文件修改/写入放行(Allow)，安全只读命令放行(Allow)，其他终端命令需要确认(Ask)
     */
    SMART_APPROVAL,

    /**
     * 全部放行模式: 所有只读、写、命令都直接 Allow
     */
    ALL_ALLOW;

    public static PermissionMode fromName(String name) {
        if (name == null) {
            return READ_ONLY;
        }
        try {
            if ("COMMAND_APPROVAL".equalsIgnoreCase(name)) {
                return SMART_APPROVAL;
            }
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return READ_ONLY;
        }
    }
}
