package com.stioc.cute.tool.findtool;

import com.alibaba.fastjson2.JSONArray;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolResult;
import com.stioc.cute.tool.ToolNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.PatternSyntaxException;

/**
 * 按 Glob 表达式查找文件与目录的本地核心工具
 */
@Slf4j
@Component
public class FindFilesTool extends AbstractFindTool {

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
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        FindFilesArgs findArgs = FindFilesArgs.from(arguments);
        String patternVal = findArgs.pattern();
        if (patternVal == null || patternVal.isBlank()) {
            return ToolResult.error("参数 'pattern' 不能为空。");
        }

        String rootDirVal = findArgs.rootDir();
        boolean includeExcludedDirs = findArgs.includeExcludedDirs();
        final int resultLimit = findArgs.maxResults();
        String normalizedPattern = findArgs.normalizedPattern();
        boolean shallowMode = findArgs.shallowMode();
        Path rootPath = resolveSearchRoot(rootDirVal, agentContext);

        // 纵深防御：rootDir 沙箱复核（权限层已纳入 rootDir 参数安检，此处工具层兜底二次校验）
        String sandboxReject = checkSandbox(rootPath, agentContext, "rootDir");
        if (sandboxReject != null) {
            return sandboxReject;
        }

        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            return ToolResult.error("搜索根目录不存在或不是目录: " + rootDirVal);
        }

        // glob 表达式编译：非法 pattern（如未闭合的 [ 或 {）会抛 PatternSyntaxException，
        // 属 RuntimeException 不会被下方的 IOException 分支捕获而穿透工具层，故在此显式拦截并给出友好提示
        List<PathMatcher> matchers;
        try {
            matchers = compileMatchers(normalizedPattern, findArgs.candidatePatterns(), shallowMode);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("Glob 表达式语法有误: " + e.getMessage()
                    + "。请检查中括号与花括号是否闭合，或改用更简单的通配形式（如 'src/**' 或 '*.java'）。");
        }

        List<String> matchedFiles = new ArrayList<>();
        AtomicBoolean truncated = new AtomicBoolean(false);
        try {
            if (shallowMode) {
                // 浅层模式：仅遍历 rootDir 直接子项，glob 对条目名称做匹配，目录条目输出时带 / 后缀。
                // 排除口径与递归模式的「永久排除」对齐：.git 等版本库内部目录任何情况都不列出，
                // 产物依赖目录（target/node_modules 等）保留展示（浅层列目录属用户显式浏览意图）
                PathMatcher matcher = matchers.getFirst();
                try (var stream = Files.list(rootPath)) {
                    stream.forEach(entry -> {
                        if (matchedFiles.size() >= resultLimit) {
                            truncated.set(true);
                            return;
                        }
                        Path fileName = entry.getFileName();
                        boolean isDir = Files.isDirectory(entry);
                        // 永久排除目录（.git/.svn/.hg 等版本库内部）：与递归模式口径一致，任何情况都跳过
                        if (isDir && SearchDirectoryFilter.isAlwaysExcluded(fileName.toString())) {
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
                            + "' 的条目。可调整 pattern、使用包含 / 的递归 pattern（如 'src/**/*.java'，当前 pattern 不含 / 属浅层模式仅查直接子项），或指定其他 rootDir 重试]";
                }
                JSONArray result = new JSONArray();
                result.addAll(matchedFiles);
                if (truncated.get()) {
                    result.add("... [匹配数量已达 " + resultLimit + " 个上限被截断] ...");
                }
                return result.toJSONString();
            }

            Path finalRootPath = rootPath;

            // pattern 首段意图识别：显式点名的目录不走排除过滤。
            // 如 ".ai-work/**" 点名 .ai-work、"{.ai-work,.agents}/**" 取首分支点名 .ai-work；
            // 含通配符（* 或 **）的段视为未点名具体目录
            String head = null;
            int slashIdx = normalizedPattern.indexOf('/');
            if (slashIdx > 0) {
                head = normalizedPattern.substring(0, slashIdx);
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
                    FileVisitResult skipDecision = SearchDirectoryFilter.evaluatePreVisit(dirName, includeExcludedDirs, firstSegment);
                    if (skipDecision == FileVisitResult.SKIP_SUBTREE) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    // 递归模式补充目录条目匹配：若目录符合 pattern（相对路径或绝对路径），收录目录路径（带 / 后缀与浅层模式对齐）
                    Path relativePath = finalRootPath.relativize(dir);
                    if (matchesAny(matchers, dir, relativePath)) {
                        if (matchedFiles.size() >= resultLimit) {
                            truncated.set(true);
                            return FileVisitResult.TERMINATE;
                        }
                        matchedFiles.add(relativePath.toString().replace("\\", "/") + "/");
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relativePath = finalRootPath.relativize(file);
                    if (matchesAny(matchers, file, relativePath)) {
                        if (matchedFiles.size() >= resultLimit) {
                            truncated.set(true);
                            return FileVisitResult.TERMINATE;
                        }
                        matchedFiles.add(relativePath.toString().replace("\\", "/"));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return handleVisitFileFailed(file, exc);
                }
            });

            log.info("FindFilesTool 执行成功: {}, 匹配到 {} 个文件", patternVal, matchedFiles.size());

            // 空结果自描述契约：空匹配时返回明确文案而非空数组，
            // 避免落库后被回填层替换为通用占位导致模型无法区分"目录为空"与"链路异常"
            if (matchedFiles.isEmpty()) {
                return "[未匹配到任何文件：根目录 " + rootPath.getFileName() + " 下没有符合 pattern '" + patternVal
                        + "' 的文件。可调整 pattern 或指定其他 rootDir 重试。"
                        + "（注意：pattern 不含 / 时为浅层模式，仅匹配根目录直接子项；如需递归搜索全路径请使用含 / 或 ** 的 pattern，如 'src/**/*.java'）]";
            }

            JSONArray result = new JSONArray();
            result.addAll(matchedFiles);
            if (truncated.get()) {
                result.add("... [匹配数量已达 " + resultLimit + " 个上限被截断] ...");
            }
            return result.toJSONString();

        } catch (IOException e) {
            log.error("FindFilesTool 查找异常", e);
            return ToolResult.error("查找文件失败: " + e.getMessage());
        }
    }

    /**
     * 编译 glob 候选列表为 PathMatcher 列表。
     * <p>
     * 候选由 {@link FindFilesArgs} 依据 {@code **}{@code /} 展开而来（覆盖零层目录情形），
     * 任一候选命中即视为匹配。显式 {@code glob:} / {@code regex:} 前缀形态不做拼接，原样编译。
     * </p>
     *
     * @param normalizedPattern 归一化后的 pattern（用于判定是否已带语法前缀）
     * @param candidates        候选 pattern 列表（展开后）
     * @param shallowMode       是否为浅层模式（浅层仅对文件名匹配，取首候选即可）
     * @return 编译后的匹配器列表，顺序与候选一致
     * @throws PatternSyntaxException pattern 语法非法时抛出，由调用方转为工具错误返回
     */
    private List<PathMatcher> compileMatchers(String normalizedPattern, List<String> candidates,
                                              boolean shallowMode) {
        boolean explicitSyntax = normalizedPattern.startsWith("glob:") || normalizedPattern.startsWith("regex:");
        List<String> effective = (candidates == null || candidates.isEmpty())
                ? List.of(normalizedPattern)
                : candidates;
        // 浅层模式只在直接子项名上匹配，展开的候选对单层名无意义，取首项避免重复判定
        if (shallowMode) {
            effective = List.of(effective.getFirst());
        }

        List<PathMatcher> compiled = new ArrayList<>(effective.size());
        for (String candidate : effective) {
            String syntaxAndPattern = explicitSyntax || candidate.startsWith("glob:") || candidate.startsWith("regex:")
                    ? candidate
                    : "glob:" + candidate;
            compiled.add(FileSystems.getDefault().getPathMatcher(syntaxAndPattern));
        }
        return compiled;
    }

    /**
     * 判定条目是否命中任一候选匹配器（绝对路径与相对路径各试一次，与既有语义保持一致）
     *
     * @param matchers     候选匹配器列表
     * @param absolutePath 条目的绝对路径
     * @param relativePath 条目相对于搜索根的路径
     * @return true 表示任一候选命中
     */
    private boolean matchesAny(List<PathMatcher> matchers, Path absolutePath, Path relativePath) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(absolutePath) || matcher.matches(relativePath)) {
                return true;
            }
        }
        return false;
    }
}
