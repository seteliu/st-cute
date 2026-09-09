package com.stioc.cute.engine.tool.types;

import org.apache.commons.lang3.StringUtils;

/**
 * 结构化工具权限裁决结果载荷。
 *
 * @param decision 裁决类型（ALLOW, ASK, DENY）
 * @param reason   拒绝或拦截时的详细原因描述（ALLOW/ASK 时通常为 null）
 */
public record ToolPermissionVerdict(ToolPermissionDecision decision, String reason) {

    public static ToolPermissionVerdict allow() {
        return new ToolPermissionVerdict(ToolPermissionDecision.ALLOW, null);
    }

    public static ToolPermissionVerdict ask() {
        return new ToolPermissionVerdict(ToolPermissionDecision.ASK, null);
    }

    public static ToolPermissionVerdict deny(String reason) {
        return new ToolPermissionVerdict(ToolPermissionDecision.DENY, reason);
    }

    public boolean isAllow() {
        return decision == ToolPermissionDecision.ALLOW;
    }

    public boolean isAsk() {
        return decision == ToolPermissionDecision.ASK;
    }

    public boolean isDeny() {
        return decision == ToolPermissionDecision.DENY;
    }

    public ToolPermissionDecision getDecision() {
        return decision;
    }

    public String getReason() {
        return reason;
    }

    /**
     * 从历史或配置契约字符串反向解析为强类型 ToolPermissionVerdict。
     *
     * @param raw 原始决策字符串（如 "ALLOW", "ASK", "DENY:原因"）
     * @return 强类型裁决结果
     */
    public static ToolPermissionVerdict fromRaw(String raw) {
        if (StringUtils.isBlank(raw)) {
            return ask();
        }
        String trimmed = raw.trim();
        if (ToolPermissionDecision.ALLOW.name().equalsIgnoreCase(trimmed)) {
            return allow();
        }
        if (ToolPermissionDecision.ASK.name().equalsIgnoreCase(trimmed)) {
            return ask();
        }
        if (trimmed.toUpperCase().startsWith(ToolPermissionDecision.DENY.name())) {
            String reason = null;
            int colonIndex = trimmed.indexOf(':');
            if (colonIndex != -1 && colonIndex + 1 < trimmed.length()) {
                reason = trimmed.substring(colonIndex + 1).trim();
            }
            return deny(StringUtils.isNotBlank(reason) ? reason : "操作受限");
        }
        return ask();
    }

    /**
     * 转换为对接历史或日志的标准裁决文本（"ALLOW", "ASK", "DENY:<原因>"）。
     *
     * @return 契约标准字符串
     */
    public String toContractString() {
        if (decision == ToolPermissionDecision.DENY) {
            return "DENY:" + (StringUtils.isNotBlank(reason) ? reason : "操作受限");
        }
        if (decision != null) {
            return decision.name();
        }
        return ToolPermissionDecision.ASK.name();
    }
}
