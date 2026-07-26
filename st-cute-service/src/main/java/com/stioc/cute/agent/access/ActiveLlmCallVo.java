package com.stioc.cute.agent.access;

import lombok.Builder;
import lombok.Data;

/**
 * 活跃大模型 HTTP 请求信息视图实体 VO (用于返回前端展示)
 */
@Data
@Builder
public class ActiveLlmCallVo {

    /**
     * 所属会话 ID
     */
    private Long cid;

    /**
     * 会话标题 / 子智能体角色名
     */
    private String sessionTitle;

    /**
     * 大模型调用的唯一标识 ID
     */
    private String llmCallId;

    /**
     * 调用的模型名称 (如 claude-3-5-sonnet, gpt-4o)
     */
    private String model;

    /**
     * 请求发起的时间戳（毫秒）
     */
    private Long startTime;

    /**
     * 已经耗时时长（毫秒）
     */
    private Long durationTimeMs;
}
