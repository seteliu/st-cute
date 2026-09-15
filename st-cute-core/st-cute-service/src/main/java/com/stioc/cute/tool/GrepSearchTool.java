package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONObject;
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

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import com.stioc.cute.platform.common.NativeCharsetKit;
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
    private ProjectService projectService;

    /**
     * 限制检索的最大单文件大小：2MB
     */
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024L;

    /**
     * 匹配行内容的最大输出长度（字符数），超过此长度的行将被截断，防止压缩JSON等超长行撑爆上下文
     */
    private static final int MAX_MATCH_LINE_LENGTH = 1000;

    @Override
    public String getRawName() {
        return ToolNames.GREP_SEARCH;
    }

    @Override
    public String getDescription() {
        return "在指定目录或文件的代码内容中全文检索关键字，返回匹配的行号与行内容信息。rootDir 可传目录路径（递归搜索）或单个文件路径（仅搜索该文件）。默认为普通子串匹配，若 useRegex 为 true 则将 query 视为正则表达式进行匹配。已自动忽略 .git 等版本库内部目录与 target, node_modules 等产物依赖目录（includeExcludedDirs=true 可放行产物类），rootDir 显式指定的目录除外，其余目录（含点开头目录）正常搜索。";
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
              "description": "搜索起始目录或单个文件路径，可选，默认当前项目根目录。传入具体文件路径时仅在该文件内搜索。rootDir 自身不走排除过滤"
            },
            "useRegex": {
              "type": "boolean",
              "description": "是否将 query 作为正则表达式进行匹配，可选，默认 false（普通子串匹配）",
              "default": false
            },
            "includeExcludedDirs": {
              "type": "boolean",
              "description": "是否放行常规排除清单（target, node_modules, .idea 等产物依赖缓存目录）。可选，默认 false（默认跳过）。.git 等版本库内部目录任何情况都排除",
              "default": false
            }
          },
          "required": ["query"]
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
        String query = args.getString("query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("参数 'query' 不能为空。");
        }

        // 解析是否启用正则模式
        boolean useRegex = Boolean.TRUE.equals(args.getBoolean("useRegex"));

        // 解析放行开关：是否放行常规排除清单中的产物依赖目录
        boolean includeExcludedDirs = Boolean.TRUE.equals(args.getBoolean("includeExcludedDirs"));

        // 预编译正则 Pattern（仅在正则模式下生效，普通子串模式为 null）
        Pattern compiled = null;
        if (useRegex) {
            try {
                compiled = Pattern.compile(query);
            } catch (PatternSyntaxException e) {
                return ToolResult.error("正则表达式语法有误: " + e.getMessage());
            }
        }
        // 用于在匿名内部类中引用的 effectively final 副本
        Pattern regexPattern = compiled;

        String rootDirVal = args.getStringTrimmed("rootDir");
        Path targetPath;
        if (rootDirVal != null && !rootDirVal.isBlank()) {
            targetPath = projectService.resolvePath(rootDirVal, agentContext);
        } else {
            targetPath = Paths.get(projectService.getProjectBasePath(agentContext)).toAbsolutePath().normalize();
        }

        if (!Files.exists(targetPath)) {
            return ToolResult.error("搜索路径不存在: " + rootDirVal);
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
            return ToolResult.error("全文检索失败: " + e.getMessage());
        }
    }

    private void searchInFile(Path file, Path root, String query, Pattern regexPattern, List<String> results) {
        try (InputStream raw = new FileInputStream(file.toFile())) {
            // 编码自适应读取：缓冲全部字节后按 UTF-8 严格探测，失败回退系统原生编码（中文 Windows 为 GBK）。
            // 修复：原先强制 UTF-8 读取，GBK 编码文件的中文字节被解码为乱码，导致中文关键字静默失配
            //
            // 采样策略说明：此处刻意直连 detectCharset 内核并传入全文字节，而非走 detectFileMeta 的 8KB 采样——
            // 搜索本就必须全文读入，全文严格校验零额外开销且无"深处非法字节未被采样命中"的死角，判定最准；
            // 本工具为读后即弃链路，不参与 read/modify/write 的写回编码一致性契约，无需 BOM/EOL 元数据，
            // 请勿为"统一"而改用 detectFileMeta（UTF-16 类文件已由二进制探测拦截兜底，BOM 前置检测亦无必要）
            byte[] rawBytes = raw.readAllBytes();
            Charset charset = NativeCharsetKit.detectCharset(rawBytes, rawBytes.length);
            BufferedReader reader = new BufferedReader(new StringReader(new String(rawBytes, charset)));
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
