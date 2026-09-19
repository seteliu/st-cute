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
import java.util.Map;

/**
 * 安全替换文件部分内容的本地核心修改工具
 */
@Slf4j
@Component
public class EditFileTool extends AbstractFileTool {

    /**
     * 单文件编辑体量上限（10MB）：edit 需将文件全量载入内存（内容字符串 + 原始字节 + 往返编码副本，内存放大数倍），
     * 超限文件拒绝编辑防止 OOM，引导改走 write_file 整体重写或拆分文件
     */
    private static final long MAX_EDIT_FILE_SIZE = 10 * 1024 * 1024L;

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
            if (file.length() > MAX_EDIT_FILE_SIZE) {
                return ToolResult.error("文件过大（约 " + (file.length() / 1024 / 1024) + " MB），超过 edit_file 单文件处理上限（"
                        + (MAX_EDIT_FILE_SIZE / 1024 / 1024) + " MB）。大文件修改请改用 write_file 以完整内容整体重写，"
                        + "或先用 grep_search 定位目标片段、将相关内容拆分为独立小文件后再编辑。");
            }

            // 强制安全门禁：修改前校验"读取过的内容仍与磁盘一致"以防止幻觉与过时修改
            String guardError = verifyReadBeforeWrite(agentContext, file);
            if (guardError != null) {
                return guardError;
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
                        rangeContent, oldContent, normalizedOld, altVariantOld, newContent, fileContent,
                        "指定的行号范围 [" + startLine + ", " + endLine + "]", true
                );
                if (!match.isSuccess()) {
                    return ToolResult.error(match.errorMessage());
                }

                matchStartOffset = offsets.startOffset() + match.startOffset();
                matchEndOffset = offsets.startOffset() + match.endOffset();
            } else {
                MatchLocateResult match = FileEditMatcher.locateMatch(
                        fileContent, oldContent, normalizedOld, altVariantOld, newContent, fileContent,
                        "文件 [" + file.getName() + "]", false
                );
                if (!match.isSuccess()) {
                    return ToolResult.error(match.errorMessage());
                }

                matchStartOffset = match.startOffset();
                matchEndOffset = match.endOffset();
            }

            String updatedContent = fileContent.substring(0, matchStartOffset) + newContent + fileContent.substring(matchEndOffset);

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

            // 提取修改位置前后 3 行的上下文切片提供闭环反馈
            int endPos = matchStartOffset + newContent.length();
            String contextSnippet = FileEditMatcher.getContextSnippet(updatedContent, matchStartOffset, endPos, 3);

            // 计算替换落点在最终文件中的行号范围 (1-indexed)，供调用方精确定位与校验
            // 当 endPos > matchStartOffset 时，以新插入内容最后一个字符偏移 (endPos - 1) 计算结束行，避免末尾换行符导致结束行号虚高
            int matchedStartLine = FileEditMatcher.offsetToLineNumber(updatedContent, matchStartOffset);
            int matchedEndLine = (endPos > matchStartOffset)
                    ? FileEditMatcher.offsetToLineNumber(updatedContent, endPos - 1)
                    : matchedStartLine;

            log.info("EditFileTool 修改成功: {}", pathVal);
            return new JSONObject()
                    .fluentPut("success", true)
                    .fluentPut("message", "已成功修改文件 [" + file.getName() + "] 的指定片段。")
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
