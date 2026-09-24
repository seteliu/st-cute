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
     * 系统界面主题设置（如 dark 或 light），默认为 dark
     */
    private String theme;

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
     * 是否记录响应部分（含 SSE 流式响应全文）：关闭后仅记录请求报文与异常，默认开启
     */
    private Boolean httpLogIncludeResponse;

    /**
     * 安全访问密码（SHA-256(原文) 传输摘要）。
     * <p>保存时空/缺省表示不修改密码，非空为前端计算的传输摘要（服务端加盐后落盘）；
     * 查询接口不回传该字段，密码状态经 {@link #passwordSet} 表达。</p>
     */
    private String password;

    /**
     * 是否已设置安全访问密码（查询接口回传的密码状态标记，替代明文回显）
     */
    private Boolean passwordSet;

    /**
     * password 字段传输摘要对应的原文长度。
     * <p>原文不落库不回传，仅在设置新密码时随摘要附带，供服务端兜底校验访问码复杂度策略
     * （长度区间见 {@link PasswordPolicy}）；字段可缺省，缺省按不合法处理。</p>
     */
    private Integer passwordLength;

    /**
     * 显式清除密码标记：true 时清除已设置的访问密码（优先级高于 password 字段）。
     * <p>因 password 字段"空=不修改"的语义无法表达关闭密码保护，需经本标记显式声明。</p>
     */
    private Boolean passwordClear;

    /**
     * 会话历史消息显示数量限制
     */
    private Integer maxViewHistoryLimit;

    /**
     * 是否开启路径沙箱保护，默认开启
     */
    private Boolean pathSandboxEnabled;

    /**
     * 是否开启极简 Skill 模式——技能清单不注入系统提示词以节省 Token，仅按需触发加载，默认关闭
     */
    private Boolean minimalSkillMode;

    /**
     * 是否加载全部用户消息的附件：开启后历史重建时所有用户消息的附件均完整装载物理数据，
     * 关闭时仅最后一条用户消息完整装载、其余以轻量占位符替代，默认开启
     */
    private Boolean loadAllUserAttachments;
}
