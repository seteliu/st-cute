package com.stioc.cute.tool.access;

import java.util.Set;

/**
 * 文件搜索相关公共常量，供 FindFilesTool、GrepSearchTool 等工具共享使用
 */
public final class FileSearchConstants {

    private FileSearchConstants() {
    }

    /**
     * 排除搜索的无关物理目录集合，包含常见开发语言、构建工具、IDE 缓存与依赖包目录。
     * 这些目录即使 includeHidden = true 也会被排除。
     */
    public static final Set<String> EXCLUDE_DIRS = Set.of(
            // 版本控制与 AI 隔离
            ".git", ".github", ".agents", ".gemini",
            // 常见 IDE 配置文件与缓存
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
