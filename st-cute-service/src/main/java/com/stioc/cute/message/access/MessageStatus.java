package com.stioc.cute.message.access;

/**
 * 消息状态枚举
 */
public enum MessageStatus {
    /**
     * 待审批（仅限 TOOL 消息）
     */
    WAITING_APPROVAL,

    /**
     * 权限拒绝（仅限 TOOL 消息）
     */
    REJECTED,

    /**
     * 待执行
     */
    PENDING,

    /**
     * 实际执行中
     */
    RUNNING,

    /**
     * 执行成功
     */
    SUCCESS,

    /**
     * 执行失败
     */
    FAILED,

    /**
     * 手动取消
     */
    CANCELED
}
