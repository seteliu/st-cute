package com.stioc.cute.tool.findtool;

import com.stioc.cute.engine.tool.types.ToolArgs;

import java.util.Map;

/**
 * 局部/全局文本文件正文搜索匹配工具输入参数强类型绑定对象
 */
public record GrepSearchArgs(
        String query,
        String rootDir,
        boolean useRegex,
        boolean ignoreCase,
        boolean includeExcludedDirs,
        int maxResults
) {
    public static final int DEFAULT_MAX_RESULTS = 50;
    public static final int MAX_RESULTS_LIMIT = 500;

    public static GrepSearchArgs from(Map<String, Object> arguments) {
        ToolArgs args = ToolArgs.of(arguments);
        String query = args.getString("query");
        String rootDir = args.getStringTrimmed("rootDir");
        boolean useRegex = Boolean.TRUE.equals(args.getBoolean("useRegex"));
        boolean ignoreCase = Boolean.TRUE.equals(args.getBoolean("ignoreCase"));
        boolean includeExcludedDirs = Boolean.TRUE.equals(args.getBoolean("includeExcludedDirs"));

        Integer maxResultsVal = args.getInt("maxResults");
        int maxResults = maxResultsVal != null
                ? Math.max(1, Math.min(MAX_RESULTS_LIMIT, maxResultsVal))
                : DEFAULT_MAX_RESULTS;

        return new GrepSearchArgs(query, rootDir, useRegex, ignoreCase, includeExcludedDirs, maxResults);
    }
}
