package com.stioc.cute.engine.store.types;

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
    COMPRESSED,

    /**
     * 折叠虚拟消息角色（不落库、大模型不可见）。
     * 仅在消息列表查询接口（/api/message/list?folded=true）出口处按折叠算法聚合生成，
     * 用于替代被折叠的多条助手/工具消息明细，代表 [foldedMinId, foldedMaxId] 闭区间内的历史步骤。
     * 严禁进入上下文组装、状态机等任何写链路。
     */
    FOLDED
}
