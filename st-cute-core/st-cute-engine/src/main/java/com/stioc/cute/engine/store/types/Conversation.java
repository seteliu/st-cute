package com.stioc.cute.engine.store.types;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话模型（引擎纯 POJO，零持久化绑定）。
 * <p>
 * 表结构映射（@Table/@Id 等）与物理落库是宿主持久化细节，由宿主 PO 承载，
 * 引擎侧模型仅在 Store 供血接口与循环链路中流转。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {

    /**
     * 会话主键 ID
     */
    private Long id;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 工作区标识（解析 workspace 的 key，引擎不消费语义，纯透传）。
     * Coding 宿主存项目 ID 字符串，SaaS 宿主可存租户 ID，由宿主 ProjectService 等业务层解释。
     */
    private String workspaceId;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 选用的模型供应商分组名
     */
    private String providerGroup;

    /**
     * 选用的具体模型名称
     */
    private String providerModelName;

    /**
     * 权限兜底模式名称（字符串化，语义由宿主定义）
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
     * 当前会话循环轮次（第几轮）。
     * 用户发消息时置 1（主动发起新一轮）；每轮工具全部完成后由唯一合法触发者在 cid 锁内
     * CAS 消费（observed == current 时 set(current+1)）并拉起下一轮循环。
     * 本字段同时充当一次性触发令牌：重复/迟到的完成回调因 observed 与 current 不等而被拒绝。
     * 存库仅用于监控展示，历史数据无迁移价值，重启后自然归零重开。
     */
    private Integer loopCount;

    /**
     * 当前 ReAct 循环是否正在运行中 (1是 0否)
     */
    private Integer loopRunning;

}
