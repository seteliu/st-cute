package com.stioc.cute.tool.filetool;

import com.stioc.cute.engine.tool.types.ToolArgs;

import java.util.Map;

/**
 * 读取文件工具输入参数强类型绑定对象
 */
public record ReadFileArgs(
        String path,
        int startLine,
        int lineCount,
        String encoding
) {
    public static final int DEFAULT_START_LINE = 1;
    public static final int DEFAULT_LINE_COUNT = 1000;
    public static final int MAX_LINE_COUNT_LIMIT = 10000;
    public static final String DEFAULT_ENCODING = "auto";

    public static ReadFileArgs from(Map<String, Object> arguments) {
        ToolArgs args = ToolArgs.of(arguments);
        String path = args.getString("path");
        int startLine = Math.max(1, args.getInt("startLine", DEFAULT_START_LINE));
        int rawLineCount = args.getInt("lineCount", DEFAULT_LINE_COUNT);
        int lineCount = Math.max(1, Math.min(MAX_LINE_COUNT_LIMIT, rawLineCount));
        String encoding = args.getStringTrimmed("encoding", DEFAULT_ENCODING);

        return new ReadFileArgs(path, startLine, lineCount, encoding);
    }

    public boolean isAutoEncoding() {
        return DEFAULT_ENCODING.equalsIgnoreCase(encoding);
    }
}
