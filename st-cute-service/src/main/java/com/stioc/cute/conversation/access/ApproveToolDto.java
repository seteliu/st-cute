package com.stioc.cute.conversation.access;

import lombok.Data;

/**
 * 工具人在回路审批决策 DTO 实体类
 */
@Data
public class ApproveToolDto {

    /**
     * 被审批的工具调用唯一 ID
     */
    private String id;

    /**
     * 审批决定决策（ALLOW 为通过，REJECT 为拒绝）
     */
    private String decision;

    /**
     * 是否对于该工具及匹配规则总是放行不挂起
     */
    private Boolean alwaysAllow;

    /**
     * 被审批的工具名称
     */
    private String toolName;

    /**
     * 内容匹配正则规则字符串
     */
    private String contentPattern;

    /**
     * 用户自定义微调覆盖后的参数 JSON 字符串
     */
    private String customArgOverride;
}
