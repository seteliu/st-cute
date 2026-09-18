package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.platform.common.NativeCharsetKit;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolResult;
import com.stioc.cute.project.ProjectService;
import com.stioc.cute.service.FileHashSupport;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.runtime.loop.RuntimeContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安全替换文件部分内容的本地核心修改工具
 */
@Slf4j
@Component
public class EditFileTool implements CuteTool {

    @Resource
    private ProjectService projectService;

    @Override
    public String getRawName() {
        return ToolNames.EDIT_FILE;
    }

    @Override
    public String getDescription() {
        return "精确替换指定文件的局部片段，oldContent 必须在文件中唯一命中才会执行替换。"
                + "匹配依次尝试三种策略：精确匹配 → CRLF 换行变体匹配 → 空白不敏感匹配（缩进与空白差异可容忍，"
                + "命中后替换的范围可能与 oldContent 字面略有出入）。命中多处时拒绝执行，请补充上下文或指定行号范围。"
                + "修改前必须先成功 read_file 读出该文件最新内容，否则会被门禁拦截；新建文件或整文件覆写（write_file）无此要求。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "目标文件路径，支持项目相对路径（以项目根目录为基准）或绝对路径"
            },
            "oldContent": {
              "type": "string",
              "description": "文件中待被替换的现有原文片段（包含行首缩进与空格，必须在文件中唯一存在）。注意：是文件里当前已有的内容，不是新内容"
            },
            "newContent": {
              "type": "string",
              "description": "替换后最终写入文件的新文本。注意：替换完成后文件中呈现的就是这段内容"
            },
            "startLine": {
              "type": "integer",
              "description": "待替换代码段的起始行号 (1-indexed)，可选，配合 oldContent 进行精准范围锁定；与 endLine 必须成对指定或成对省略"
            },
            "endLine": {
              "type": "integer",
              "description": "待替换代码段的结束行号 (1-indexed)，可选，配合 oldContent 进行精准范围锁定；与 startLine 必须成对指定或成对省略"
            }
          },
          "required": ["path", "oldContent", "newContent"]
        }
        """;
    }

    @Override
    public ToolAccessLevel getAccessLevel() {
        // 写级：文件局部修改，随文件写类工具治理（智能审批放行）
        return ToolAccessLevel.WRITE;
    }

    @Override
    public String getTargetResource(Map<String, Object> arguments) {
        // ToolArgs 宽松访问：规避 String.valueOf(null) 产生字面量 "null" 路径的边界坑
        return ToolArgs.of(arguments).getStringTrimmed("path");
    }

    @Override
    public String getLockKey(Map<String, Object> arguments) {
        String path = getTargetResource(arguments);
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return "file:" + new File(path).getCanonicalPath();
        } catch (Exception e) {
            return "file:" + path;
        }
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        ToolArgs args = ToolArgs.of(arguments);
        String pathVal = args.getString("path");
        String oldContent = args.getString("oldContent");
        String newContent = args.getString("newContent");

        if (pathVal == null || pathVal.isBlank()) {
            return ToolResult.error("参数 'path' 不能为空。");
        }
        if (oldContent == null || oldContent.isEmpty()) {
            return ToolResult.error("参数 'oldContent' 不能为空。");
        }
        if (newContent == null) {
            newContent = "";
        }

        try {
            File file = projectService.resolvePath(pathVal, agentContext).toFile();

            if (!file.exists()) {
                return ToolResult.error("文件不存在: " + pathVal);
            }
            if (file.isDirectory()) {
                return ToolResult.error("目标路径是一个目录，无法执行代码修改: " + pathVal);
            }

            // 强制安全门禁：修改前校验"读取过的内容仍与磁盘一致"以防止幻觉与过时修改。
            // 哈希校验三分支：无记录 → 先读再改；有记录但磁盘内容已变 → 拦截重读；哈希一致 → 放行
            // （读取哈希门禁记录已迁宿主伴生上下文，readFiles 出引擎）
            if (agentContext != null) {
                String absPath = file.getAbsolutePath();
                RuntimeContext runtimeCtx = agentContext.extra(RuntimeContext.class);
                String recordedHash = runtimeCtx != null ? runtimeCtx.getReadFiles().get(absPath) : null;
                if (recordedHash == null) {
                    log.warn("EditFileTool 安全防御触发：未读先改拦截 - {}", absPath);
                    return ToolResult.error("拒绝执行代码修改。门禁判定规则：read_file 成功读取过的文件才允许修改。当前状态：本会话尚未读取过该文件。"
                            + "请先使用 read_file 读取目标文件 [" + file.getName() + "] 的最新内容，然后重试修改。");
                }
                String currentHash = FileHashSupport.computeFileHash(file);
                if (!recordedHash.equals(currentHash)) {
                    log.warn("EditFileTool 安全防御触发：文件内容已变化拦截 - {}", absPath);
                    return ToolResult.error("拒绝执行代码修改。目标文件 [" + file.getName() + "] 的内容自上次 read_file 后已发生变化"
                            + "（可能被外部程序、用户或其他工具修改）。请重新 read_file 读取最新内容后再重试修改，"
                            + "防止基于过时上下文产生错误替换。");
                }
            }

            // 编码与文本元数据一致化：修改读与 read_file 共用同一探测函数与同样本策略，保证对同一文件的
            // 编码/BOM/换行风格判定严格一致，写回时按该编码重新编码，杜绝编码与换行符漂移
            NativeCharsetKit.FileTextMeta meta = NativeCharsetKit.detectFileMeta(file.toPath());
            Charset charset = meta.charset();
            // UTF-16 文件不支持编辑（字节含 \x00 读侧即判 UTF-16 拒绝，编码转换亦非本工具职责），显式拒绝引导人工转码
            if (meta.utf16Bom()) {
                return ToolResult.error("该文件为 UTF-16 编码（检测到 UTF-16 BOM 字节序标记），edit_file 暂不支持编辑。"
                        + "请先人工转换为 UTF-8 编码（如 Notepad++ 转码或 iconv 命令）后重试。");
            }
            // EOL 保真（注入归一）：模型侧内容统一按 \n 换行传入，注入前按文件主导风格转换，保证注入片段
            // 与文件原有 EOL 风格一致、原有区域字节不动（内置防重复归一，模型已传 CRLF 不会二次转换）
            newContent = NativeCharsetKit.normalizeEolToStyle(newContent, meta.eolStyle());
            String fileContent;
            try {
                fileContent = Files.readString(file.toPath(), charset);
            } catch (CharacterCodingException e) {
                // 探测编码与真实编码不符（如 8KB 采样未命中深处的非法字节）导致严格解码失败，
                // 交由统一错误处理器给出可行动提示，让模型显式指定 encoding 引导修正
                log.warn("EditFileTool 修改读解码失败: {}, 探测的编码: {}", pathVal, charset.name());
                return buildEncodingFailureResult(pathVal, charset);
            }

            // 空文件防御，引导使用 write_file
            if (fileContent.isEmpty()) {
                return ToolResult.error("文件内容为空，无法使用行号定位或局部内容替换。若要写入新内容，请直接使用 write_file。");
            }

            Integer startLine = args.getInt("startLine");
            Integer endLine = args.getInt("endLine");

            if ((startLine != null && endLine == null) || (startLine == null && endLine != null)) {
                return ToolResult.error("参数 'startLine' 和 'endLine' 必须同时指定或同时省略。");
            }

            // 写回内容显式初始化为 null：所有匹配路径终点必须完成赋值，写回前守卫校验，
            // 防御任何未预料的控制流路径把未赋值/异常状态带进物理写盘
            String updatedContent = null;
            int matchStartOffset = -1;
            int matchEndOffset = -1;

            if (startLine != null && endLine != null) {
                int totalLines = countLines(fileContent);
                if (startLine < 1 || endLine < 1 || startLine > totalLines || endLine > totalLines || startLine > endLine) {
                    return ToolResult.error("指定的行号范围 [" + startLine + ", " + endLine + "] 不合法。当前文件总行数为: " + totalLines);
                }

                // 包含结束行整行（含换行符）的偏移量
                int[] offsets = getLineRangeOffsets(fileContent, startLine, endLine);
                int startOffset = offsets[0];
                int endOffset = offsets[1];
                String rangeContent = fileContent.substring(startOffset, endOffset);

                // 在局部行范围内寻找子串匹配
                int subStart = -1;
                int subEnd = -1;

                // 1. 精确匹配子串
                int firstIdx = rangeContent.indexOf(oldContent);
                if (firstIdx != -1) {
                    int secondIdx = rangeContent.indexOf(oldContent, firstIdx + oldContent.length());
                    if (secondIdx == -1) {
                        subStart = firstIdx;
                        subEnd = firstIdx + oldContent.length();
                    } else {
                        return ToolResult.error("在指定的行号范围 [" + startLine + ", " + endLine + "] 内找到了多处 (2 处或以上) 'oldContent' 的精确匹配。请缩窄行号范围或提供更多上下文以确保唯一性。");
                    }
                } else {
                    // 1.5 EOL 变体精确重试：CRLF 文件上模型侧 \n 形态撞不上真实 \r\n（原样精确未命中），
                    // 先试确定性换行变体（\n → \r\n），唯一命中即用，避免误入空白不敏感模糊匹配产生误替换
                    String crlfVariant = oldContent.replace("\n", "\r\n");
                    int vFirst = crlfVariant.equals(oldContent) ? -1 : rangeContent.indexOf(crlfVariant);
                    if (vFirst != -1) {
                        int vSecond = rangeContent.indexOf(crlfVariant, vFirst + crlfVariant.length());
                        if (vSecond == -1) {
                            subStart = vFirst;
                            subEnd = vFirst + crlfVariant.length();
                        } else {
                            return ToolResult.error("在指定的行号范围 [" + startLine + ", " + endLine + "] 内找到了多处 'oldContent' 的精确匹配（CRLF 换行变体）。请缩窄行号范围或提供更多上下文以确保唯一性。");
                        }
                    }
                    if (subStart == -1) {
                        // 2. 模糊匹配子串 Fallback（EOL 变体精确未命中时才进入）
                        Pattern pattern = buildWhitespaceInsensitivePattern(oldContent);
                        Matcher matcher = pattern.matcher(rangeContent);
                        int matchCount = 0;
                        while (matcher.find()) {
                            matchCount++;
                            if (matchCount == 1) {
                                subStart = matcher.start();
                                subEnd = matcher.end();
                            }
                        }

                        if (matchCount > 1) {
                            return ToolResult.error("在指定的行号范围 [" + startLine + ", " + endLine + "] 内找到了多处 (" + matchCount + " 处) 'oldContent' 的空白不敏感匹配。请缩窄行号范围或提供更多上下文以确保唯一性。");
                        }
                    }
                }

                if (subStart == -1) {
                    // 防参数写反：oldContent 找不到，但 newContent 恰好能在文件中找到，大概率是两参数顺序颠倒了
                    if (!newContent.isEmpty() && fileContent.contains(newContent)) {
                        return ToolResult.error("疑似参数顺序写反：oldContent（待替换原文）在指定范围内未找到，而 newContent 反而存在于文件中。请检查：oldContent 应为文件中现有内容，newContent 为替换后的新内容。");
                    }
                    return ToolResult.error("在指定的行号范围 [" + startLine + ", " + endLine + "] 内未找到 'oldContent' 的匹配。\n" +
                            "该范围内的实际内容为:\n" + rangeContent + "\n\n" +
                            "你期望的 'oldContent' 为:\n" + oldContent);
                }

                // 映射回全文偏移量
                matchStartOffset = startOffset + subStart;
                matchEndOffset = startOffset + subEnd;
                updatedContent = fileContent.substring(0, matchStartOffset) + newContent + fileContent.substring(matchEndOffset);
            } else {
                // 全局搜索匹配
                int count = 0;
                int idx = 0;
                while ((idx = fileContent.indexOf(oldContent, idx)) != -1) {
                    count++;
                    idx += oldContent.length();
                }

                if (count == 1) {
                    matchStartOffset = fileContent.indexOf(oldContent);
                    matchEndOffset = matchStartOffset + oldContent.length();
                    updatedContent = fileContent.substring(0, matchStartOffset) + newContent + fileContent.substring(matchEndOffset);
                } else if (count > 1) {
                    return ToolResult.error("在文件 [" + file.getName() + "] 中找到了多处 (" + count + " 处) 'oldContent' 的精确匹配。为了安全起见，我们只能在唯一匹配时才能执行替换。请提供更多的上下文（如前后几行代码）或指定行号范围(startLine, endLine)来确保匹配的唯一性。");
                } else {
                    // EOL 变体精确重试：CRLF 文件上模型侧 \n 形态撞不上真实 \r\n（原样精确未命中），
                    // 先试确定性换行变体（\n → \r\n），唯一命中即用，避免误入空白不敏感模糊匹配产生误替换
                    String crlfVariant = oldContent.replace("\n", "\r\n");
                    int vCount = 0;
                    if (!crlfVariant.equals(oldContent)) {
                        int vIdx = 0;
                        while ((vIdx = fileContent.indexOf(crlfVariant, vIdx)) != -1) {
                            vCount++;
                            vIdx += crlfVariant.length();
                        }
                        if (vCount == 1) {
                            matchStartOffset = fileContent.indexOf(crlfVariant);
                            matchEndOffset = matchStartOffset + crlfVariant.length();
                            updatedContent = fileContent.substring(0, matchStartOffset) + newContent + fileContent.substring(matchEndOffset);
                        } else if (vCount > 1) {
                            return ToolResult.error("在文件 [" + file.getName() + "] 中找到了多处 (" + vCount + " 处) 'oldContent' 的精确匹配（CRLF 换行变体）。为了安全起见，我们只能在唯一匹配时才能执行替换。请提供更多的上下文（如前后几行代码）或指定行号范围(startLine, endLine)来确保匹配的唯一性。");
                        }
                    }
                    if (vCount == 0) {
                        // 全局模糊匹配 Fallback
                        Pattern pattern = buildWhitespaceInsensitivePattern(oldContent);
                        Matcher matcher = pattern.matcher(fileContent);
                        int matchCount = 0;
                        while (matcher.find()) {
                            matchCount++;
                            if (matchCount == 1) {
                                matchStartOffset = matcher.start();
                                matchEndOffset = matcher.end();
                            }
                        }

                        if (matchCount == 0) {
                            // 防参数写反：oldContent 找不到，但 newContent 恰好能在文件中找到，大概率是两参数顺序颠倒了
                            if (!newContent.isEmpty() && fileContent.contains(newContent)) {
                                return ToolResult.error("疑似参数顺序写反：oldContent（待替换原文）在文件中未找到，而 newContent 反而存在于文件中。请检查：oldContent 应为文件中现有内容，newContent 为替换后的新内容。");
                            }
                            return ToolResult.error("在文件 [" + file.getName() + "] 中未找到要替换的 'oldContent' 匹配片段（精确匹配与空白不敏感匹配均失败）。请检查空格、缩进或换行是否与文件实际内容一致。");
                        } else if (matchCount > 1) {
                            return ToolResult.error("在文件 [" + file.getName() + "] 中未找到 'oldContent' 的精确匹配，且找到了多处 (" + matchCount + " 处) 空白不敏感匹配。为了安全起见，只能在唯一匹配时执行替换。请提供更多的上下文（如前后几行代码）或指定行号范围(startLine, endLine)来确保匹配的唯一性。");
                        } else {
                            updatedContent = fileContent.substring(0, matchStartOffset) + newContent + fileContent.substring(matchEndOffset);
                        }
                    }
                }
            }

            // 写前往返校验门（Round-trip Preservation）：按探测编码对原始字节做
            // decode→encode 往返等式校验，等式不成立说明该编码非规范（如 GBK 撞上
            // 非 GBK 字节），无法保证未触碰区域字节级原样还原，拒绝写入防止静默损坏
            byte[] originalBytes = Files.readAllBytes(file.toPath());
            String roundTripContent = new String(originalBytes, charset);
            ByteBuffer reEncodedBuffer = charset.encode(roundTripContent);
            byte[] reEncoded = Arrays.copyOf(reEncodedBuffer.array(), reEncodedBuffer.limit());
            if (!Arrays.equals(originalBytes, reEncoded)) {
                return buildRoundTripFailureResult(pathVal, charset);
            }

            // 写回守卫：updatedContent 未赋值说明控制流异常（不应发生），显式报错而非写脏数据
            if (updatedContent == null) {
                log.error("EditFileTool 控制流异常: 匹配流程结束但写回内容未生成, {}", pathVal);
                return ToolResult.error("内部控制流异常：匹配流程已结束但未能生成替换后的内容，本次修改未执行。请检查参数后重试。");
            }

            Files.writeString(file.toPath(), updatedContent, charset);

            // 写入成功后同步更新内容哈希：同一文件连续多次编辑时无需重复 read_file（门禁以最新哈希校验）
            // （读取哈希门禁记录已迁运行时伴生上下文）
            if (agentContext != null) {
                RuntimeContext runtimeCtx = agentContext.extra(RuntimeContext.class);
                if (runtimeCtx != null) {
                    runtimeCtx.getReadFiles().put(file.getAbsolutePath(),
                            FileHashSupport.computeFileHash(file));
                }
            }

            // 提取修改位置前后 3 行的上下文切片提供闭环反馈
            int endPos = matchStartOffset + newContent.length();
            String contextSnippet = getContextSnippet(updatedContent, matchStartOffset, endPos, 3);

            // 计算替换落点在最终文件中的行号范围 (1-indexed)，供调用方精确定位与校验
            int matchedStartLine = offsetToLineNumber(updatedContent, matchStartOffset);
            int matchedEndLine = offsetToLineNumber(updatedContent, endPos);

            log.info("EditFileTool 修改成功: {}", pathVal);
            return new JSONObject()
                    .fluentPut("success", true)
                    .fluentPut("message", "已成功修改文件 [" + file.getName() + "] 的指定片段。")
                    .fluentPut("matchedLines", new int[]{matchedStartLine, matchedEndLine})
                    .fluentPut("context", contextSnippet)
                    .toJSONString();

        } catch (IOException e) {
            log.error("EditFileTool 修改异常", e);
            return ToolResult.error("修改文件失败: " + e.getMessage());
        }
    }

    /**
     * 将字符偏移量转换为行号 (1-indexed)，算法与 countLines 对齐。
     * offset 等于内容长度时返回最后一行行号。
     */
    private int offsetToLineNumber(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\r') {
                line++;
                if (i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
            } else if (c == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * 流式统计文件中的总行数（算法对齐 BufferedReader.readLine）
     */
    private int countLines(String content) {
        if (content.isEmpty()) {
            return 0;
        }
        int count = 0;
        int pos = 0;
        int len = content.length();
        while (pos < len) {
            char c = content.charAt(pos);
            if (c == '\r') {
                if (pos + 1 < len && content.charAt(pos + 1) == '\n') {
                    pos++;
                }
                count++;
            } else if (c == '\n') {
                count++;
            }
            pos++;
        }
        char lastChar = content.charAt(len - 1);
        if (lastChar != '\r' && lastChar != '\n') {
            count++;
        }
        return count;
    }

    /**
     * 计算并获取目标行号范围的字符级起始和截止偏移量（包含结束行的换行符）
     */
    private int[] getLineRangeOffsets(String content, int startLine, int endLine) {
        int len = content.length();
        int currentLine = 1;
        int pos = 0;
        int startOffset = 0;

        // 寻找起始偏移量
        while (pos < len && currentLine < startLine) {
            char c = content.charAt(pos);
            if (c == '\r') {
                if (pos + 1 < len && content.charAt(pos + 1) == '\n') {
                    pos++;
                }
                currentLine++;
            } else if (c == '\n') {
                currentLine++;
            }
            pos++;
        }
        startOffset = pos;

        // 寻找截止偏移量（包含目标结束行及其行结束符）
        while (pos < len && currentLine <= endLine) {
            char c = content.charAt(pos);
            if (c == '\r' || c == '\n') {
                if (c == '\r' && pos + 1 < len && content.charAt(pos + 1) == '\n') {
                    pos++;
                }
                currentLine++;
            }
            pos++;
        }
        int endOffset = pos;

        return new int[]{startOffset, endOffset};
    }

    /**
     * 截取修改位置前后若干行的上下文文本片段。
     *
     * <p>向前回溯时先遇到 '\n'，再检查其前一位是否为 '\r'（CRLF 整体跳过），
     * 向后推进时先遇到 '\r'，再检查其后一位是否为 '\n'（同理）。</p>
     *
     * @param content      修改后的完整文件内容
     * @param startPos     替换片段在 content 中的起始偏移（含）
     * @param endPos       替换片段在 content 中的结束偏移（不含）
     * @param contextLines 前后各保留的行数
     */
    private String getContextSnippet(String content, int startPos, int endPos, int contextLines) {
        int len = content.length();

        // 向前回溯：找到 startPos 往前第 contextLines 条行边界
        int start = startPos;
        for (int lines = 0; lines < contextLines && start > 0; ) {
            // 向前扫描一个字符，遇到行结束符才计一行
            char c = content.charAt(start - 1);
            start--;
            if (c == '\n') {
                // CRLF：'\n' 前还有 '\r'，一起跳过，算作同一个行结束符
                if (start > 0 && content.charAt(start - 1) == '\r') {
                    start--;
                }
                lines++;
            } else if (c == '\r') {
                lines++;
            }
        }

        // 向后推进：找到 endPos 往后第 contextLines 条行边界
        int end = endPos;
        for (int lines = 0; lines < contextLines && end < len; ) {
            char c = content.charAt(end);
            end++;
            if (c == '\r') {
                // CRLF：'\r' 后还有 '\n'，一起推进，算作同一个行结束符
                if (end < len && content.charAt(end) == '\n') {
                    end++;
                }
                lines++;
            } else if (c == '\n') {
                lines++;
            }
        }

        return content.substring(start, end);
    }

    /**
     * 将包含空白字符的待替换原文转换为具有空白折叠兼容性的正则表达式
     */
    private Pattern buildWhitespaceInsensitivePattern(String oldContent) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        int len = oldContent.length();
        while (i < len) {
            char c = oldContent.charAt(i);
            if (Character.isWhitespace(c)) {
                int start = i;
                boolean hasNewline = false;
                while (i < len && Character.isWhitespace(oldContent.charAt(i))) {
                    char ws = oldContent.charAt(i);
                    if (ws == '\r' || ws == '\n') {
                        hasNewline = true;
                    }
                    i++;
                }
                int end = i;

                boolean isLeading = (start == 0);
                boolean isTrailing = (end == len);

                if (!hasNewline) {
                    if (isLeading || isTrailing) {
                        regex.append("[ \\t]*");
                    } else {
                        regex.append("[ \\t]+");
                    }
                } else {
                    if (isLeading && isTrailing) {
                        regex.append("\\s*");
                    } else if (isLeading) {
                        regex.append("\\s*");
                    } else if (isTrailing) {
                        regex.append("[ \\t]*\\r?\\n");
                    } else {
                        regex.append("[ \\t]*\\r?\\n\\s*");
                    }
                }
            } else {
                StringBuilder token = new StringBuilder();
                while (i < len && !Character.isWhitespace(oldContent.charAt(i))) {
                    token.append(oldContent.charAt(i));
                    i++;
                }
                regex.append(Pattern.quote(token.toString()));
            }
        }
        return Pattern.compile(regex.toString());
    }

    /**
     * 构建编码失败的结构化错误返回（感知 encoding 参数）。
     * 裸的 CharacterCodingException 文案（如 "Input length = 1"）对使用者毫无可读性，
     * 此处转译为「已尝试的编码 + 可行动建议」的提示，引导模型显式指定 encoding 修正。
     *
     * @param pathVal       模型传入的原始路径参数（用于错误文案回显）
     * @param attemptedCharset 尝试失败的字符集
     * @return 结构化错误 JSON
     */
    private String buildEncodingFailureResult(String pathVal, Charset attemptedCharset) {
        return ToolResult.error("EncodingFailure", obj -> obj.fluentPut("message", "文件 [" + pathVal + "] 按 " + attemptedCharset.name()
                + " 编码解码失败（存在非法字节序列）。该文件的真实编码大概率不是 " + attemptedCharset.name()
                + "，请先用 read_file（encoding=auto 或显式 encoding=utf-8/gbk）确认文件真实编码后再重试修改。"));
    }

    /**
     * 构建往返校验失败的结构化错误返回。
     * 触发即代表该文件无法按当前判定编码做到字节级无损还原（编码非规范或局部损坏），
     * 拒绝写入并引导模型改用 write_file 整文件重写或人工排查，而非静默产生编码损坏。
     *
     * @param pathVal       模型传入的原始路径参数（用于错误文案回显）
     * @param attemptedCharset 尝试使用的字符集
     * @return 结构化错误 JSON
     */
    private String buildRoundTripFailureResult(String pathVal, Charset attemptedCharset) {
        return ToolResult.error("EncodingRoundTripFailure", obj -> obj.fluentPut("message", "文件 [" + pathVal + "] 无法按 " + attemptedCharset.name()
                + " 编码做到字节级无损往返还原（文件可能存在混合编码或非规范字节）。"
                + "为防止写回时静默损坏未修改区域，本次修改已被拒绝。"
                + "若确需修改，请使用 write_file 以明确编码整体重写该文件，或人工检查文件编码后处理。"));
    }
}
