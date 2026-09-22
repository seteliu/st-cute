package com.stioc.cute.tool.filetool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolResult;
import com.stioc.cute.platform.common.NativeCharsetKit;
import com.stioc.cute.tool.ToolNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 安全替换文件部分内容的本地核心修改工具
 */
@Slf4j
@Component
public class EditFileTool extends AbstractFileTool {

    /**
     * 单文件编辑体积上限（10MB，按 UTF-8 字节数口径）。
     * <p>
     * edit 需将文件全量载入内存（内容字符串 + 原始字节 + 往返编码副本，内存放大数倍），
     * 超限文件拒绝编辑防止 OOM。
     * </p>
     * <p>
     * 该口径与 {@link WriteFileTool} 的写入上限刻意保持一致：若 edit 上限低于 write 上限，
     * 中间区间文件会同时被两者拒绝，而 read_file 却读得进来，形成"能读不能改"的死路
     * （此前 edit 按 10MB、write 按字符数计，正是该问题的来源）。
     * </p>
     */
    private static final long MAX_EDIT_FILE_BYTES = 10 * 1024 * 1024L;

    /**
     * 窗口外孪生提示的行号列举上限：窗口外相同片段数达到该值时视为高频短片段（如 "}"），
     * 只报总数不逐行列行号，防止提示自身成为输出噪音
     */
    private static final int TWIN_TIP_MAX_LISTED = 5;

    @Override
    public String getRawName() {
        return ToolNames.EDIT_FILE;
    }

    @Override
    public String getDescription() {
        return "精确替换指定文件的局部片段，oldContent 必须在文件中唯一命中才会执行替换。"
                + "匹配依次尝试三种策略：精确匹配 → CRLF 换行变体匹配 → 空白不敏感匹配（缩进与空白差异可容忍，"
                + "命中后替换的范围可能与 oldContent 字面略有出入）。命中多处时拒绝执行，请补充上下文或指定行号范围。"
                + "建议修改前先 read_file 读出目标片段的最新内容，确保 oldContent 与文件实际内容精确一致，一次命中。";
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
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        EditFileArgs editArgs = EditFileArgs.from(arguments);
        String pathVal = editArgs.path();
        String oldContent = editArgs.oldContent();
        String newContent = editArgs.newContent();

        if (pathVal == null || pathVal.isBlank()) {
            return ToolResult.error("参数 'path' 不能为空。");
        }
        if (oldContent == null || oldContent.isEmpty()) {
            return ToolResult.error("参数 'oldContent' 不能为空。");
        }
        if (editArgs.hasInvalidLineRange()) {
            return ToolResult.error("参数 'startLine' 和 'endLine' 必须同时指定或同时省略。");
        }

        try {
            File file = resolveFile(pathVal, agentContext);

            if (!file.exists()) {
                return ToolResult.error("文件不存在: " + pathVal);
            }
            if (file.isDirectory()) {
                return ToolResult.error("目标路径是一个目录，无法执行代码修改: " + pathVal);
            }

            // 体量防御：超大文件（如百 MB 级日志）全量载入会直接 OOM，拒绝编辑并引导改走整体重写
            if (file.length() > MAX_EDIT_FILE_BYTES) {
                return ToolResult.error("文件过大（约 " + (file.length() / 1024 / 1024) + " MB），超过 edit_file 单文件处理上限（"
                        + (MAX_EDIT_FILE_BYTES / 1024 / 1024) + " MB）。该体积同样超出 write_file 上限，"
                        + "请先用 grep_search 定位目标片段、将相关内容拆分为独立小文件后再编辑，"
                        + "或改用 execute_command 上的外部工具（如 sed/脚本）处理。");
            }

            // 编码与文本元数据一致化：与 read_file 共用同一探测函数与同样本策略
            NativeCharsetKit.FileTextMeta meta = NativeCharsetKit.detectFileMeta(file.toPath());
            Charset charset = meta.charset();
            if (meta.utf16Bom()) {
                return ToolResult.error("该文件为 UTF-16 编码（检测到 UTF-16 BOM 字节序标记），edit_file 暂不支持编辑。"
                        + "请先人工转换为 UTF-8 编码（如 Notepad++ 转码或 iconv 命令）后重试。");
            }

            // EOL 保真：注入前按文件主导风格转换
            newContent = NativeCharsetKit.normalizeEolToStyle(newContent, meta.eolStyle());
            String fileContent;
            try {
                fileContent = Files.readString(file.toPath(), charset);
            } catch (CharacterCodingException e) {
                log.warn("EditFileTool 修改读解码失败: {}, 探测的编码: {}", pathVal, charset.name());
                return buildEncodingFailureResult(pathVal, charset);
            }

            // 空文件防御，引导使用 write_file
            if (fileContent.isEmpty()) {
                return ToolResult.error("文件内容为空，无法使用行号定位或局部内容替换。若要写入新内容，请直接使用 write_file。");
            }

            Integer startLine = editArgs.startLine();
            Integer endLine = editArgs.endLine();

            // EOL 归一化：优先按当前文件的换行风格归一化待替换内容
            String normalizedOld = NativeCharsetKit.normalizeEolToStyle(oldContent, meta.eolStyle());
            NativeCharsetKit.EolStyle altEol = (meta.eolStyle() == NativeCharsetKit.EolStyle.CRLF)
                    ? NativeCharsetKit.EolStyle.LF
                    : NativeCharsetKit.EolStyle.CRLF;
            String altVariantOld = NativeCharsetKit.normalizeEolToStyle(oldContent, altEol);

            int matchStartOffset;
            int matchEndOffset;

            if (editArgs.hasLineRange()) {
                int totalLines = FileEditMatcher.countLines(fileContent);
                if (startLine < 1 || endLine < 1 || startLine > totalLines || endLine > totalLines || startLine > endLine) {
                    return ToolResult.error("指定的行号范围 [" + startLine + ", " + endLine + "] 不合法。当前文件总行数为: " + totalLines);
                }

                // 包含结束行整行（含换行符）的偏移量
                LineRangeOffsets offsets = FileEditMatcher.getLineRangeOffsets(fileContent, startLine, endLine);
                String rangeContent = fileContent.substring(offsets.startOffset(), offsets.endOffset());

                MatchLocateResult match = FileEditMatcher.locateMatch(
                        rangeContent, normalizedOld, altVariantOld, newContent, fileContent,
                        "指定的行号范围 [" + startLine + ", " + endLine + "]", true
                );
                if (!match.isSuccess()) {
                    return ToolResult.error(match.errorMessage());
                }

                matchStartOffset = offsets.startOffset() + match.startOffset();
                matchEndOffset = offsets.startOffset() + match.endOffset();
            } else {
                MatchLocateResult match = FileEditMatcher.locateMatch(
                        fileContent, normalizedOld, altVariantOld, newContent, fileContent,
                        "文件 [" + file.getName() + "]", false
                );
                if (!match.isSuccess()) {
                    return ToolResult.error(match.errorMessage());
                }

                matchStartOffset = match.startOffset();
                matchEndOffset = match.endOffset();
            }

            String updatedContent = fileContent.substring(0, matchStartOffset) + newContent + fileContent.substring(matchEndOffset);

            // 窗口外孪生提示（防静默换错）：行号范围分支命中后，检查窗口外是否还有与本次替换片段相同的内容。
            // 场景：模型按旧认知的行号给窗口，若文件上方被插入内容导致窗口漂移，可能框住另一处相同片段并"成功"改错位置。
            // 全局分支无此风险（多处命中直接拒绝），故仅范围分支需要。
            // 注意按真实落点文本（而非 oldContent 字面）检索——模糊匹配时两者存在空白差异，孪生点与落点字节一致才搜得到。
            String twinTip = "";
            if (editArgs.hasLineRange()) {
                String actualMatched = fileContent.substring(matchStartOffset, matchEndOffset);
                List<Integer> twinLines = FileEditMatcher.locateAllLineNumbers(fileContent, actualMatched,
                        TWIN_TIP_MAX_LISTED, matchStartOffset, matchEndOffset);
                if (!twinLines.isEmpty()) {
                    // 达到列举上限即停搜，size>=上限 意味着真实孪生数只多不少——报"≥N 处"总数，不列行号防噪音
                    if (twinLines.size() >= TWIN_TIP_MAX_LISTED) {
                        twinTip = "；注意：文件内还有多处（≥" + TWIN_TIP_MAX_LISTED + " 处）与本次替换内容相同的片段，本次未修改，请确认替换落点符合预期";
                    } else {
                        String lineNos = twinLines.stream().map(String::valueOf).collect(Collectors.joining("、"));
                        twinTip = "；注意：文件内另有 " + twinLines.size() + " 处相同内容位于第 " + lineNos
                                + " 行，本次未修改，请确认你修改的是预期位置";
                    }
                }
            }

            // UTF-8 BOM 保真：若原文件带 UTF-8 BOM 且首部被替换修改，自动补回 \uFEFF 保持落盘字节序与原文件完全一致
            if (meta.hasUtf8Bom() && !updatedContent.startsWith("\uFEFF")) {
                updatedContent = "\uFEFF" + updatedContent;
            }

            // 写前往返校验门（Round-trip Preservation）：按探测编码对原始字节做 decode→encode 往返等式校验
            byte[] originalBytes = Files.readAllBytes(file.toPath());
            String roundTripContent = new String(originalBytes, charset);
            ByteBuffer reEncodedBuffer = charset.encode(roundTripContent);
            byte[] reEncoded = Arrays.copyOf(reEncodedBuffer.array(), reEncodedBuffer.limit());
            if (!Arrays.equals(originalBytes, reEncoded)) {
                return buildRoundTripFailureResult(pathVal, charset);
            }

            Files.writeString(file.toPath(), updatedContent, charset);

            // 写入成功后同步更新内容哈希：同一文件连续多次编辑时无需重复 read_file
            recordFileHash(agentContext, file);

            // 提取修改位置前后 3 行的上下文切片提供闭环反馈。
            // newContent 超过 4 行时对回显加帽（省 token）：前 3 行 + 头 2 行 + 省略 N 行 + 尾 2 行 + 后 3 行，
            // 落点行号已由 matchedLines 提供，完整内容模型本就刚亲手写过，无需全量回显
            int endPos = matchStartOffset + newContent.length();
            String contextSnippet = FileEditMatcher.getContextSnippetCapped(updatedContent, matchStartOffset, endPos, 3, newContent);

            // 计算替换落点在最终文件中的行号范围 (1-indexed)，供调用方精确定位与校验
            // 当 endPos > matchStartOffset 时，以新插入内容最后一个字符偏移 (endPos - 1) 计算结束行，避免末尾换行符导致结束行号虚高
            int matchedStartLine = FileEditMatcher.offsetToLineNumber(updatedContent, matchStartOffset);
            int matchedEndLine = (endPos > matchStartOffset)
                    ? FileEditMatcher.offsetToLineNumber(updatedContent, endPos - 1)
                    : matchedStartLine;

            log.info("EditFileTool 修改成功: {}", pathVal);
            return new JSONObject()
                    .fluentPut("success", true)
                    .fluentPut("message", "已成功修改文件 [" + file.getName() + "] 的指定片段。" + twinTip)
                    .fluentPut("matchedLines", new int[]{matchedStartLine, matchedEndLine})
                    .fluentPut("context", contextSnippet)
                    .toJSONString();

        } catch (IOException e) {
            log.error("EditFileTool 修改异常", e);
            return ToolResult.error("修改文件失败: " + getSafeErrorMessage(e));
        }
    }

    private String buildEncodingFailureResult(String pathVal, Charset attemptedCharset) {
        return ToolResult.error("EncodingFailure", obj -> obj.fluentPut("message", "文件 [" + pathVal + "] 按 " + attemptedCharset.name()
                + " 编码解码失败（存在非法字节序列）。该文件的真实编码大概率不是 " + attemptedCharset.name()
                + "，请先用 read_file（encoding=auto 或显式 encoding=utf-8/gbk）确认文件真实编码后再重试修改。"));
    }

    private String buildRoundTripFailureResult(String pathVal, Charset attemptedCharset) {
        return ToolResult.error("EncodingRoundTripFailure", obj -> obj.fluentPut("message", "文件 [" + pathVal + "] 无法按 " + attemptedCharset.name()
                + " 编码做到字节级无损往返还原（文件可能存在混合编码或非规范字节）。"
                + "为防止写回时静默损坏未修改区域，本次修改已被拒绝。"
                + "若确需修改，请使用 write_file 以明确编码整体重写该文件，或人工检查文件编码后处理。"));
    }
}
