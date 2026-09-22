package com.stioc.cute.engine.common;

import com.alibaba.fastjson2.*;

import java.util.List;

/**
 * 全系统 JSON 操作统一收敛门面。
 * <p>
 * JSON 解析与序列化出口必须经由本类，JSONObject / JSONArray 树对象的构建与读取不受限。
 * </p>
 */
public final class JsonKit {

    static {
        // 防御性关闭 autoType（fastjson2 默认已关闭，此处双保险，防全局配置漂移重新打开）
        JSONFactory.setDisableAutoType(true);
    }

    private JsonKit() {
    }

    /**
     * 序列化为 JSON 字符串（默认不输出 null 字段）
     */
    public static String toJson(Object obj) {
        return JSON.toJSONString(obj);
    }

    /**
     * 序列化为 JSON 字符串（null 字段一并输出，LLM 协议体构建用）
     */
    public static String toJsonWithNulls(Object obj) {
        return JSON.toJSONString(obj, JSONWriter.Feature.WriteNulls);
    }

    /**
     * 序列化为美化缩进格式（配置/规则文件落盘用）
     */
    public static String toPrettyJson(Object obj) {
        return JSON.toJSONString(obj, JSONWriter.Feature.PrettyFormat);
    }

    /**
     * 通用解析（按内容返回 JSONObject / JSONArray / 基础类型，亦可用于纯格式校验）
     */
    public static Object parse(String json) {
        return JSON.parse(json);
    }

    /**
     * 解析为 JSON 树对象（输入为 null 或空串时返回 null，与原生语义一致）
     */
    public static JSONObject parseObject(String json) {
        return JSON.parseObject(json);
    }

    /**
     * 解析为指定类型对象
     */
    public static <T> T parseObject(String json, Class<T> type) {
        return JSON.parseObject(json, type);
    }

    /**
     * 解析为 JSON 树数组（输入为 null 或空串时返回 null，与原生语义一致）
     */
    public static JSONArray parseArray(String json) {
        return JSON.parseArray(json);
    }

    /**
     * 解析为指定类型对象列表
     */
    public static <T> List<T> parseArray(String json, Class<T> type) {
        return JSON.parseArray(json, type);
    }
}
