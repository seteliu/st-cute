package com.stioc.cute.message.access;

import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.llm.CuteChatResponse;

/**
 * 基于对话消息单数据源的状态机状态流转驱动接口
 */
public interface MessageStateMachineService {

    /**
     * 将消息历史对齐到当前 ReAct 步骤，并定位生成活跃的助手消息 ID
     */
    Long alignForNextStep(AgentContext context);

    /**
     * 标记当前活跃的助手消息状态为生成成功 (SUCCESS)
     */
    void markAssistantSuccess(AgentContext context, CuteChatResponse response);

    /**
     * 标记助手发起工具调用行为 (WAITING_APPROVAL)
     */
    void markAssistantToolCalls(AgentContext context, CuteChatResponse response);

    /**
     * 标记本轮推理超出了设置的最大迭代次数上限 (FAILED)
     */
    void markIterationLimitExceeded(AgentContext context);

    /**
     * 标记推理循环执行期间被线程中断强制中止 (CANCELED)
     */
    void markInterrupted(AgentContext context, InterruptedException e);

    /**
     * 标记发生幻觉陷入无限错误调用被熔断强制阻断 (FAILED)
     */
    void markMeltdown(AgentContext context);

    /**
     * 标记执行中抛出运行时异常而失败 (FAILED)
     */
    void markException(AgentContext context, Exception e);

    /**
     * 标记指定的工具已被人在回路拒绝执行 (REJECTED)
     */
    void rejectTool(AgentContext context, MessageEntity toolMsg);
}
