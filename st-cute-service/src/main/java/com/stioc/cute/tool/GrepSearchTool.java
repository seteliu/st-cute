package com.stioc.cute.tool;

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

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 局部/全局文本文件正文搜索匹配本地核心工具
 */
@Slf4j
@Component
public class GrepSearchTool implements CuteTool {

    @Resource
    private WorkspacePathResolver workspacePathResolver;

    /**
     * 限制检索的最大单文件大小：2MB
     */
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024L;

    /**
     * 匹配行内容的最大输出长度（字符数），超过此长度的行将被截断，防止压缩JSON等超长行撑爆上下文
     */
    private static final int MAX_MATCH_LINE_LENGTH = 1000;

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getName() {
        return ToolNames.GREP_SEARCH;
    }

    @Override
    public String getDescription() {
        return "在指定目录或文件的代码内容中全文检索关键字，返回匹配的行号与行内容信息。rootDir 可传目录路径（递归搜索）或单个文件路径（仅搜索该文件）。默认为普通子串匹配，若 useRegex 为 true 则将 query 视为正则表达式进行匹配。默认跳过以点(.)开头的隐藏目录（含 .agents 等用户配置目录），如需搜索隐藏目录可设置 includeHidden 为 true。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "要检索的文本内容关键字或正则表达式"
            },
            "rootDir": {
              "type": "string",
              "description": "搜索起始目录或单个文件路径，可选，默认当前项目根目录。传入具体文件路径时仅在该文件内搜索。"
            },
            "useRegex": {
              "type": "boolean",
              "description": "是否将 query 作为正则表达式进行匹配，可选，默认 false（普通子串匹配）",
              "default": false
            },
            "includeHidden": {
              "type": "boolean",
              "description": "是否搜索以点(.)开头的隐藏目录（如 .git, .idea, AI临时目录等），可选，默认 false（默认跳过隐藏目录）",
              "default": false
            }
          },
          "required": ["query"]
        }
        """;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        String query = (String) arguments.get("query");
        if (query == null || query.isBlank()) {
            return new JSONObject().fluentPut("error", "参数 'query' 不能为空。").toJSONString();
        }

        // 解析是否启用正则模式
        boolean useRegex = Boolean.TRUE.equals(arguments.get("useRegex"));

        // 解析是否搜索以点开头的隐藏目录
        boolean includeHidden = Boolean.TRUE.equals(arguments.get("includeHidden"));

        // 预编译正则 Pattern（仅在正则模式下生效，普通子串模式为 null）
        Pattern compiled = null;
        if (useRegex) {
            try {
                compiled = Pattern.compile(query);
            } catch (PatternSyntaxException e) {
                return new JSONObject().fluentPut("error", "正则表达式语法有误: " + e.getMessage()).toJSONString();
            }
        }
        // 用于在匿名内部类中引用的 effectively final 副本
        Pattern regexPattern = compiled;

        String rootDirVal = (String) arguments.get("rootDir");
        Path targetPath;
        if (rootDirVal != null && !rootDirVal.isBlank()) {
            targetPath = workspacePathResolver.resolvePath(rootDirVal, agentContext);
        } else {
            targetPath = Paths.get(workspacePathResolver.getProjectBasePath(agentContext)).toAbsolutePath().normalize();
        }

        if (!Files.exists(targetPath)) {
            return new JSONObject().fluentPut("error", "搜索路径不存在: " + rootDirVal).toJSONString();
        }

        List<String> results = new ArrayList<>();

        try {
            // 如果传入的是单个文件，直接在该文件内搜索（需先做大小与二进制校验）
            if (Files.isRegularFile(targetPath)) {
                if (Files.size(targetPath) <= MAX_FILE_SIZE && !isBinaryFile(targetPath)) {
                    Path fileRoot = targetPath.getParent();
                    searchInFile(targetPath, fileRoot, query, regexPattern, results);
                }
            } else {
                // 目录模式：递归遍历
                Path finalRootPath = targetPath;
                Files.walkFileTree(targetPath, new SimpleFileVisitor<>() {
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
                        // 过滤超大文件与二进制文件
                        if (attrs.size() <= MAX_FILE_SIZE && !isBinaryFile(file)) {
                            searchInFile(file, finalRootPath, query, regexPattern, results);
                        }
                        if (results.size() >= 50) {
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            log.info("GrepSearchTool 检索完成, 找到 {} 处匹配", results.size());

            if (results.isEmpty()) {
                return new JSONObject().fluentPut("message", "未检索到匹配的内容。").toJSONString();
            }

            StringBuilder sb = new StringBuilder("已找到以下匹配行:\n");
            for (String item : results) {
                sb.append(item).append("\n");
            }
            if (results.size() >= 50) {
                sb.append("... [匹配超过 50 个被截断] ...");
            }
            return sb.toString();

        } catch (IOException e) {
            log.error("GrepSearchTool 检索异常", e);
            return new JSONObject().fluentPut("error", "全文检索失败: " + e.getMessage()).toJSONString();
        }
    }

    private void searchInFile(Path file, Path root, String query, Pattern regexPattern, List<String> results) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file.toFile()), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            String relativePath = root.relativize(file).toString().replace("\\", "/");
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                // regexPattern 非 null 走正则匹配，否则走普通子串匹配
                boolean matched = (regexPattern != null)
                        ? regexPattern.matcher(line).find()
                        : line.contains(query);
                if (matched) {
                    // 超长行截断，防止压缩JSON等撑爆上下文
                    String trimmed = line.trim();
                    if (trimmed.length() > MAX_MATCH_LINE_LENGTH) {
                        trimmed = trimmed.substring(0, MAX_MATCH_LINE_LENGTH)
                                + "... [已截断, 原始长度: " + trimmed.length() + " 字符]";
                    }
                    results.add(String.format("[%s:%d] %s", relativePath, lineNumber, trimmed));
                    if (results.size() >= 50) {
                        break;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("无法检索文件内容: {}, 异常: {}", file, e.getMessage());
        }
    }

    /**
     * 通过读取文件前 1024 字节探测其是否为二进制文件。
     * 只要前 1024 字节中包含零字节 0x00，则判定为二进制文件。
     */
    private boolean isBinaryFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024];
            int read = in.read(buffer);
            for (int i = 0; i < read; i++) {
                if (buffer[i] == 0) {
                    return true;
                }
            }
        } catch (IOException e) {
            log.warn("无法探测文件类型，默认视为二进制跳过: {}, 异常: {}", file, e.getMessage());
            return true;
        }
        return false;
    }
}
