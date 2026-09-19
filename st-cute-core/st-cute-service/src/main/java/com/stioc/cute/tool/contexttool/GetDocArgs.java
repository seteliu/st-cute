package com.stioc.cute.tool.contexttool;

import com.stioc.cute.engine.tool.types.ToolArgs;

import java.util.Map;

/**
 * 获取规约文档工具输入参数强类型绑定对象
 */
public record GetDocArgs(String topic) {
    public static GetDocArgs from(Map<String, Object> arguments) {
        ToolArgs args = ToolArgs.of(arguments);
        String raw = args.getString("topic");
        String normalized = raw != null ? raw.toLowerCase().trim() : null;
        return new GetDocArgs(normalized);
    }
}
