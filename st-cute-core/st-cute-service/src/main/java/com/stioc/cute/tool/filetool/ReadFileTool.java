package com.stioc.cute.tool.filetool;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolResult;
import com.stioc.cute.platform.common.NativeCharsetKit;
import com.stioc.cute.tool.ToolNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * 安全读取本地物理文件内容的本地核心只读工具
 */
@Slf4j
@Component
public class ReadFileTool extends AbstractFileTool {

    /**
     * 单文件读取字节上限（100MB）：防止模型对超大文件（如打包产物/日志导出）发起读取造成内存与上下文冲击
     */
    private static final long MAX_READ_FILE_SIZE = 100 * 1024 * 1024L;

    @Override
    public String getRawName() {
        return ToolNames.READ_FILE;
    }

    @Override
    public String getDescription() {
        return "读取指定纯文本文件的内容，每行附带行号。"
                + "仅支持纯文本文件（代码、Markdown、配置等）；PDF/Word/Excel/PPT/图片等二进制文件请改用 load_attachment 工具。"
                + "编码默认自动探测，出现乱码时可用 encoding 参数显式指定。";
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
            "startLine": {
              "type": "integer",
              "description": "读取的起始行号 (1-indexed)，可选，默认为 1",
              "default": 1
            },
            "lineCount": {
              "type": "integer",
              "description": "读取的行数，可选，默认读取 1000 行",
              "default": 1000
            },
            "encoding": {
              "type": "string",
              "description": "读取该文件时所用的解码字符集（默认 auto：自动探测，优先 UTF-8）。出现乱码或已知文件编码时可显式指定，如 utf-8、gbk。仅用于读取解码，不改变文件本身",
              "default": "auto"
            }
          },
          "required": ["path"]
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
        ReadFileArgs readArgs = ReadFileArgs.from(arguments);
        String pathVal = readArgs.path();
        if (pathVal == null || pathVal.isBlank()) {
            return ToolResult.error("参数 'path' 不能为空。");
        }

        int startLine = readArgs.startLine();
        int lineCount = readArgs.lineCount();

        File file = resolveFile(pathVal, agentContext);

        if (!file.exists()) {
            return ToolResult.error("文件不存在: " + pathVal);
        }
        if (file.isDirectory()) {
            return ToolResult.error("路径是一个目录，无法作为文件读取: " + pathVal);
        }
        if (!file.canRead()) {
            return ToolResult.error("无权读取该文件: " + pathVal);
        }

        // 体量防御：超大文件（如打包产物/巨型日志）拒绝读取，引导分段或改用搜索类工具定位
        if (file.length() > MAX_READ_FILE_SIZE) {
            return ToolResult.error("文件过大（约 " + (file.length() / 1024 / 1024) + " MB），超过 read_file 单文件处理上限（"
                    + (MAX_READ_FILE_SIZE / 1024 / 1024) + " MB）。请改用 grep_search 在该文件内定位关键内容，"
                    + "或用 execute_command 配合重定向/分割命令（如 split）拆分后再分段读取。");
        }

        // 二进制拦截：本工具与 grep_search 一致，仅支持纯文本文件。
        // PDF/Word/Excel/图片等二进制文件需改用 load_attachment 工具（支持多模态解析与内容提取）
        String binaryReject = checkBinaryFile(file);
        if (binaryReject != null) {
            return binaryReject;
        }

        // 解析编码参数：auto = 采样自动探测（默认，与 BOM/换行风格共用一次采样），其他值按 Java 字符集名强制指定
        Charset charset = null;
        String encodingVal = readArgs.encoding();
        boolean explicitEncoding = !readArgs.isAutoEncoding();
        if (explicitEncoding) {
            try {
                charset = Charset.forName(encodingVal);
            } catch (Exception e) {
                return ToolResult.error("参数 'encoding' 的值 '" + encodingVal + "' 不是合法字符集名 (Exception: " + e.getMessage() + ")。"
                        + "常用取值：auto（自动探测）、utf-8、gbk。");
            }
        }
        // 文本元数据探测（一次 8KB 采样）：auto 模式编码判定 + UTF-16 BOM 显式拒绝 + UTF-8 BOM 剥离展示
        NativeCharsetKit.FileTextMeta meta = NativeCharsetKit.detectFileMeta(file.toPath());
        if (charset == null) {
            charset = meta.charset();
        }
        if (meta.utf16Bom() && !explicitEncoding) {
            // UTF-16 BOM 检测前置：字节含 \x00 否则将被二进制拦截误报，此处给出精确的可行动提示
            return ToolResult.error("该文件为 UTF-16 编码（检测到 UTF-16 BOM 字节序标记），read_file 暂不支持读取与编辑。"
                    + "请先人工转换为 UTF-8 编码（如 Notepad++ 转码或 iconv 命令）后再处理。"
                    + "（提示：因未成功读取，该文件同样无法通过 edit_file 局部修改；若确需修改，请改用 write_file 整体重写或先转码）");
        }
        // UTF-8 BOM 透明化（读取侧）：判定需剥离展示的 BOM 字符（写回侧自动保留），模型全程无感零认知负担
        boolean stripUtf8Bom = meta.hasUtf8Bom() && StandardCharsets.UTF_8.name().equalsIgnoreCase(charset.name());

        StringBuilder content = new StringBuilder();
        // 流式读取目标行区间：不再先全量扫描一遍统计总行数（大文件三遍全读 IO 放大），
        // 总行数改为在读满即停后按「已到 EOF」与否推断语义——截断提示仅在确定未到文件末尾时给出
        boolean truncated = false;
        int totalLines = -1;

        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), charset)) {
            String line;
            int currentLine = 0;
            int linesRead = 0;
            while ((line = reader.readLine()) != null) {
                currentLine++;
                if (currentLine >= startLine) {
                    content.append(currentLine).append(": ").append(line).append("\n");
                    linesRead++;
                    if (linesRead >= lineCount) {
                        truncated = true;
                        break;
                    }
                }
            }
            // 循环自然结束（未触发 break）说明已读至 EOF：本次读取覆盖到了文件末尾
            if (!truncated) {
                totalLines = currentLine;
            }
            log.info("ReadFileTool 执行成功: {}, 读取了 {} 行, 文件总行数 {}", pathVal, linesRead, totalLines);

            // 记录内容哈希（基于完整文件字节而非本次读取片段），供修改门禁做"内容未变即放行"校验
            recordFileHash(agentContext, file);

            // 截断提示：仅在「达到行数上限且确认未读至文件末尾」时给出（EOF 后的提示对模型无意义且浪费上下文）。
            // 文件总行数未知时不冒充精确值，以「仍有后续内容」的语义表述
            if (truncated) {
                String tailTip = totalLines > 0
                        ? "，文件共 " + totalLines + " 行"
                        : "，文件仍有后续内容（总行数未知）";
                content.append("... [此处已截断").append(tailTip)
                        .append("，本次返回第 ").append(startLine).append("-").append(currentLine)
                        .append(" 行，已达到单次读取行数限制 ").append(lineCount)
                        .append(" 行，如需继续可指定更大的 startLine] ...");
            }

            // 空结果自描述契约：空文件/起始行超界时明确说明原因返回非空文案，
            // 避免空串落库后被回填层误替换为面向命令工具设计的通用占位（模型无法理解语义）
            if (content.length() == 0) {
                if (file.length() == 0) {
                    return "[文件为空：" + pathVal + " 共 0 字节，无可读内容。若需写入内容请使用 write_file]";
                }
                return "[读取范围无内容：文件共 " + Math.max(totalLines, 0) + " 行，起始行 " + startLine + " 超出文件末尾，请调小 startLine 重试]";
            }

            // UTF-8 BOM 剥离展示：BOM 只存在于文件首行行首，删除内容中首个 U+FEFF 即可（写回侧自动保留）
            if (stripUtf8Bom) {
                int bomIdx = content.indexOf("\uFEFF");
                if (bomIdx >= 0) {
                    content.deleteCharAt(bomIdx);
                }
            }

            // 非 UTF-8 编码显式标注：auto 探测判定为非 UTF-8（如 GBK）时在结果尾部附加编码信息，
            // 让模型带着编码意识去构造后续修改（edit_file 将按同一编码写回），防止默认一切皆 UTF-8
            String footer = "";
            if (!StandardCharsets.UTF_8.name().equalsIgnoreCase(charset.name())) {
                footer = "\n[系统提示：本文件以 " + charset.name() + " 编码读取。"
                        + "edit_file 修改后将保持该编码写回，无需转换。]";
            }
            // 乱码感知提示（REPLACE 策略副作用）：无法解码的字节不会抛异常而是落为 U+FFFD 替换符（），
            // 出现即代表模型看到了乱码而非文件真实内容，需显式提示换编码重读验证，防止基于乱码推理
            long replacementCount = content.chars().filter(c -> c == '\uFFFD').count();
            if (replacementCount >= 3) {
                footer += "\n[系统提示：本次读取内容中出现 " + replacementCount
                        + " 处 U+FFFD 替换符（\\uFFFD），说明文件存在按 " + charset.name()
                        + " 解码失败的字节（典型为编码错配产生的乱码）。"
                        + "请勿基于乱码内容做判断，建议显式指定 encoding=gbk 或 encoding=utf-8 重新读取验证。]";
            }
            return content + footer;
        } catch (CharacterCodingException e) {
            // 编码错配友好化兜底：转译为「已尝试的编码 + 疑似原因 + 可行动建议」的结构化提示
            log.warn("ReadFileTool 解码失败: {}, 尝试的编码: {}", pathVal, charset.name());
            Charset attemptedCharset = charset;
            return ToolResult.error("EncodingFailure", obj -> obj.fluentPut("message", "文件按 " + attemptedCharset.name()
                    + " 编码解码失败（存在非法字节序列）。该文件的真实编码大概率不是 " + attemptedCharset.name()
                    + "，或文件存在混合编码/局部损坏。建议改用 encoding=auto（自动探测）重试；"
                    + "若已处于 auto 模式仍失败，可尝试显式指定 encoding=utf-8 或 encoding=gbk 逐一验证。"));
        } catch (IOException e) {
            log.error("ReadFileTool 读取异常", e);
            return ToolResult.error("读取文件失败: " + getSafeErrorMessage(e));
        }
    }

    /**
     * 二进制文件探测与拦截（与 grep_search 的探测策略一致：前 1024 字节含零字节即判定为二进制）。
     *
     * @return 拦截提示 JSON；纯文本文件返回 null 放行
     */
    private String checkBinaryFile(File file) {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[1024];
            int read = in.read(buffer);
            for (int i = 0; i < read; i++) {
                if (buffer[i] == 0) {
                    return ToolResult.error("该文件为二进制格式（如 PDF/Word/Excel/PPT/图片/压缩包等），read_file 仅支持纯文本文件。"
                            + "请改用 load_attachment 工具加载该文件，系统将自动解析内容"
                            + "（图片与文档内嵌图片将以多模态视觉呈现，需模型支持视觉能力）。");
                }
            }
        } catch (IOException e) {
            log.warn("二进制探测读取失败，默认放行由后续读取兜底: {}, 异常: {}", file.getName(), e.getMessage());
        }
        return null;
    }
}
