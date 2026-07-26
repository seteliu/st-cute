package com.stioc.cute.llm;

/**
 * 大模型标准消息角色枚举
 */
public enum CuteMessageRole {

    /**
     * 系统提示角色
     */
    SYSTEM,

    /**
     * 用户角色
     */
    USER,

    /**
     * 助手角色
     */
    ASSISTANT,

    /**
     * 工具输出结果角色
     */
    TOOL
}
