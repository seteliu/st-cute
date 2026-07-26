package com.stioc.cute.agent.event;

/**
 * 智能体运行周期内的核心写命令事件与流式事实事件类型。
 *
 * 事件层高度精简，由三层监听器同步/异步瀑布式链式消费：
 * 1. DIRECT (直接层，持久化写盘与内部控制命令) -> 2. CACHE (缓存同步层) -> 3. NOTIFICATION (通知推送层)
 */
public enum AgentEventType {

    // --------------------------------------------------
    // 1. 会话与消息的写命令 (Database Write Commands)
    // --------------------------------------------------

    /**
     * 创建新会话（含子会话拉起）
     */
    CONVERSATION_CREATE,

    /**
     * 更新会话属性（Token、解锁工具、Loop状态、权限模式、父子会话绑定等）
     */
    CONVERSATION_UPDATE,

    /**
     * 删除会话
     */
    CONVERSATION_DELETE,

    /**
     * 创建消息（用户提问、AI回复、工具消息）
     */
    MESSAGE_CREATE,

    /**
     * 更新消息内容/状态（AI生成结束、工具执行完毕、审批拒绝/等待中等）
     */
    MESSAGE_UPDATE,

    /**
     * 删除消息
     */
    MESSAGE_DELETE,

    // --------------------------------------------------
    // 2. 穿透/纯流式事实事件 (Pass-Through Stream Events) -> 第一、二层静默，直推 WS
    // --------------------------------------------------

    /**
     * 流式思考链（Reasoning）内容输出
     */
    AGENT_THINKING_STREAM,

    /**
     * 流式正文（Content）内容输出
     */
    AGENT_CONTENT_STREAM,

    /**
     * 工具执行过程中的控制台增量日志流
     */
    TOOL_LOG_STREAM;

}
