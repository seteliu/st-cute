package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolExecutionContext;
import com.stioc.cute.tool.access.ToolNames;
import com.stioc.cute.security.access.WorkspacePathResolver;
import com.stioc.cute.agent.access.AgentContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 遍历文件与匹配过滤本地核心工具
 */
@Slf4j
@Component
public class FindFilesTool implements CuteTool {

    @Resource
    private WorkspacePathResolver workspacePathResolver;

    /**
     * 常见的大型无关依赖与构建产物目录，直接跳过以提升搜索性能
     */
    private static final Set<String> EXCLUDE_DIRS = Set.of(
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

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getName() {
        return ToolNames.LIST_DIR;
    }

    @Override
    public String getDescription() {
        return "在指定目录下按 Glob 表达式查找匹配的文件相对路径列表。已自动忽略 .git, node_modules, target 等无关目录。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "pattern": {
              "type": "string",
              "description": "文件名匹配的 glob 表达式，例如 '*.java'、'**/*.vue'、'*config*'"
            },
            "rootDir": {
              "type": "string",
              "description": "查找的根目录相对或绝对路径，可选，默认当前工作目录"
            }
          },
          "required": ["pattern"]
        }
        """;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        String patternVal = (String) arguments.get("pattern");
        if (patternVal == null || patternVal.isBlank()) {
            return new JSONObject().fluentPut("error", "参数 'pattern' 不能为空。").toJSONString();
        }

        String rootDirVal = (String) arguments.get("rootDir");
        Path rootPath;
        if (rootDirVal != null && !rootDirVal.isBlank()) {
            rootPath = workspacePathResolver.resolvePath(rootDirVal, agentContext);
        } else {
            rootPath = Paths.get(workspacePathResolver.getProjectBasePath(agentContext)).toAbsolutePath().normalize();
        }

        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            return new JSONObject().fluentPut("error", "搜索根目录不存在或不是目录: " + rootDirVal).toJSONString();
        }

        String syntaxAndPattern = patternVal.startsWith("glob:") || patternVal.startsWith("regex:")
                ? patternVal : "glob:**/" + patternVal;

        List<String> matchedFiles = new ArrayList<>();
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher(syntaxAndPattern);
            Path finalRootPath = rootPath;

            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (EXCLUDE_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matcher.matches(file) || matcher.matches(finalRootPath.relativize(file))) {
                        matchedFiles.add(finalRootPath.relativize(file).toString().replace("\\", "/"));
                    }
                    if (matchedFiles.size() >= 100) {
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            log.info("FindFilesTool 执行成功: {}, 匹配到 {} 个文件", patternVal, matchedFiles.size());

            JSONArray result = new JSONArray();
            result.addAll(matchedFiles);
            if (matchedFiles.size() >= 100) {
                result.add("... [匹配数量已达 100 个上限被截断] ...");
            }
            return result.toJSONString();

        } catch (IOException e) {
            log.error("FindFilesTool 查找异常", e);
            return new JSONObject().fluentPut("error", "查找文件失败: " + e.getMessage()).toJSONString();
        }
    }
}
