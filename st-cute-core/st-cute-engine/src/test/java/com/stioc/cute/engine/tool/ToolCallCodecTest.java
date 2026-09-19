package com.stioc.cute.engine.tool;

import com.stioc.cute.engine.llm.types.CuteToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolCallCodec} 工具调用存量编解码单元测试。
 * <p>
 * tool_calls 字段存在「ASSISTANT 数组 / TOOL 单对象」两种存储形态，且历史库中
 * 可能存在形态错位的脏数据。编解码往返一致性直接决定模型能否正确续接
 * 工具调用回合，故此处覆盖标准形态、脏数据兼容与失败兜底三条路径。
 * </p>
 */
class ToolCallCodecTest {

    /**
     * ASSISTANT 数组形态的解析与往返
     */
    @Test
    void parsesAndRoundTripsList() {
        List<CuteToolCall> calls = List.of(
                CuteToolCall.builder().id("call_1").name("read_file").arguments("{\"path\":\"a.txt\"}").build(),
                CuteToolCall.builder().id("call_2").name("grep_search").arguments("{\"query\":\"x\"}").build());

        String raw = ToolCallCodec.writeList(calls);
        List<CuteToolCall> parsed = ToolCallCodec.parseList(raw);

        assertEquals(2, parsed.size());
        assertEquals("call_1", parsed.get(0).getId());
        assertEquals("read_file", parsed.get(0).getName());
        assertEquals("{\"path\":\"a.txt\"}", parsed.get(0).getArguments());
        assertEquals("call_2", parsed.get(1).getId());
    }

    /**
     * TOOL 单对象形态的解析与往返
     */
    @Test
    void parsesAndRoundTripsSingle() {
        CuteToolCall call = CuteToolCall.builder()
                .id("call_9").name("write_file").arguments("{\"path\":\"b.txt\"}").build();

        String raw = ToolCallCodec.writeSingle(call);
        CuteToolCall parsed = ToolCallCodec.parseSingle(raw);

        assertEquals("call_9", parsed.getId());
        assertEquals("write_file", parsed.getName());
        assertEquals("{\"path\":\"b.txt\"}", parsed.getArguments());
    }

    /**
     * 脏数据兼容：ASSISTANT 字段意外存成单对象时，parseList 应包装为单元素列表
     */
    @Test
    void parseListToleratesSingleObject() {
        String single = ToolCallCodec.writeSingle(
                CuteToolCall.builder().id("c").name("n").arguments("{}").build());

        List<CuteToolCall> parsed = ToolCallCodec.parseList(single);
        assertEquals(1, parsed.size(), "单对象脏数据应被兼容为单元素列表");
        assertEquals("c", parsed.get(0).getId());
    }

    /**
     * 脏数据兼容：TOOL 字段意外存成数组时，parseSingle 应取首个元素
     */
    @Test
    void parseSingleToleratesList() {
        String list = ToolCallCodec.writeList(List.of(
                CuteToolCall.builder().id("first").name("n1").arguments("{}").build(),
                CuteToolCall.builder().id("second").name("n2").arguments("{}").build()));

        CuteToolCall parsed = ToolCallCodec.parseSingle(list);
        assertEquals("first", parsed.getId(), "数组脏数据应取首个元素");
    }

    /**
     * 边界兜底：null 与空白输入不得抛异常，且返回值必须非 null（保持非空契约）
     */
    @Test
    void toleratesNullAndBlankInput() {
        assertTrue(ToolCallCodec.parseList(null).isEmpty());
        assertTrue(ToolCallCodec.parseList("").isEmpty());
        assertTrue(ToolCallCodec.parseList("   ").isEmpty());

        assertNotNull(ToolCallCodec.parseSingle(null));
        assertNotNull(ToolCallCodec.parseSingle(""));
        assertNotNull(ToolCallCodec.parseSingle("   "));
        assertEquals("", ToolCallCodec.parseSingle(null).getId(), "兜底应返回 id 为空串的空标识对象");
        assertEquals("", ToolCallCodec.parseSingle(null).getName(), "兜底应返回 name 为空串的空标识对象");
    }

    /**
     * 非法 JSON 不得抛出：返回空清单 / 空标识对象，保证调用链不因脏数据崩溃
     */
    @Test
    void toleratesMalformedJson() {
        assertTrue(ToolCallCodec.parseList("{not json").isEmpty(), "非法 JSON 应降级为空清单");
        assertNotNull(ToolCallCodec.parseSingle("[not json"), "非法 JSON 应降级为空标识对象");
        assertTrue(ToolCallCodec.parseList("neither-array-nor-object").isEmpty(),
                "无法识别的形态应降级为空清单");
    }

    /**
     * 空清单序列化与反序列化保持一致
     */
    @Test
    void handlesEmptyList() {
        String raw = ToolCallCodec.writeList(List.of());
        assertTrue(ToolCallCodec.parseList(raw).isEmpty(), "空清单往返后仍应为空");
    }
}
