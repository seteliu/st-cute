package com.stioc.cute.engine.tool.types;

import com.alibaba.fastjson2.JSONObject;

import java.util.function.Consumer;

/**
 * 工具错误结果统一构建器。
 * <p>
 * 错误结果契约的唯一收口：所有工具的错误返回必须经由此类构建，
 * 保证「error」字段名与 JSON 形态全局一致，供引擎统一判定成功/失败，
 * 禁止再手写 JSON 字符串拼接（含引号/换行的消息会产生非法 JSON）。
 * </p>
 */
public final class ToolResult {

    /**
     * 统一错误字段名
     */
    public static final String ERROR_KEY = "error";

    private ToolResult() {
    }

    /**
     * 构建标准错误 JSON（单 error 字段）
     *
     * @param message 面向模型的错误说明（应包含可行动的修正建议）
     * @return 标准错误 JSON 字符串
     */
    public static String error(String message) {
        return new JSONObject().fluentPut(ERROR_KEY, message).toJSONString();
    }

    /**
     * 构建带附加字段的错误 JSON（如 status、path 等上下文信息）。
     * 附加字段写入时 error 字段已就位，覆盖 error 键的行为未定义（禁止）。
     *
     * @param message 面向模型的错误说明
     * @param extra   附加字段写入器，可为 null
     * @return 错误 JSON 字符串
     */
    public static String error(String message, Consumer<JSONObject> extra) {
        JSONObject obj = new JSONObject();
        obj.fluentPut(ERROR_KEY, message);
        if (extra != null) {
            extra.accept(obj);
        }
        return obj.toJSONString();
    }
}
