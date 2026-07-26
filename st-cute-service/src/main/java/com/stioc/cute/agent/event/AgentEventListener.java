package com.stioc.cute.agent.event;

/**
 * 智能体运行事件监听器接口
 */
public interface AgentEventListener {
    /**
     * 响应处理智能体生命周期或业务状态变更事件
     */
    void onEvent(AgentEvent event);

    /**
     * 获取当前监听器的触发执行阶梯级别
     */
    default ListenerTier getTier() {
        return ListenerTier.DIRECT;
    }
}
