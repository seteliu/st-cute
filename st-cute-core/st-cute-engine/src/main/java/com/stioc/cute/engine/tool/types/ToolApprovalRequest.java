package com.stioc.cute.engine.tool.types;

import lombok.Builder;

/**
 * 智能体工具人在回路（Human-in-the-Loop）审批决策请求载荷
 * <p>
 * 当大模型发起敏感工具调用（如终端命令、文件修改等）被权限门禁（{@code ToolGuard}）拦截并判定为需人工审批（{@code ASK}）时，
 * ReAct 循环会挂起进入待审批状态（{@code WAITING_APPROVAL}）。人工在前端做出裁决（放行/拒绝、是否总是放行、微调入参等）后，
 * 封装为本请求对象并提交给工具执行引擎（{@code ToolExecutionEngine}）进行消费与状态机推进。
 *
 * @param cid               会话唯一标识 (Conversation ID)，用于定位智能体运行时上下文及消息记录
 * @param toolCallId        待审批工具调用的唯一标识 (Tool Call ID)，对应工具执行等待屏障及工具消息关联键
 * @param decision          人工审批决策（如 "ALLOW" 放行、"DENY" 拒绝，大小写不敏感）
 * @param alwaysAllow       是否勾选“总是放行/信任此规则”。若为 true 且 decision 为 ALLOW，将触发授信规则写回工作区配置
 * @param toolName          待审批的工具名称（如 "run_command" 等），用于在总是放行时关联授信规则
 * @param contentPattern    规则匹配的内容模式/正则匹配式（可选，配合 alwaysAllow 写入自动化放行规则，如命令或路径模式）
 * @param customArgOverride 用户人工修改后的自定义入参覆盖 JSON 字符串（可选，若非空则优先替代大模型生成的原始入参执行）
 */
@Builder
public record ToolApprovalRequest(
        Long cid,
        String toolCallId,
        String decision,
        boolean alwaysAllow,
        String toolName,
        String contentPattern,
        String customArgOverride
) {}
