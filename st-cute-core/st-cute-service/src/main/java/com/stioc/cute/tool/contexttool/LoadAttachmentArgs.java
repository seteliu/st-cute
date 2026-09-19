package com.stioc.cute.tool.contexttool;

import com.stioc.cute.engine.tool.types.ToolArgs;

import java.util.Map;

/**
 * 附件加载工具输入参数强类型绑定对象
 */
public record LoadAttachmentArgs(String path) {
    public static LoadAttachmentArgs from(Map<String, Object> arguments) {
        ToolArgs args = ToolArgs.of(arguments);
        return new LoadAttachmentArgs(args.getStringTrimmed("path"));
    }
}
