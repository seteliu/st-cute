package com.stioc.cute.hook.access;

import com.stioc.cute.agent.access.AgentContext;
import lombok.Builder;
import lombok.Data;

/**
 * 生命周期切面触发执行时的运行时数据环境
 */
@Data
@Builder
public class HookContext {

    /**
     * 当前会话唯一 ID
     */
    private Long cid;

    /**
     * 智能体运行上下文实例
     */
    private AgentContext agentContext;

    /**
     * 触发 Hook 时的工具调用 ID
     */
    private String toolCallId;

    /**
     * 触发 Hook 时的工具名称
     */
    private String toolName;

    /**
     * 被操作文件的绝对/相对物理路径
     */
    private String filePath;

    /**
     * 工具入参参数的 JSON 字符串
     */
    private String toolArgs;

    /**
     * 工具执行并生成的结果文本内容
     */
    private String toolResult;
}
