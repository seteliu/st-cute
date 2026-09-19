package com.stioc.cute.tool.websearchtool;

import com.stioc.cute.engine.tool.types.ToolArgs;

import java.util.Map;

/**
 * 联网聚合搜索工具输入参数强类型绑定对象
 */
public record SearxngSearchArgs(
        String query,
        int pageno
) {
    public static final int DEFAULT_PAGENO = 1;

    public static SearxngSearchArgs from(Map<String, Object> arguments) {
        ToolArgs args = ToolArgs.of(arguments);
        String query = args.getStringTrimmed("query");
        Integer pagenoVal = args.getInt("pageno");
        int pageno = (pagenoVal != null && pagenoVal >= 1) ? pagenoVal : DEFAULT_PAGENO;

        return new SearxngSearchArgs(query, pageno);
    }
}
