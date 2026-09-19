package com.stioc.cute.engine.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolSchemaValidator} 参数 Schema 静态校验单元测试。
 * <p>
 * 该卫兵位于工具注册的关键路径上：校验不通过的静态工具会被拒绝注册并让启动失败，
 * 故其判定口径必须精确——既不能放过带病 Schema，也不能误杀合法工具。
 * </p>
 */
class ToolSchemaValidatorTest {

    /**
     * 合法 Schema 应零错误通过
     */
    @Test
    void acceptsLegalSchema() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "path": {"type": "string", "description": "文件路径"},
                    "limit": {"type": "integer", "default": 100}
                  },
                  "required": ["path"]
                }
                """;
        assertTrue(ToolSchemaValidator.validate("read_file", schema).isEmpty(),
                "合法 Schema 不应产生任何错误");
    }

    /**
     * 空 / null Schema 直接判错（这是导致模型调用必失败的典型来源）
     */
    @Test
    void rejectsBlankSchema() {
        assertFalse(ToolSchemaValidator.validate("t", null).isEmpty());
        assertFalse(ToolSchemaValidator.validate("t", "").isEmpty());
        assertFalse(ToolSchemaValidator.validate("t", "   ").isEmpty());
    }

    /**
     * JSON 语法错误判错
     */
    @Test
    void rejectsMalformedJson() {
        List<String> errors = ToolSchemaValidator.validate("t", "{\"type\":\"object\",");
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("不是合法 JSON"), "应指出 JSON 语法问题，实际: " + errors);
    }

    /**
     * 顶层 type 必须为 object，且必须有非空 properties
     */
    @Test
    void rejectsWrongTopLevelTypeAndMissingProperties() {
        List<String> wrongType = ToolSchemaValidator.validate("t", "{\"type\":\"array\",\"properties\":{\"a\":{\"type\":\"string\"}}}");
        assertTrue(wrongType.stream().anyMatch(e -> e.contains("顶层 type 必须为 object")),
                "应拦截非 object 顶层类型，实际: " + wrongType);

        List<String> emptyProps = ToolSchemaValidator.validate("t", "{\"type\":\"object\",\"properties\":{}}");
        assertTrue(emptyProps.stream().anyMatch(e -> e.contains("缺少 properties 定义")),
                "应拦截空的 properties，实际: " + emptyProps);
    }

    /**
     * 属性 type 拼写错误必须被捕获（幻觉类型名是最常见的漂移缺陷）
     */
    @Test
    void rejectsIllegalPropertyType() {
        String schema = """
                {"type":"object","properties":{"count":{"type":"int"}}}
                """;
        List<String> errors = ToolSchemaValidator.validate("t", schema);
        assertTrue(errors.stream().anyMatch(e -> e.contains("的 type 非法")),
                "应拦截非法的属性 type，实际: " + errors);
    }

    /**
     * default 与声明 type 的兼容性判定（含数字字符串等宽松形态）
     */
    @Test
    void checksDefaultCompatibility() {
        // 合法：integer 配数字字面量与数字字符串均接受
        assertTrue(ToolSchemaValidator.validate("t",
                "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"default\":5}}}").isEmpty());
        assertTrue(ToolSchemaValidator.validate("t",
                "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"default\":\"5\"}}}").isEmpty());

        // 非法：integer 配布尔值
        List<String> bad = ToolSchemaValidator.validate("t",
                "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"default\":true}}}");
        assertTrue(bad.stream().anyMatch(e -> e.contains("default 与 type")),
                "应拦截 default 与 type 不兼容，实际: " + bad);

        // 非法：string 配数字
        List<String> badString = ToolSchemaValidator.validate("t",
                "{\"type\":\"object\",\"properties\":{\"s\":{\"type\":\"string\",\"default\":1}}}");
        assertTrue(badString.stream().anyMatch(e -> e.contains("default 与 type")),
                "应拦截 string 配数字 default，实际: " + badString);
    }

    /**
     * enum 空数组与非互异元素必须判错
     */
    @Test
    void checksEnumArray() {
        List<String> empty = ToolSchemaValidator.validate("t",
                "{\"type\":\"object\",\"properties\":{\"m\":{\"type\":\"string\",\"enum\":[]}}}");
        assertTrue(empty.stream().anyMatch(e -> e.contains("enum 不能为空数组")),
                "应拦截空 enum，实际: " + empty);

        List<String> dup = ToolSchemaValidator.validate("t",
                "{\"type\":\"object\",\"properties\":{\"m\":{\"type\":\"string\",\"enum\":[\"a\",\"a\"]}}}");
        assertTrue(dup.stream().anyMatch(e -> e.contains("enum 存在重复元素")),
                "应拦截重复 enum，实际: " + dup);

        // 合法 enum 通过
        assertTrue(ToolSchemaValidator.validate("t",
                "{\"type\":\"object\",\"properties\":{\"m\":{\"type\":\"string\",\"enum\":[\"a\",\"b\"]}}}").isEmpty());
    }

    /**
     * required 引用了未定义的属性必须判错（required 与 properties 脱节）
     */
    @Test
    void rejectsRequiredReferencingUndefinedProperty() {
        String schema = """
                {
                  "type": "object",
                  "properties": {"path": {"type": "string"}},
                  "required": ["path", "ghost"]
                }
                """;
        List<String> errors = ToolSchemaValidator.validate("t", schema);
        assertTrue(errors.stream().anyMatch(e -> e.contains("required 引用了未定义的属性")),
                "应拦截 required 引用未定义属性，实际: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("ghost")),
                "错误信息应回显越界的属性名，实际: " + errors);
    }

    /**
     * 多缺陷应一次性全部收集（而非遇到首个错误就返回），便于启动期集中排障
     */
    @Test
    void collectsAllErrorsAtOnce() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "badType": {"type": "int"},
                    "emptyEnum": {"type": "string", "enum": []}
                  },
                  "required": ["notDefined"]
                }
                """;
        List<String> errors = ToolSchemaValidator.validate("t", schema);
        assertEquals(3, errors.size(),
                "应一次性收集 type 非法、enum 空数组、required 越界三类错误，实际: " + errors);
    }
}
