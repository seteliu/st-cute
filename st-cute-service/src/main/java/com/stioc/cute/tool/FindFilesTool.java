package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.FileSearchConstants;
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

/**
 * 遍历文件与匹配过滤本地核心工具
 */
@Slf4j
@Component
public class FindFilesTool implements CuteTool {

    @Resource
    private WorkspacePathResolver workspacePathResolver;

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
        return "在指定目录下按 Glob 表达式查找匹配的文件相对路径列表。已自动忽略 .git, node_modules, target 等产物与依赖目录（任何情况都排除）。默认跳过以点(.)开头的隐藏目录（含 .agents 等用户配置目录），如需搜索隐藏目录可设置 includeHidden 为 true。";
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
            },
            "includeHidden": {
              "type": "boolean",
              "description": "是否搜索以点(.)开头的隐藏目录（如 .git, .idea, AI临时目录等），可选，默认 false（默认跳过隐藏目录）",
              "default": false
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
        // 解析是否搜索以点开头的隐藏目录
        boolean includeHidden = Boolean.TRUE.equals(arguments.get("includeHidden"));
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
                    String dirName = dir.getFileName().toString();
                    // 遍历根目录本身不跳过（用户显式指定的搜索入口）
                    if (dir.equals(finalRootPath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    // 硬排除目录：纯产物/依赖/版本库/IDE 缓存，任何情况都跳过，防止海量文件拖垮搜索
                    if (FileSearchConstants.HARD_EXCLUDE_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // 点开头隐藏目录（含 .agents/.github/.gemini 等用户配置资产目录）：
                    // 默认跳过防噪音，includeHidden = true 时放行
                    if (!includeHidden && dirName.startsWith(".")) {
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
