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
 * 按 Glob 表达式查找文件与目录的本地核心工具
 */
@Slf4j
@Component
public class FindFilesTool implements CuteTool {

    /**
     * 单次返回条目数的默认上限（与 schema 声明的 default 保持一致）
     */
    private static final int DEFAULT_MAX_RESULTS = 500;

    /**
     * 单次返回条目数的硬上限：钳制模型传入值（1-2000），防超大值撑爆上下文
     */
    private static final int MAX_RESULTS_LIMIT = 2000;

    @Resource
    private ProjectService projectService;

    @Override
    public String getRawName() {
        return ToolNames.FIND_FILES;
    }

    @Override
    public String getDescription() {
        return "在指定目录下按 Glob 表达式查找文件与目录条目，用于定位文件路径或列出目录结构（pattern 用 '*' 即可仅列一层目录）。"
                + "默认跳过版本库内部与产物依赖缓存目录（详见 includeExcludedDirs 参数）。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "pattern": {
              "type": "string",
              "description": "文件名匹配的 glob 表达式。不含 / 时（如 '*.java'、'*'）仅匹配 rootDir 的直接子项（浅层模式，目录条目带 / 后缀返回）；含 / 或 ** 时（如 'src/**/*.java'）递归匹配全路径。若首段为具体目录名（如 'node_modules/**'），该目录即使默认被排除也会被搜索"
            },
            "rootDir": {
              "type": "string",
              "description": "查找的根目录，可选，默认为当前项目根目录。支持项目相对路径（以项目根目录为基准）或绝对路径"
            },
            "includeExcludedDirs": {
              "type": "boolean",
              "description": "%s",
              "default": false
            },
            "maxResults": {
              "type": "integer",
              "description": "单次返回的最大条目数（可选，默认 500，上限 2000，达到即截断）",
              "default": 500
            }
          },
          "required": ["pattern"]
        }
        """.formatted(FileSearchConstants.EXCLUDE_DIRS_DESC);
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
        // 解析单次返回上限（钳制 1-2000，防超大值撑爆上下文；与 grep_search 同款机制）
        Integer maxResultsVal = args.getInt("maxResults");
        int maxResults = DEFAULT_MAX_RESULTS;
        if (maxResultsVal != null) {
            maxResults = Math.max(1, Math.min(MAX_RESULTS_LIMIT, maxResultsVal));
        }
        // 用于在 lambda 中引用的 effectively final 副本
        final int resultLimit = maxResults;
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
                // 排除口径与递归模式的「永久排除」对齐：.git 等版本库内部目录任何情况都不列出，
                // 产物依赖目录（target/node_modules 等）保留展示（浅层列目录属用户显式浏览意图）
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + patternVal);
                try (var stream = Files.list(rootPath)) {
                    stream.forEach(entry -> {
                        if (matchedFiles.size() >= resultLimit) {
                            return;
                        }
                        Path fileName = entry.getFileName();
                        boolean isDir = Files.isDirectory(entry);
                        // 永久排除目录（.git/.svn/.hg 等版本库内部）：与递归模式口径一致，任何情况都跳过
                        if (isDir && FileSearchConstants.ALWAYS_EXCLUDE_DIRS.contains(fileName.toString())) {
                            return;
                        }
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
                if (matchedFiles.size() >= resultLimit) {
                    result.add("... [匹配数量已达 " + resultLimit + " 个上限被截断] ...");
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
                    if (matchedFiles.size() >= resultLimit) {
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
            if (matchedFiles.size() >= resultLimit) {
                result.add("... [匹配数量已达 " + resultLimit + " 个上限被截断] ...");
            }
            return result.toJSONString();

        } catch (IOException e) {
            log.error("FindFilesTool 查找异常", e);
            return ToolResult.error("查找文件失败: " + e.getMessage());
        }
    }
}
