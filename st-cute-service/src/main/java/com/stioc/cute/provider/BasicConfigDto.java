package com.stioc.cute.provider;

import lombok.Data;

/**
 * 客户端请求获取或更新系统基础配置 DTO
 */
@Data
public class BasicConfigDto {

    /**
     * 系统语言设置（如 zh-CN 或 en-US）
     */
    private String language;

    /**
     * 发送消息的触发换行按键名（如 enter 或 ctrl+enter）
     */
    private String newlineKey;

    /**
     * 是否开启原始大模型调用 HTTP 日志记录
     */
    private Boolean httpLog;

    /**
     * HTTP 原始 Payload 物理日志的最长留存天数
     */
    private Integer httpLogDays;

    /**
     * 安全访问密码
     */
    private String password;

    /**
     * 是否开启消息聚合展示
     */
    private Boolean messageAggregation;

    /**
     * 会话历史消息显示数量限制
     */
    private Integer maxViewHistoryLimit;

    /**
     * 是否开启路径沙箱保护，默认开启
     */
    private Boolean pathSandboxEnabled;
}
