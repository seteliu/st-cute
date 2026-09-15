package com.stioc.cute.tool.types;

import java.util.Set;

/**
 * 文件搜索相关公共常量，供 FindFilesTool、GrepSearchTool 等工具共享使用
 */
public final class FileSearchConstants {

    private FileSearchConstants() {
    }

    /**
     * 永久排除目录集合：版本库内部对象等任何场景都无搜索/遍历价值的目录。
     * 即使 includeExcludedDirs = true 也不会被放行，与产物依赖类目录区别对待。
     */
    public static final Set<String> ALWAYS_EXCLUDE_DIRS = Set.of(
            // 版本控制内部对象（二进制 pack 为主，遍历无价值且噪音极大）
            ".git", ".svn", ".hg"
    );

    /**
     * 常规排除清单：纯产物、依赖包、IDE 缓存等默认无搜索价值的目录。
     * 默认跳过，防止海量文件拖垮搜索与污染结果；includeExcludedDirs = true 时可放行。
     * 注意：识别依据是「显式列入清单的目录名」，而非点(.)开头前缀——
     * 点开头的目录若未列入本清单（如 .ai-work、.agents、.github 等用户配置资产目录），
     * 视为正常资产目录参与搜索，不做任何过滤，避免误杀。
     */
    public static final Set<String> EXCLUDE_DIRS = Set.of(
            // IDE 配置文件与缓存
            ".idea", ".vscode", ".vs", ".settings", ".metadata",
            // 后端编译与构建输出 (Java/Gradle/Rust/Go)
            "target", "build", "out", "bin", ".gradle",
            // 前端打包与依赖 (Node/Web)
            "node_modules", "dist", ".next", ".nuxt", ".output",
            // Python 虚拟环境与工具缓存
            "venv", ".venv", "env", ".env", "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache",
            // C/C++ 构建与编译中间文件
            "cmake-build-debug", "cmake-build-release", "CMakeFiles", "Debug", "Release", "x64",
            // 移动端与其他语言产物缓存
            "Pods", ".dart_tool", ".expo", "coverage",
            // 其他常见依赖与通用缓存 (PHP/Ruby 等)
            "vendor", ".bundle", ".cache"
    );
}
