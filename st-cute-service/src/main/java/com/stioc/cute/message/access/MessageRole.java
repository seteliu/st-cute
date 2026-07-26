package com.stioc.cute.message.access;

/**
 * 消息角色枚举
 */
public enum MessageRole {
    /**
     * 用户消息
     */
    USER,

    /**
     * 大模型助手消息
     */
    ASSISTANT,

    /**
     * 系统提示消息
     */
    SYSTEM,

    /**
     * 工具调用响应消息
     */
    TOOL,

    /**
     * 来自子 Agent 的工作汇报消息。
     * 持久化在父会话的消息表中，前端单独样式展示。
     * 组装历史发给大模型时以 USER 角色发送，内容前拼接"来自其他Agent：\n"前缀。
     */
    BRANCH,

    /**
     * 内部上下文压缩消息。
     * 改为前端也可见，大模型可见。
     * 发起调用的时候，对于大模型等同于USER类型的消息。
     */
    COMPRESSED
}
