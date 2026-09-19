package com.stioc.cute.tool.filetool;

import com.stioc.cute.engine.tool.types.ToolArgs;

import java.util.Map;

/**
 * 新建/覆写文件工具输入参数强类型绑定对象
 */
public record WriteFileArgs(
        String path,
        String content,
        String encoding
) {
    public static final String DEFAULT_ENCODING = "";

    public static WriteFileArgs from(Map<String, Object> arguments) {
        ToolArgs args = ToolArgs.of(arguments);
        String path = args.getString("path");
        String content = args.getString("content");
        if (content == null) {
            content = "";
        }
        String encoding = args.getStringTrimmed("encoding", DEFAULT_ENCODING);

        return new WriteFileArgs(path, content, encoding);
    }

    public boolean hasExplicitEncoding() {
        return encoding != null && !encoding.isEmpty() && !"auto".equalsIgnoreCase(encoding);
    }
}
