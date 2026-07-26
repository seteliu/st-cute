package com.stioc.cute.conversation.access;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.LocalDateTime;

/**
 * 对应 SQLite 数据库中 t_conversations 会话表，用于支持对话状态重启与复原
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "t_conversation")
public class ConversationEntity {

    /**
     * 会话主键 ID
     */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 关联的项目 ID
     */
    private Long projectId;

    /**
     * 选用的模型供应商分组名
     */
    private String providerGroup;

    /**
     * 选用的具体模型名称
     */
    private String providerModelName;

    /**
     * 权限兜底模式名称
     */
    private String permissionMode;

    /**
     * 父会话唯一 ID
     */
    private Long parentCid;

    /**
     * 最近一次 LLM 调用返回的输入 token 快照。
     */
    private Long inputTokens;

    /**
     * 最近一次 LLM 调用返回的输出 token 快照。
     */
    private Long outputTokens;

    /**
     * 最近一次 LLM 调用返回的缓存 token 快照。
     */
    private Long cachedTokens;

    /**
     * 当前轮次已发出的工具调用总数。
     * LLM 返回工具调用时写入 N；本轮 LLM 无工具调用（自然收尾）时清 0。
     * 配合 waitingToolIds 使用：waitingToolIds 非空说明本轮工具尚未跑完；
     * waitingToolIds 清空后自动触发下一轮 LLM。
     */
    private Integer callToolCount;

    /**
     * 本轮正在等待执行结果的工具 toolCallId 集合，以英文逗号分隔的字符串存储。
     * 每个工具（含 invoke_subagent 启动子 Agent 的调用本身）完成或审批通过执行完后从中移除。
     * 为空或 null 时，若 waitingSubCids 也为空，则触发下一轮 LLM 调用。
     * 进程重启后可据此恢复仍处于 WAITING_APPROVAL 状态的工具。
     */
    private String waitingToolIds;

    /**
     * 当前正在后台运行、尚未完成的子 Agent 会话 ID 集合，以英文逗号分隔的字符串存储。
     * invoke_subagent 拉起子 Agent 时写入其 subCid；子 Agent 完成并向父会话汇报后移除。
     * waitingSubCids 与 waitingToolIds 同时为空时，触发父 Agent 进行汇总轮 LLM 调用。
     */
    private String waitingSubCids;

    /**
     * 当前会话已解锁的按需暴露工具名集合，逗号分隔字符串。
     * 由 discover_tools 工具写入，影响 ToolRegistry 可见工具集，重启后必须恢复。
     */
    private String unlockedToolNames;

    /**
     * 当前 ReAct 循环已执行的总迭代轮次计数。
     * 每轮 LLM 调用开始前 +1；本轮 LLM 无工具调用（Loop 天然完结）时清 0。
     */
    private Integer iterationCount;

    /**
     * 当前会话绑定的物理隔离工作区绝对路径。
     * 由 enter_worktree 工具写入，exit_worktree 工具清除。
     * 工具执行时用于 cwd 重定向和写越界安全拦截，重启后必须恢复。
     */
    private String worktreePath;

    /**
     * 当前会话绑定的物理隔离工作区 Git 分支名。
     * 由 enter_worktree 工具写入，exit_worktree 工具清除。
     * 重启后 exit_worktree 需要凭此清理 git worktree，必须恢复。
     */
    private String worktreeBranch;

    /**
     * 当前 ReAct 循环是否正在运行中 (1是 0否)
     */
    private Integer loopRunning;

}
