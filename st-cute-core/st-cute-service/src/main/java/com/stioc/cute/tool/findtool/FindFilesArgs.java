package com.stioc.cute.tool.findtool;

import com.stioc.cute.engine.tool.types.ToolArgs;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按 Glob 表达式查找文件工具输入参数强类型绑定对象
 */
public record FindFilesArgs(
        String pattern,
        String rootDir,
        boolean includeExcludedDirs,
        int maxResults,
        String normalizedPattern,
        List<String> candidatePatterns,
        boolean shallowMode
) {
    public static final int DEFAULT_MAX_RESULTS = 500;
    public static final int MAX_RESULTS_LIMIT = 2000;

    /**
     * 候选 pattern 数量上限。
     * <p>
     * 每个 {@code **}{@code /} 都会派生一个候选，候选数随出现次数按 2 的幂增长。
     * 超过本上限即放弃展开、退回原始语义——宁可少匹配也不能让每轮文件遍历的匹配次数失控。
     * </p>
     */
    private static final int MAX_CANDIDATE_PATTERNS = 8;

    public static FindFilesArgs from(Map<String, Object> arguments) {
        ToolArgs args = ToolArgs.of(arguments);
        String pattern = args.getString("pattern");
        String rootDir = args.getStringTrimmed("rootDir");
        boolean includeExcludedDirs = Boolean.TRUE.equals(args.getBoolean("includeExcludedDirs"));

        Integer maxResultsVal = args.getInt("maxResults");
        int maxResults = maxResultsVal != null
                ? Math.max(1, Math.min(MAX_RESULTS_LIMIT, maxResultsVal))
                : DEFAULT_MAX_RESULTS;

        String rawPattern = pattern != null ? pattern : "";
        // 显式语法前缀（glob: / regex:）：保持原样不做反斜杠替换。
        // regex: 形态中的反斜杠是正则转义语义（如 \d、\w），替换为 / 会直接破坏正则；
        // 且显式前缀意味着调用方已自行声明匹配语法，浅层/递归模式交由 pattern 是否含分隔符判定
        boolean explicitSyntax = rawPattern.startsWith("glob:") || rawPattern.startsWith("regex:");
        String normalizedPattern = explicitSyntax ? rawPattern : rawPattern.replace("\\", "/");
        boolean shallowMode = !explicitSyntax && !normalizedPattern.contains("/");

        // glob 语义补全：JDK 的 glob 语法中 * 不跨目录分隔符，故 "src/**/*.java" 不匹配 "src/App.java"、
        // "**/*.java" 不匹配顶层 "App.java"，与 Go/bash 直觉（** 可匹配零层目录）相悖，
        // 使用者按工具描述传参会静默漏掉顶层文件。此处展开为多个候选 pattern，
        // 使 **/ 同时覆盖「零层目录」与「任意层目录」两种情形
        List<String> candidatePatterns = (shallowMode || explicitSyntax)
                ? List.of(normalizedPattern)
                : expandDoubleStarSlash(normalizedPattern);

        return new FindFilesArgs(pattern, rootDir, includeExcludedDirs, maxResults,
                normalizedPattern, candidatePatterns, shallowMode);
    }

    /**
     * 将 glob 中的 {@code **}{@code /} 展开为「同时可匹配零层目录」的候选 pattern 列表。
     * <p>
     * JDK glob 语法无法用单条表达式表达「零层或任意层」，故对每个 {@code **}{@code /}
     * 派生一个「去掉该处前缀」的候选。以 {@code a/**}{@code /b/**}{@code /c.java} 为例，
     * 最终得到 4 个候选：{@code a/**}{@code /b/**}{@code /c.java}、{@code a/b/**}{@code /c.java}、
     * {@code a/**}{@code /b/c.java}、{@code a/b/c.java}，覆盖 0～2 层目录的全部组合。
     * </p>
     * <p>
     * 收敛性：每次派生都严格减少候选中的 {@code **}{@code /} 数量，故候选集有限且循环必然终止。
     * </p>
     *
     * @param pattern 已归一化（反斜杠转正斜杠）的 glob 表达式
     * @return 待尝试的候选 pattern 列表（首项为原始 pattern）
     */
    private static List<String> expandDoubleStarSlash(String pattern) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(pattern);

        boolean grown = true;
        while (grown) {
            grown = false;
            for (String base : new ArrayList<>(candidates)) {
                int hit = base.indexOf("**/");
                if (hit < 0) {
                    continue;
                }
                if (candidates.size() >= MAX_CANDIDATE_PATTERNS) {
                    // 候选过多：放弃展开，退回原始语义
                    return List.of(pattern);
                }
                if (candidates.add(base.substring(0, hit) + base.substring(hit + 3))) {
                    grown = true;
                }
            }
        }
        return List.copyOf(candidates);
    }
}
