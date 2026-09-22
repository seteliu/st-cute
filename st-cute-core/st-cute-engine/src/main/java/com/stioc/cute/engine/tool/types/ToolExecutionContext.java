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
     * 本次工具调用对应的 TOOL 消息 ID（工具日志流按消息 ID 归属所需的标识）。
     * <p>
     * 由引擎在构造执行上下文时查库解析；解析不到时为 null，此时不推送工具日志流
     * （消息尚未落库的窗口期，日志也无处归属）。
     * </p>
     */
    private final Long messageId;

    /**
     * 工具产生并需挂载至消息的附件元数据（如存盘的 JSON 字符串）
     */
    @Setter
    private String attachments;

    /**
     * 兼容构造器：仅携带 toolCallId（messageId 为 null，不推送工具日志流）
     */
    public ToolExecutionContext(AgentContext agentContext, String toolCallId) {
        this(agentContext, toolCallId, null);
    }

    public ToolExecutionContext(AgentContext agentContext, String toolCallId, Long messageId) {
        this.agentContext = agentContext;
        this.toolCallId = toolCallId;
        this.messageId = messageId;
    }

    public AgentContext agentContext() {
        return agentContext;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public Long messageId() {
        return messageId;
    }
}
