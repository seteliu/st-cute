package com.stioc.cute.tool.filetool;

import com.stioc.cute.engine.tool.types.ToolArgs;

import java.util.Map;

/**
 * 局部替换文件内容工具输入参数强类型绑定对象
 */
public record EditFileArgs(
        String path,
        String oldContent,
        String newContent,
        Integer startLine,
        Integer endLine
) {
    public static EditFileArgs from(Map<String, Object> arguments) {
        ToolArgs args = ToolArgs.of(arguments);
        String path = args.getString("path");
        String oldContent = args.getString("oldContent");
        String newContent = args.getString("newContent");
        if (newContent == null) {
            newContent = "";
        }
        Integer startLine = args.getInt("startLine");
        Integer endLine = args.getInt("endLine");

        return new EditFileArgs(path, oldContent, newContent, startLine, endLine);
    }

    public boolean hasLineRange() {
        return startLine != null && endLine != null;
    }

    public boolean hasInvalidLineRange() {
        return (startLine != null && endLine == null) || (startLine == null && endLine != null);
    }
}
