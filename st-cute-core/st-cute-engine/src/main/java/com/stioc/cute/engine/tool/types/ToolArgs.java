package com.stioc.cute.engine.tool.types;

import java.util.Map;

/**
 * 工具入参安全访问帮助类。
 * <p>
 * 消除各工具手写 Map 强转：{@code (String) arguments.get("path")}、
 * {@code ((Number) arguments.get("startLine")).intValue()} 等写法在模型传参类型偏差时
 * （如整数传成字符串 "5"）会直接抛出 ClassCastException 导致工具崩溃，
 * 本类内置宽松类型矫正，类型完全非法时统一按缺失（null）处理，
 * 由工具自身的必填/取值校验给出可行动的参数错误，而非引擎层模糊崩溃。
 * </p>
 */
public final class ToolArgs {

    /**
     * 原始参数 Map（构造时保证非 null）
     */
    private final Map<String, Object> raw;

    private ToolArgs(Map<String, Object> raw) {
        this.raw = raw;
    }

    /**
     * 包装原始参数 Map（null 安全，空 Map 兜底）
     */
    public static ToolArgs of(Map<String, Object> arguments) {
        return new ToolArgs(arguments != null ? arguments : Map.of());
    }

    /**
     * 读取字符串参数（保持原文不裁剪，适用于文件内容等空白敏感参数）。
     * 数字/布尔形态自动转字符串；对象/数组等非法形态按缺失返回 null
     */
    public String getString(String key) {
        Object v = raw.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return null;
    }

    /**
     * 读取字符串参数，缺失或空白时返回默认值
     */
    public String getString(String key, String defaultValue) {
        String v = getString(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    /**
     * 读取字符串参数并裁剪首尾空白（适用于路径、编码名、格式模板等非空白敏感参数）
     */
    public String getStringTrimmed(String key) {
        String v = getString(key);
        return v != null ? v.trim() : null;
    }

    /**
     * 读取字符串参数并裁剪首尾空白，缺失或空白时返回默认值
     */
    public String getStringTrimmed(String key, String defaultValue) {
        String v = getStringTrimmed(key);
        return (v == null || v.isEmpty()) ? defaultValue : v;
    }

    /**
     * 读取整型参数：Number 自动窄化（小数截断），整数字符串自动解析；
     * 缺失或非法返回 null
     */
    public Integer getInt(String key) {
        Number n = parseNumber(key);
        return n != null ? n.intValue() : null;
    }

    /**
     * 读取整型参数，缺失或非法时返回默认值
     */
    public Integer getInt(String key, Integer defaultValue) {
        Integer v = getInt(key);
        return v != null ? v : defaultValue;
    }

    /**
     * 读取长整型参数：Number 自动取值，整数字符串自动解析；缺失或非法返回 null
     */
    public Long getLong(String key) {
        return parseNumber(key);
    }

    /**
     * 读取长整型参数，缺失或非法时返回默认值
     */
    public Long getLong(String key, Long defaultValue) {
        Long v = getLong(key);
        return v != null ? v : defaultValue;
    }

    /**
     * 读取布尔参数：Boolean 直取，true/false 字符串（忽略大小写）自动解析；
     * 缺失或非法返回 null
     */
    public Boolean getBoolean(String key) {
        Object v = raw.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            String t = s.trim();
            if ("true".equalsIgnoreCase(t)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(t)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /**
     * 数字解析内核：Number 直取，整数字符串解析，浮点数字符串截断兜底，其余返回 null
     */
    private Long parseNumber(String key) {
        Object v = raw.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            String trimmed = s.trim();
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException e) {
                // 宽容兜底：兼容部分 LLM 输出浮点数字符串（如 "1.0"）
                try {
                    return (long) Double.parseDouble(trimmed);
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
        }
        return null;
    }
}
