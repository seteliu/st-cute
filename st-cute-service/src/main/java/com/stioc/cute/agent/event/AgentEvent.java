package com.stioc.cute.agent.event;

import com.stioc.cute.agent.access.AgentContext;
import lombok.Builder;
import lombok.Data;
import java.util.Objects;

/**
 * 智能体运行周期内的核心业务事件对象
 */
@Data
@Builder
public class AgentEvent {
    /**
     * 智能体运行上下文实例
     */
    private final AgentContext agentContext;

    /**
     * 事件发生时间戳
     */
    private long timestamp;

    /**
     * 事件类型
     */
    private final AgentEventType type;

    /**
     * 绑定的事件消息载荷对象
     */
    private final Object payload;

    /**
     * 构造智能体业务事件实例
     */
    public AgentEvent(AgentContext agentContext, long timestamp, AgentEventType type, Object payload) {
        this.agentContext = Objects.requireNonNull(agentContext, "AgentContext 必须存在");
        this.timestamp = timestamp;
        this.type = type;
        this.payload = payload;
    }
}
