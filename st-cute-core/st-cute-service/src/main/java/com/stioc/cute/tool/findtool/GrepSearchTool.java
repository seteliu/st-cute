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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 局部/全局文本文件正文搜索匹配本地核心工具
 */
@Slf4j
@Component
public class GrepSearchTool extends AbstractFindTool {


    /**
     * 限制检索的最大单文件大小：10MB
     */
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024L;

    /**
     * 二进制探测与编码判定的预读字节数：流式读取时先缓冲该字节数做零字节探测，
     * 探测后复位再按行读取。8KB 与 ReadFileTool 的采样口径一致——样本越大，
     * 「前缀代表整体」的编码判定越可靠（如文件头 1KB 恰为纯 ASCII、主体是 GBK 中文的老项目源码，
     * 1KB 样本会误判为 UTF-8 导致中文关键字搜不到；8KB 显著降低该概率）。
     */
    private static final int BINARY_PROBE_BYTES = 8 * 1024;

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
                + "默认跳过版本库内部与产物依赖缓存目录（详见 includeExcludedDirs 参数）；超过 10MB 的文件与二进制文件会被跳过，不参与检索。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "pattern": {
              "type": "string",
              "description": "要检索的文本内容关键字或正则表达式"
            },
            "rootDir": {
              "type": "string",
              "description": "搜索起始目录或单个文件路径，可选，默认为当前项目根目录。传入具体文件路径时仅在该文件内搜索。支持项目相对路径（以项目根目录为基准）或绝对路径"
            },
            "useRegex": {
              "type": "boolean",
              "description": "是否将 pattern 作为正则表达式进行匹配，可选，默认 false（普通子串匹配）",
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
          "required": ["pattern"]
        }
        """.formatted(FileSearchConstants.EXCLUDE_DIRS_DESC);
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        GrepSearchArgs grepArgs = GrepSearchArgs.from(arguments);
        String pattern = grepArgs.pattern();
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.error("参数 'pattern' 不能为空。");
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
                compiled = Pattern.compile(pattern, flags);
            } catch (PatternSyntaxException e) {
                return ToolResult.error("正则表达式语法有误: " + e.getMessage());
            }
        } else if (ignoreCase) {
            // 普通子串忽略大小写：通过 Pattern.quote 编译为不区分大小写正则匹配
            compiled = Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
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
        // 跳过统计：记录被防御性跳过的超大/二进制文件数，空结果时反馈，帮助区分「无匹配」与「文件被跳过」
        SkipStats skipStats = new SkipStats();

        try {
            // 如果传入的是单个文件，直接在该文件内搜索（体量超限防御，二进制检测在 searchInFile 首部内联完成）
            if (Files.isRegularFile(targetPath)) {
                if (Files.size(targetPath) <= MAX_FILE_SIZE) {
                    Path fileRoot = targetPath.getParent();
                    searchInFile(targetPath, fileRoot, effectiveProjectRoot, pattern, regexPattern, results, resultLimit, skipStats);
                } else {
                    skipStats.addOversized();
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
                            searchInFile(file, finalRootPath, effectiveProjectRoot, pattern, regexPattern, results, resultLimit, skipStats);
                        } else {
                            skipStats.addOversized();
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
                if (!useRegex && pattern.matches(".*[|^$()\\[\\]{}+?].*")) {
                    regexHint = "（提示：pattern 中包含正则元字符，当前为字面子串匹配整串搜索；如需按正则表达式匹配请传 useRegex=true）";
                }
                JSONObject empty = new JSONObject().fluentPut("message", "未检索到匹配的内容。");
                if (regexHint != null) {
                    empty.fluentPut("hint", regexHint);
                }
                // 存在被防御性跳过的文件时附跳过统计，消除「没匹配到」与「文件根本没参与搜索」的归因歧义
                if (skipStats.any()) {
                    empty.fluentPut("skipped", skipStats.describe());
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

    private void searchInFile(Path file, Path searchRoot, Path projectRoot, String pattern, Pattern regexPattern, List<String> results, int resultLimit, SkipStats skipStats) {
        try (InputStream raw = new FileInputStream(file.toFile())) {
            // 流式读取替代全量载入（10MB 上限下全量 readAllBytes 的峰值内存约 30-40MB，
            // 大文件检索时既浪费堆内存又拖慢首次匹配响应）：
            // 用 BufferedInputStream + mark/reset 先预读首部 8KB 做二进制探测与编码判定，
            // 探测后复位到流起点再按行解码读取——探测与读取共用同一文件句柄，仅一次打开文件。
            BufferedInputStream bis = new BufferedInputStream(raw, 8192);
            bis.mark(BINARY_PROBE_BYTES + 1);
            byte[] probe = new byte[BINARY_PROBE_BYTES];
            int probeLen = 0;
            while (probeLen < BINARY_PROBE_BYTES) {
                int read = bis.read(probe, probeLen, BINARY_PROBE_BYTES - probeLen);
                if (read == -1) {
                    break;
                }
                probeLen += read;
            }
            // 预读不足 8KB 说明文件已读完（小文件），后续 reset 后按行读即可，无需特殊处理
            for (int i = 0; i < probeLen; i++) {
                if (probe[i] == 0) {
                    skipStats.addBinary();
                    return;
                }
            }
            // 编码判定基于首部样本：与 ReadFileTool 的 8KB 采样同理，属「前缀代表整体」的近似判定；
            // 极端混合编码文件（样本内合法 UTF-8、样本外非 UTF-8）可能被误判为 UTF-8，
            // 后续匹配行将出现 U+FFFD 替换符——低概率场景，接受该近似换取流式读取的恒定内存
            Charset charset = NativeCharsetKit.detectCharset(probe, probeLen);
            bis.reset();

            String line;
            int lineNumber = 0;
            // 相对路径基准策略：若搜索文件落在项目物理根目录下，统一计算相对于项目根的路径，
            // 确保输出的路径可直接被 downstream 的 read_file / edit_file 无缝消费；否则相对于搜索入口根目录
            String relativePath = formatRelativePath(file, searchRoot, projectRoot);
            // Matcher 复用：regex 模式下每行新建 Matcher 会随行数线性分配临时对象（10 万行 = 10 万次分配），
            // 循环外建一次、循环内 reset(line) 重置目标串，避免 GC 压力
            Matcher matcher = (regexPattern != null) ? regexPattern.matcher("") : null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(bis, charset))) {
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    // regexPattern 非 null 走正则匹配（含 ignoreCase 的 Pattern.quote 模式），否则走普通子串匹配
                    boolean matched = (matcher != null)
                            ? matcher.reset(line).find()
                            : line.contains(pattern);
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
            }
        } catch (IOException e) {
            log.warn("无法检索文件内容: {}, 异常: {}", file, e.getMessage());
        }
    }

    /**
     * 单次检索的跳过统计累加器：分别记录被防御性跳过的超大文件与二进制文件数量。
     * <p>
     * 遍历为同步单线程，普通可变字段即可满足线程安全；设计动机是空结果反馈——
     * 无统计时「未检索到匹配」无法区分目标真不存在与文件全被跳过（如全二进制目录）。
     * </p>
     */
    private static final class SkipStats {

        private int oversized;

        private int binary;

        void addOversized() {
            oversized++;
        }

        void addBinary() {
            binary++;
        }

        boolean any() {
            return oversized > 0 || binary > 0;
        }

        /**
         * 拼接人类可读的跳过描述文案，超大文件的上限大小与 {@link #MAX_FILE_SIZE} 联动
         */
        String describe() {
            StringBuilder sb = new StringBuilder();
            if (oversized > 0) {
                sb.append("超大文件(>").append(MAX_FILE_SIZE / 1024 / 1024).append("MB) ").append(oversized).append(" 个");
            }
            if (binary > 0) {
                if (!sb.isEmpty()) {
                    sb.append("、");
                }
                sb.append("二进制文件 ").append(binary).append(" 个");
            }
            return sb.append("被跳过未参与匹配").toString();
        }
    }
}
