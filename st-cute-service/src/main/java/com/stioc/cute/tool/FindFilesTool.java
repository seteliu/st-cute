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
        return "在指定目录下按 Glob 表达式查找匹配的文件相对路径列表。"
                + "匹配模式按 pattern 形态自动区分：不含 / 时为浅层模式（仅匹配 rootDir 直接子项，目录条目以 / 结尾返回，可用来仅列目录）；含 / 或 ** 时为递归模式（按 glob 全路径匹配，等价旧版递归语义）。"
                + "已自动忽略 target, node_modules 等产物与依赖目录，如需放行编译产物（如验证构建输出）可设置 includeBuildArtifacts 为 true（.git 始终排除）。"
                + "默认跳过以点(.)开头的隐藏目录（含 .agents 等用户配置目录），如需搜索隐藏目录可设置 includeHidden 为 true。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "pattern": {
              "type": "string",
              "description": "文件名匹配的 glob 表达式：不含 / 时（如 '*.java'、'*config*'）为浅层模式仅匹配直接子项；含 / 或 ** 时（如 '**/*.vue'、'src/main/**/*.java'）为递归模式匹配全路径"
            },
            "rootDir": {
              "type": "string",
              "description": "查找的根目录相对或绝对路径，可选，默认当前工作目录"
            },
            "includeHidden": {
              "type": "boolean",
              "description": "是否搜索以点(.)开头的隐藏目录（如 .idea, .agents, AI临时目录等），可选，默认 false（默认跳过隐藏目录）",
              "default": false
            },
            "includeBuildArtifacts": {
              "type": "boolean",
              "description": "是否放行 target, node_modules, dist 等构建产物与依赖目录（如需验证编译产物时可开启），可选，默认 false。.git 任何情况下都排除。",
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
        // 解析是否放行构建产物与依赖目录（target/node_modules 等，.git 始终排除）
        boolean includeBuildArtifacts = Boolean.TRUE.equals(arguments.get("includeBuildArtifacts"));
        // 解析匹配模式：pattern 不含 / 时为浅层模式（仅直接子项），含 / 或 ** 时为递归模式
        boolean shallowMode = !patternVal.contains("/");
        Path rootPath;
        if (rootDirVal != null && !rootDirVal.isBlank()) {
            rootPath = workspacePathResolver.resolvePath(rootDirVal, agentContext);
        } else {
            rootPath = Paths.get(workspacePathResolver.getProjectBasePath(agentContext)).toAbsolutePath().normalize();
        }

        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            return new JSONObject().fluentPut("error", "搜索根目录不存在或不是目录: " + rootDirVal).toJSONString();
        }

        List<String> matchedFiles = new ArrayList<>();
        try {
            if (shallowMode) {
                // 浅层模式：仅遍历 rootDir 直接子项，glob 对条目名称做匹配，目录条目输出时带 / 后缀
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + patternVal);
                try (var stream = Files.list(rootPath)) {
                    stream.forEach(entry -> {
                        Path fileName = entry.getFileName();
                        boolean isDir = Files.isDirectory(entry);
                        if (matcher.matches(fileName)) {
                            matchedFiles.add(isDir ? fileName + "/" : fileName.toString());
                        }
                    });
                }
                matchedFiles.sort(String.CASE_INSENSITIVE_ORDER);
                if (matchedFiles.isEmpty()) {
                    return "[未匹配到任何文件：根目录 " + rootPath.getFileName() + " 的直接子项中没有符合 pattern '" + patternVal
                            + "' 的条目。可调整 pattern、使用包含 / 的递归 pattern，或指定其他 rootDir 重试]";
                }
                JSONArray result = new JSONArray();
                result.addAll(matchedFiles);
                if (matchedFiles.size() >= 100) {
                    result.add("... [匹配数量已达 100 个上限被截断] ...");
                }
                return result.toJSONString();
            }

            // 递归模式：walkFileTree 全路径 glob 匹配（pattern 已含 / 或 **，直接使用不拼前缀）
            String syntaxAndPattern = patternVal.startsWith("glob:") || patternVal.startsWith("regex:")
                    ? patternVal : "glob:" + patternVal;

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
                    // 永久排除目录（.git 等）：任何情况都跳过
                    if (FileSearchConstants.ALWAYS_EXCLUDE_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // 硬排除目录：纯产物/依赖/IDE 缓存，默认跳过防海量文件拖垮搜索；
                    // includeBuildArtifacts = true 时放行（如验证编译产物场景）
                    if (FileSearchConstants.HARD_EXCLUDE_DIRS.contains(dirName) && !includeBuildArtifacts) {
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

            // 空结果自描述契约：空匹配时返回明确文案而非空数组，
            // 避免落库后被回填层替换为通用占位导致模型无法区分"目录为空"与"链路异常"
            if (matchedFiles.isEmpty()) {
                return "[未匹配到任何文件：根目录 " + rootPath.getFileName() + " 下没有符合 pattern '" + patternVal
                        + "' 的文件。可调整 pattern 或指定其他 rootDir 重试]";
            }

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
