package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONArray;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolResult;
import com.stioc.cute.tool.types.FileSearchConstants;
import com.stioc.cute.tool.ToolNames;
import com.stioc.cute.project.ProjectService;
import com.stioc.cute.engine.loop.core.AgentContext;
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

    /**
     * 结果数量上限：达到即停止收集并标记截断
     */
    private static final int MAX_RESULTS = 500;

    @Resource
    private ProjectService projectService;

    @Override
    public String getRawName() {
        return ToolNames.LIST_DIR;
    }

    @Override
    public String getDescription() {
        return "在指定目录下按 Glob 表达式查找匹配的条目列表。"
                + "匹配模式按 pattern 形态自动区分：不含 / 时为浅层模式（仅匹配 rootDir 直接子项，目录条目以 / 结尾返回，可用来仅列目录）；含 / 或 ** 时为递归模式（按 glob 全路径匹配）。"
                + "已自动忽略 .git 等版本库内部目录与 target, node_modules 等产物依赖目录（includeExcludedDirs=true 可放行产物类），显式列入 pattern 首段或 rootDir 的目录除外，其余目录（含点开头目录）正常搜索。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "pattern": {
              "type": "string",
              "description": "文件名匹配的 glob 表达式：不含 / 时（如 '*.java'、'*config*'）为浅层模式仅匹配直接子项；含 / 或 ** 时（如 '**/*.vue'、'src/main/**/*.java'）为递归模式。pattern 首段（或 {a,b} 首分支首段）显式点名的目录不走排除过滤"
            },
            "rootDir": {
              "type": "string",
              "description": "查找的根目录相对或绝对路径，可选，默认当前工作目录。rootDir 自身不走排除过滤"
            },
            "includeExcludedDirs": {
              "type": "boolean",
              "description": "是否放行常规排除清单（target, node_modules, .idea 等产物依赖缓存目录）。可选，默认 false（默认跳过）。.git 等版本库内部目录任何情况都排除",
              "default": false
            }
          },
          "required": ["pattern"]
        }
        """;
    }

    @Override
    public ToolAccessLevel getAccessLevel() {
        // 只读：无外部副作用，支持并发调度
        return ToolAccessLevel.READ;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        ToolArgs args = ToolArgs.of(arguments);
        String patternVal = args.getString("pattern");
        if (patternVal == null || patternVal.isBlank()) {
            return ToolResult.error("参数 'pattern' 不能为空。");
        }

        String rootDirVal = args.getStringTrimmed("rootDir");
        // 解析放行开关：是否放行常规排除清单中的产物依赖目录
        boolean includeExcludedDirs = Boolean.TRUE.equals(args.getBoolean("includeExcludedDirs"));
        // 解析匹配模式：pattern 不含 / 时为浅层模式（仅直接子项），含 / 或 ** 时为递归模式
        boolean shallowMode = !patternVal.contains("/");
        Path rootPath;
        if (rootDirVal != null && !rootDirVal.isBlank()) {
            rootPath = projectService.resolvePath(rootDirVal, agentContext);
        } else {
            rootPath = Paths.get(projectService.getProjectBasePath(agentContext)).toAbsolutePath().normalize();
        }

        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            return ToolResult.error("搜索根目录不存在或不是目录: " + rootDirVal);
        }

        List<String> matchedFiles = new ArrayList<>();
        try {
            if (shallowMode) {
                // 浅层模式：仅遍历 rootDir 直接子项，glob 对条目名称做匹配，目录条目输出时带 / 后缀。
                // 不套排除过滤：浅层只看一层，用户列目录时理应看到全部直接子项
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + patternVal);
                try (var stream = Files.list(rootPath)) {
                    stream.forEach(entry -> {
                        if (matchedFiles.size() >= MAX_RESULTS) {
                            return;
                        }
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
                if (matchedFiles.size() >= MAX_RESULTS) {
                    result.add("... [匹配数量已达 " + MAX_RESULTS + " 个上限被截断] ...");
                }
                return result.toJSONString();
            }

            // 递归模式：walkFileTree 全路径 glob 匹配（pattern 已含 / 或 **，直接使用不拼前缀）
            String syntaxAndPattern = patternVal.startsWith("glob:") || patternVal.startsWith("regex:")
                    ? patternVal : "glob:" + patternVal;

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher(syntaxAndPattern);
            Path finalRootPath = rootPath;

            // pattern 首段意图识别：显式点名的目录不走排除过滤。
            // 如 ".ai-work/**" 点名 .ai-work、"{.ai-work,.agents}/**" 取首分支点名 .ai-work；
            // 含通配符（* 或 **）的段视为未点名具体目录
            String head = null;
            int slashIdx = patternVal.indexOf('/');
            if (slashIdx > 0) {
                head = patternVal.substring(0, slashIdx);
                // {a,b} 形态取首分支首段
                if (head.startsWith("{") && head.contains(",")) {
                    head = head.substring(1, head.indexOf(','));
                }
                if (head.isBlank() || head.contains("*")) {
                    head = null;
                }
            }
            final String firstSegment = head;

            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // 遍历根目录本身不跳过（用户显式指定的搜索入口）
                    if (dir.equals(finalRootPath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String dirName = dir.getFileName().toString();
                    // 显式意图优先：pattern 首段（或 {a,b} 首分支首段）点名的目录，不走排除过滤
                    if (dirName.equals(firstSegment)) {
                        return FileVisitResult.CONTINUE;
                    }
                    // 永久排除目录（.git/.svn/.hg 等版本库内部）：任何情况都跳过
                    if (FileSearchConstants.ALWAYS_EXCLUDE_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // 常规排除清单：纯产物/依赖/IDE 缓存，默认跳过防海量文件拖垮搜索；
                    // includeExcludedDirs = true 时放行（如验证编译产物场景）。
                    // 未列入清单的目录（含 .ai-work/.agents 等点开头资产目录）正常遍历，不做点前缀判断
                    if (FileSearchConstants.EXCLUDE_DIRS.contains(dirName) && !includeExcludedDirs) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matcher.matches(file) || matcher.matches(finalRootPath.relativize(file))) {
                        matchedFiles.add(finalRootPath.relativize(file).toString().replace("\\", "/"));
                    }
                    if (matchedFiles.size() >= MAX_RESULTS) {
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
            if (matchedFiles.size() >= MAX_RESULTS) {
                result.add("... [匹配数量已达 " + MAX_RESULTS + " 个上限被截断] ...");
            }
            return result.toJSONString();

        } catch (IOException e) {
            log.error("FindFilesTool 查找异常", e);
            return ToolResult.error("查找文件失败: " + e.getMessage());
        }
    }
}
