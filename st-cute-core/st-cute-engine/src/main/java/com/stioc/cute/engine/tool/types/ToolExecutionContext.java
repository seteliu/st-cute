package com.stioc.cute.engine.tool.types;

import com.stioc.cute.engine.loop.core.AgentContext;
import lombok.Getter;
import lombok.Setter;

/**
 * 显式工具执行上下文
 */
@Getter
public class ToolExecutionContext {

    private final AgentContext agentContext;
    private final String toolCallId;

    /**
     * 工具产生并需挂载至消息的附件元数据（如存盘的 JSON 字符串）
     */
    @Setter
    private String attachments;

    public ToolExecutionContext(AgentContext agentContext, String toolCallId) {
        this.agentContext = agentContext;
        this.toolCallId = toolCallId;
    }

    public AgentContext agentContext() {
        return agentContext;
    }

    public String toolCallId() {
        return toolCallId;
    }
}
