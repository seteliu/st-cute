package com.stioc.cute.tool.findtool;

import com.stioc.cute.engine.tool.types.ToolArgs;

import java.util.Map;

/**
 * 按 Glob 表达式查找文件工具输入参数强类型绑定对象
 */
public record FindFilesArgs(
        String pattern,
        String rootDir,
        boolean includeExcludedDirs,
        int maxResults,
        String normalizedPattern,
        boolean shallowMode
) {
    public static final int DEFAULT_MAX_RESULTS = 500;
    public static final int MAX_RESULTS_LIMIT = 2000;

    public static FindFilesArgs from(Map<String, Object> arguments) {
        ToolArgs args = ToolArgs.of(arguments);
        String pattern = args.getString("pattern");
        String rootDir = args.getStringTrimmed("rootDir");
        boolean includeExcludedDirs = Boolean.TRUE.equals(args.getBoolean("includeExcludedDirs"));

        Integer maxResultsVal = args.getInt("maxResults");
        int maxResults = maxResultsVal != null
                ? Math.max(1, Math.min(MAX_RESULTS_LIMIT, maxResultsVal))
                : DEFAULT_MAX_RESULTS;

        String normalizedPattern = pattern != null ? pattern.replace("\\", "/") : "";
        boolean shallowMode = !normalizedPattern.contains("/");

        return new FindFilesArgs(pattern, rootDir, includeExcludedDirs, maxResults, normalizedPattern, shallowMode);
    }
}
