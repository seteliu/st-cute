package com.stioc.cute.tool.findtool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolResult;
import com.stioc.cute.platform.common.NativeCharsetKit;
import com.stioc.cute.tool.ToolNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
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
public class GrepSearchTool extends AbstractFindTool {


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
        return "在指定目录或文件的代码内容中全文检索关键字，返回匹配的文件路径、行号与行内容。"
                + "rootDir 可传目录路径（递归搜索）或单个文件路径（仅搜索该文件）。"
                + "默认为普通子串匹配，useRegex=true 时按正则表达式匹配。"
                + "默认跳过版本库内部与产物依赖缓存目录（详见 includeExcludedDirs 参数）；超过 2MB 的文件与二进制文件会被跳过，不参与检索。";
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
              "description": "搜索起始目录或单个文件路径，可选，默认为当前项目根目录。传入具体文件路径时仅在该文件内搜索。支持项目相对路径（以项目根目录为基准）或绝对路径"
            },
            "useRegex": {
              "type": "boolean",
              "description": "是否将 query 作为正则表达式进行匹配，可选，默认 false（普通子串匹配）",
              "default": false
            },
            "ignoreCase": {
              "type": "boolean",
              "description": "是否忽略大小写进行匹配，可选，默认 false（区分大小写）",
              "default": false
            },
            "includeExcludedDirs": {
              "type": "boolean",
              "description": "%s",
              "default": false
            },
            "maxResults": {
              "type": "integer",
              "description": "单次返回的最大匹配条数（可选，默认 50，上限 500，达到即截断）",
              "default": 50
            }
          },
          "required": ["query"]
        }
        """.formatted(FileSearchConstants.EXCLUDE_DIRS_DESC);
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        GrepSearchArgs grepArgs = GrepSearchArgs.from(arguments);
        String query = grepArgs.query();
        if (query == null || query.isBlank()) {
            return ToolResult.error("参数 'query' 不能为空。");
        }

        // 解析是否启用正则模式
        boolean useRegex = grepArgs.useRegex();
        // 解析是否忽略大小写
        boolean ignoreCase = grepArgs.ignoreCase();

        // 解析放行开关：是否放行常规排除清单中的产物依赖目录
        boolean includeExcludedDirs = grepArgs.includeExcludedDirs();

        // 用于在匿名内部类中引用的 effectively final 副本
        final int resultLimit = grepArgs.maxResults();

        // 预编译正则 Pattern（正则模式或忽略大小写的普通子串模式）
        Pattern compiled = null;
        if (useRegex) {
            try {
                int flags = ignoreCase ? (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : 0;
                compiled = Pattern.compile(query, flags);
            } catch (PatternSyntaxException e) {
                return ToolResult.error("正则表达式语法有误: " + e.getMessage());
            }
        } else if (ignoreCase) {
            // 普通子串忽略大小写：通过 Pattern.quote 编译为不区分大小写正则匹配
            compiled = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
        // 用于在匿名内部类中引用的 effectively final 副本
        Pattern regexPattern = compiled;

        String rootDirVal = grepArgs.rootDir();
        Path targetPath = resolveSearchRoot(rootDirVal, agentContext);

        // 解析当前项目物理根路径：用于规范化匹配结果中的 relativePath，让下游 read_file/edit_file 工具链可直接无缝消费
        final Path effectiveProjectRoot = getProjectRoot(agentContext);

        // 纵深防御：rootDir 沙箱复核（权限层已纳入 rootDir 参数安检，此处工具层兜底二次校验）
        String sandboxReject = checkSandbox(targetPath, agentContext, "rootDir");
        if (sandboxReject != null) {
            return sandboxReject;
        }

        if (!Files.exists(targetPath)) {
            return ToolResult.error("搜索路径不存在: " + rootDirVal);
        }

        List<String> results = new ArrayList<>();

        try {
            // 如果传入的是单个文件，直接在该文件内搜索（体量超限防御，二进制检测在 searchInFile 首部内联完成）
            if (Files.isRegularFile(targetPath)) {
                if (Files.size(targetPath) <= MAX_FILE_SIZE) {
                    Path fileRoot = targetPath.getParent();
                    searchInFile(targetPath, fileRoot, effectiveProjectRoot, query, regexPattern, results, resultLimit);
                }
            } else {
                // 目录模式：递归遍历
                Path finalRootPath = targetPath;
                Files.walkFileTree(targetPath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        // 遍历根目录本身不跳过（用户显式指定的搜索入口）
                        if (dir.equals(finalRootPath)) {
                            return FileVisitResult.CONTINUE;
                        }
                        String dirName = dir.getFileName().toString();
                        return SearchDirectoryFilter.evaluatePreVisit(dirName, includeExcludedDirs, null);
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        // 过滤超大文件（二进制检测已下沉至 searchInFile 单次读入首部探测，免去重复打开文件句柄）
                        if (attrs.size() <= MAX_FILE_SIZE) {
                            searchInFile(file, finalRootPath, effectiveProjectRoot, query, regexPattern, results, resultLimit);
                        }
                        if (results.size() >= resultLimit) {
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return handleVisitFileFailed(file, exc);
                    }
                });
            }

            log.info("GrepSearchTool 检索完成, 找到 {} 处匹配", results.size());

            if (results.isEmpty()) {
                // 正则误用防御：子串模式下 query 含典型正则元字符（| ^ $ ( ) [ ] + 等）时，
                // 用户很可能想用正则却忘了传 useRegex=true（如 "a|b" 被当字面整串搜索静默无结果），
                // 附加提示避免误判"目标不存在"。
                // 刻意不检测反斜杠：Windows 路径查询普遍含 \，纳入会大面积误报提示
                String regexHint = null;
                if (!useRegex && query.matches(".*[|^$()\\[\\]{}+?].*")) {
                    regexHint = "（提示：query 中包含正则元字符，当前为字面子串匹配整串搜索；如需按正则表达式匹配请传 useRegex=true）";
                }
                JSONObject empty = new JSONObject().fluentPut("message", "未检索到匹配的内容。");
                if (regexHint != null) {
                    empty.fluentPut("hint", regexHint);
                }
                return empty.toJSONString();
            }

            StringBuilder sb = new StringBuilder("已找到以下匹配行:\n");
            for (String item : results) {
                sb.append(item).append("\n");
            }
            if (results.size() >= resultLimit) {
                sb.append("... [匹配超过 ").append(resultLimit).append(" 个被截断] ...");
            }
            return sb.toString();

        } catch (IOException e) {
            log.error("GrepSearchTool 检索异常", e);
            return ToolResult.error("全文检索失败: " + e.getMessage());
        }
    }

    private void searchInFile(Path file, Path searchRoot, Path projectRoot, String query, Pattern regexPattern, List<String> results, int resultLimit) {
        try (InputStream raw = new FileInputStream(file.toFile())) {
            // 编码自适应读取与首部二进制检测（合并为单次读入）：
            // 先缓冲全部字节，前 1024 字节探测若含 0x00 则判定为二进制文件直接跳过，免除前置单独打开文件流的冗余 IO
            byte[] rawBytes = raw.readAllBytes();
            int probeLen = Math.min(rawBytes.length, 1024);
            for (int i = 0; i < probeLen; i++) {
                if (rawBytes[i] == 0) {
                    return;
                }
            }
            Charset charset = NativeCharsetKit.detectCharset(rawBytes, rawBytes.length);
            BufferedReader reader = new BufferedReader(new StringReader(new String(rawBytes, charset)));
            String line;
            int lineNumber = 0;
            // 相对路径基准策略：若搜索文件落在项目物理根目录下，统一计算相对于项目根的路径，
            // 确保输出的路径可直接被 downstream 的 read_file / edit_file 无缝消费；否则相对于搜索入口根目录
            String relativePath = formatRelativePath(file, searchRoot, projectRoot);
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                // regexPattern 非 null 走正则匹配（含 ignoreCase 的 Pattern.quote 模式），否则走普通子串匹配
                boolean matched = (regexPattern != null)
                        ? regexPattern.matcher(line).find()
                        : line.contains(query);
                if (matched) {
                    // 裁剪行尾空白与换行，保留前导缩进以感知 Python/YAML 等缩进敏感代码层级；超长行截断防撑爆上下文
                    String trimmed = line.stripTrailing();
                    if (trimmed.length() > MAX_MATCH_LINE_LENGTH) {
                        trimmed = trimmed.substring(0, MAX_MATCH_LINE_LENGTH)
                                + "... [已截断, 原始长度: " + trimmed.length() + " 字符]";
                    }
                    results.add(String.format("[%s:%d] %s", relativePath, lineNumber, trimmed));
                    if (results.size() >= resultLimit) {
                        break;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("无法检索文件内容: {}, 异常: {}", file, e.getMessage());
        }
    }
}
