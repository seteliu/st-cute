package com.stioc.cute.tool.access;

import java.util.Set;

/**
 * 文件搜索相关公共常量，供 FindFilesTool、GrepSearchTool 等工具共享使用
 */
public final class FileSearchConstants {

    private FileSearchConstants() {
    }

    /**
     * 永久排除目录集合：版本库内部对象等任何场景都无搜索/遍历价值的目录。
     * 即使 includeBuildArtifacts = true 也不会被放行，与构建产物类目录区别对待。
     */
    public static final Set<String> ALWAYS_EXCLUDE_DIRS = Set.of(
            // 版本控制内部对象（二进制 pack 为主，遍历无价值且噪音极大）
            ".git"
    );

    /**
     * 硬排除目录集合：纯产物、依赖包、IDE 缓存等默认无搜索价值的目录。
     * 默认任何情况下都会被跳过，防止海量文件拖垮搜索与污染结果；
     * FindFilesTool 可通过 includeBuildArtifacts = true 显式放行（如验证编译产物），
     * GrepSearchTool 维持硬排除语义不放行。
     * 注意：以点开头的用户配置资产目录（如 .agents、.github）不在此列，
     * 它们走「点开头目录默认跳过、includeHidden = true 放行」的通用规则。
     */
    public static final Set<String> HARD_EXCLUDE_DIRS = Set.of(
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
            // 其他常见依赖与通用缓存 (PHP/Ruby 等)
            "vendor", ".bundle", ".cache"
    );
}
