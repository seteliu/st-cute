package com.stioc.cute.engine.tool;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.common.JsonKit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具参数 Schema 静态校验器。
 * <p>
 * 启动期对 {@code CuteTool#getArgumentSchema()} 返回的 JSON Schema 字符串做轻量语法卫兵：
 * 捕获「JSON 语法错误、type 拼写错误、required 与 properties 脱节、default 与声明 type 不兼容、
 * enum 空数组或重复元素」等漂移缺陷，不合法即 fail-fast 拒绝工具注册（静态工具拒绝启动），
 * 防止带病 schema 静默进入模型请求体导致调用必失败。
 * </p>
 */
public final class ToolSchemaValidator {

    /**
     * JSON Schema 合法的基础类型集合
     */
    private static final Set<String> LEGAL_TYPES =
            Set.of("string", "integer", "number", "boolean", "array", "object");

    private ToolSchemaValidator() {
    }

    /**
     * 校验工具参数 Schema，返回错误清单（空列表即合法）。
     *
     * @param toolName 工具名（仅用于错误信息可读性）
     * @param schema   工具声明的 JSON Schema 字符串
     */
    public static List<String> validate(String toolName, String schema) {
        List<String> errors = new ArrayList<>();
        if (schema == null || schema.isBlank()) {
            errors.add("schema 为空");
            return errors;
        }
        JSONObject root;
        try {
            root = JsonKit.parseObject(schema);
        } catch (Exception e) {
            errors.add("不是合法 JSON: " + e.getMessage());
            return errors;
        }
        if (root == null || root.isEmpty()) {
            errors.add("顶层必须为非空 JSON object");
            return errors;
        }
        if (!"object".equals(root.getString("type"))) {
            errors.add("顶层 type 必须为 object（实际: " + root.getString("type") + "）");
        }
        JSONObject properties = root.getJSONObject("properties");
        if (properties == null || properties.isEmpty()) {
            errors.add("缺少 properties 定义");
            return errors;
        }

        // 逐属性校验：type 合法性、default 与 type 宽松兼容、enum 非空互异
        for (String key : properties.keySet()) {
            try {
                JSONObject prop = properties.getJSONObject(key);
                if (prop == null) {
                    errors.add("属性 " + key + " 的定义必须为 object");
                    continue;
                }
                String type = prop.getString("type");
                if (type == null || !LEGAL_TYPES.contains(type)) {
                    errors.add("属性 " + key + " 的 type 非法: " + type);
                }
                Object def = prop.get("default");
                if (def != null && type != null && LEGAL_TYPES.contains(type) && !isDefaultCompatible(def, type)) {
                    errors.add("属性 " + key + " 的 default 与 type=" + type + " 不兼容: " + def);
                }
                JSONArray enumArr = prop.getJSONArray("enum");
                if (enumArr != null) {
                    if (enumArr.isEmpty()) {
                        errors.add("属性 " + key + " 的 enum 不能为空数组");
                    } else if (new HashSet<>(enumArr).size() != enumArr.size()) {
                        errors.add("属性 " + key + " 的 enum 存在重复元素");
                    }
                }
            } catch (Exception e) {
                errors.add("属性 " + key + " 校验异常: " + e.getMessage());
            }
        }

        // required 与 properties 的 key 集合交叉校验
        JSONArray required = root.getJSONArray("required");
        if (required != null) {
            for (int i = 0; i < required.size(); i++) {
                String req = required.getString(i);
                if (req == null || !properties.containsKey(req)) {
                    errors.add("required 引用了未定义的属性: " + req);
                }
            }
        }
        return errors;
    }

    /**
     * default 值与声明 type 的宽松兼容判定（JSON 形态对齐）
     */
    private static boolean isDefaultCompatible(Object def, String type) {
        return switch (type) {
            case "string" -> def instanceof String;
            case "integer" -> (def instanceof Number n && !(def instanceof Double || def instanceof Float))
                    || (def instanceof String s && s.matches("-?\\d+"));
            case "number" -> def instanceof Number
                    || (def instanceof String s && s.matches("-?\\d+(\\.\\d+)?"));
            case "boolean" -> def instanceof Boolean;
            case "array" -> def instanceof List;
            case "object" -> def instanceof java.util.Map;
            default -> true;
        };
    }
}
