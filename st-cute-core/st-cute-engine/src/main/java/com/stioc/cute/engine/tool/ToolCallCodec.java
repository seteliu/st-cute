package com.stioc.cute.engine.tool;

import com.stioc.cute.engine.common.JsonKit;
import com.stioc.cute.engine.llm.types.CuteToolCall;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 工具调用存量（tool_calls 字段）统一编解码器。
 * <p>
 * 消息库中 tool_calls 字段存在两种存储形态（字段结构同构）：
 * <ul>
 *   <li>ASSISTANT 消息：JSON 数组（每次推理发起的一批工具调用）</li>
 *   <li>TOOL 消息：JSON 单对象（该条工具消息对应的单次调用）</li>
 * </ul>
 * 全引擎所有解析/写入点统一收敛至本类，杜绝各处手搓 JSONObject 解析漂移。
 * </p>
 */
@Slf4j
public final class ToolCallCodec {

    private ToolCallCodec() {
    }

    /**
     * 解析 ASSISTANT 消息的 tool_calls（数组格式），失败返回空列表。
     * 与 {@link #parseSingle} 共用同构字段，可兼容意外存成单对象的脏数据
     */
    public static List<CuteToolCall> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String trimmed = raw.trim();
        try {
            // 标准形态：数组
            if (trimmed.startsWith("[")) {
                List<CuteToolCall> calls = JsonKit.parseArray(trimmed, CuteToolCall.class);
                return calls != null ? calls : List.of();
            }
            // 脏数据兼容：单对象包装为单元素列表
            if (trimmed.startsWith("{")) {
                CuteToolCall call = JsonKit.parseObject(trimmed, CuteToolCall.class);
                return call != null ? List.of(call) : List.of();
            }
            log.warn("tool_calls 存量格式无法识别，返回空清单: {}", abbreviate(trimmed));
        } catch (Exception e) {
            log.warn("解析 ASSISTANT 消息 tool_calls 失败，返回空清单: {}", abbreviate(trimmed), e);
        }
        return List.of();
    }

    /**
     * 解析 TOOL 消息的 tool_calls（单对象格式），失败返回空标识对象。
     * 与 {@link #parseList} 共用同构字段，可兼容意外存成数组的脏数据
     */
    public static CuteToolCall parseSingle(String raw) {
        if (raw == null || raw.isBlank()) {
            return emptyCall();
        }
        String trimmed = raw.trim();
        try {
            // 标准形态：单对象
            if (trimmed.startsWith("{")) {
                CuteToolCall call = JsonKit.parseObject(trimmed, CuteToolCall.class);
                return call != null ? call : emptyCall();
            }
            // 脏数据兼容：数组取首个元素
            if (trimmed.startsWith("[")) {
                List<CuteToolCall> calls = JsonKit.parseArray(trimmed, CuteToolCall.class);
                if (calls != null && !calls.isEmpty()) {
                    return calls.get(0);
                }
            }
            log.warn("tool_calls 存量格式无法识别，返回空标识: {}", abbreviate(trimmed));
        } catch (Exception e) {
            log.warn("解析 TOOL 消息 tool_calls 失败，返回空标识: {}", abbreviate(trimmed), e);
        }
        return emptyCall();
    }

    /**
     * 序列化为 ASSISTANT 消息的数组格式存量
     */
    public static String writeList(List<CuteToolCall> calls) {
        return JsonKit.toJson(calls);
    }

    /**
     * 序列化为 TOOL 消息的单对象格式存量
     */
    public static String writeSingle(CuteToolCall call) {
        return JsonKit.toJson(call);
    }

    /**
     * 空标识对象（id/name 均为空串），供解析失败时保持非 null 契约
     */
    public static CuteToolCall emptyCall() {
        return CuteToolCall.builder().id("").name("").build();
    }

    /**
     * 日志用缩略展示（防超长存量撑爆日志）
     */
    private static String abbreviate(String raw) {
        return raw.length() <= 200 ? raw : raw.substring(0, 200) + "...";
    }
}
