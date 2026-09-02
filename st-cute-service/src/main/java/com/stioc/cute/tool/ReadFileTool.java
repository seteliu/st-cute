package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.platform.common.NativeCharsetKit;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolExecutionContext;
import com.stioc.cute.tool.access.ToolNames;
import com.stioc.cute.security.access.WorkspacePathResolver;
import com.stioc.cute.agent.access.AgentContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * 安全读取本地物理文件内容的本地核心只读工具
 */
@Slf4j
@Component
public class ReadFileTool implements CuteTool {

    @Resource
    private WorkspacePathResolver workspacePathResolver;

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getName() {
        return ToolNames.READ_FILE;
    }

    @Override
    public String getDescription() {
        return "【安全核心工具】读取指定纯文本文件的内容。每行附带行号。修改文件前，你必须先使用此工具阅读其最新内容以防止幻觉，否则修改将被系统拒绝。"
                + "仅支持纯文本文件（代码、Markdown、配置等）；PDF/Word/Excel/PPT/图片等二进制文件请使用 load_attachment 工具。"
                + "编码默认 auto 自动探测（UTF-8 严格校验优先，失败回退系统原生编码如 GBK），与 grep_search 行为一致；可用 encoding 参数强制指定。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "目标文件路径，支持绝对路径或项目相对路径"
            },
            "startLine": {
              "type": "integer",
              "description": "读取的起始行号 (1-indexed)，可选，默认为 1"
            },
            "lineCount": {
              "type": "integer",
              "description": "读取的行数，可选，默认读取 1000 行",
              "default": 1000
            },
            "encoding": {
              "type": "string",
              "description": "文件解码字符集，可选。支持 'auto'（默认，采样探测：UTF-8 严格校验优先，失败回退系统原生编码如 GBK）、'utf-8'、'gbk'，也可传 Java 合法字符集名。出现乱码或明确知晓文件编码时可显式指定。",
              "default": "auto"
            }
          },
          "required": ["path"]
        }
        """;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        String pathVal = (String) arguments.get("path");
        if (pathVal == null || pathVal.isBlank()) {
            return new JSONObject().fluentPut("error", "参数 'path' 不能为空。").toJSONString();
        }

        int startLine = 1;
        if (arguments.get("startLine") != null) {
            startLine = ((Number) arguments.get("startLine")).intValue();
        }
        int lineCount = 1000;
        if (arguments.get("lineCount") != null) {
            lineCount = ((Number) arguments.get("lineCount")).intValue();
        }

        File file = workspacePathResolver.resolvePath(pathVal, agentContext).toFile();

        if (!file.exists()) {
            return new JSONObject().fluentPut("error", "文件不存在: " + pathVal).toJSONString();
        }
        if (file.isDirectory()) {
            return new JSONObject().fluentPut("error", "路径是一个目录，无法作为文件读取: " + pathVal).toJSONString();
        }
        if (!file.canRead()) {
            return new JSONObject().fluentPut("error", "无权读取该文件: " + pathVal).toJSONString();
        }

        // 二进制拦截：本工具与 grep_search 一致，仅支持纯文本文件。
        // PDF/Word/Excel/图片等二进制文件需改用 load_attachment 工具（支持多模态解析与内容提取）
        String binaryReject = checkBinaryFile(file);
        if (binaryReject != null) {
            return binaryReject;
        }

        // 解析编码参数：auto = 采样自动探测（默认，与 BOM/换行风格共用一次采样），其他值按 Java 字符集名强制指定
        Charset charset = null;
        String encodingVal = arguments.get("encoding") != null ? String.valueOf(arguments.get("encoding")).trim() : "auto";
        boolean explicitEncoding = !"auto".equalsIgnoreCase(encodingVal);
        if (explicitEncoding) {
            try {
                charset = Charset.forName(encodingVal);
            } catch (Exception e) {
                return new JSONObject().fluentPut("error",
                        "参数 'encoding' 的值 '" + encodingVal + "' 不是合法字符集名 (Exception: " + e.getMessage() + ")。"
                                + "常用取值：auto（自动探测）、utf-8、gbk。").toJSONString();
            }
        }
        // 文本元数据探测（一次 8KB 采样）：auto 模式编码判定 + UTF-16 BOM 显式拒绝 + UTF-8 BOM 剥离展示
        NativeCharsetKit.FileTextMeta meta = NativeCharsetKit.detectFileMeta(file.toPath());
        if (charset == null) {
            charset = meta.charset();
        }
        if (meta.utf16Bom() && !explicitEncoding) {
            // UTF-16 BOM 检测前置：字节含 \x00 否则将被二进制拦截误报，此处给出精确的可行动提示
            return new JSONObject().fluentPut("error",
                    "该文件为 UTF-16 编码（检测到 UTF-16 BOM 字节序标记），read_file 暂不支持读取与编辑。"
                            + "请先人工转换为 UTF-8 编码（如 Notepad++ 转码或 iconv 命令）后再处理。").toJSONString();
        }
        // UTF-8 BOM 透明化（读取侧）：判定需剥离展示的 BOM 字符（写回侧自动保留），模型全程无感零认知负担
        boolean stripUtf8Bom = meta.hasUtf8Bom() && StandardCharsets.UTF_8.name().equalsIgnoreCase(charset.name());

        StringBuilder content = new StringBuilder();
        // 先流式统计文件总行数，供截断提示中告知模型完整文件规模，避免盲翻；统计失败时降级为 -1（不提示）
        int totalLines = -1;
        try (BufferedReader counter = Files.newBufferedReader(file.toPath(), charset)) {
            while (counter.readLine() != null) {
                totalLines++;
            }
        } catch (IOException e) {
            log.warn("统计文件总行数失败，降级为不提示: {}, 异常: {}", pathVal, e.getMessage());
            totalLines = -1;
        }
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
                        content.append("... [此处已截断，文件共 ").append(totalLines)
                                .append(" 行，本次返回第 ").append(startLine).append("-").append(currentLine)
                                .append(" 行，已达到单次读取行数限制 ").append(lineCount)
                                .append(" 行，如需继续可指定更大的 startLine] ...");
                        break;
                    }
                }
            }
            log.info("ReadFileTool 执行成功: {}, 读取了 {} 行, 文件总行数 {}", pathVal, linesRead, totalLines);

            if (agentContext != null) {
                // 记录内容哈希（基于完整文件字节而非本次读取片段），供修改门禁做"内容未变即放行"校验
                agentContext.getReadFiles().put(file.getAbsolutePath(),
                        com.stioc.cute.security.access.FileHashSupport.computeFileHash(file));
            }

            // 空结果自描述契约：空文件/起始行超界时明确说明原因返回非空文案，
            // 避免空串落库后被回填层误替换为面向命令工具设计的通用占位（模型无法理解语义）
            if (content.length() == 0) {
                if (totalLines == 0) {
                    return "[文件为空：" + pathVal + " 共 0 字节，无可读内容。若需写入内容请使用 write_to_file]";
                }
                return "[读取范围无内容：文件共 " + totalLines + " 行，起始行 " + startLine + " 超出文件末尾，请调小 startLine 重试]";
            }

            // UTF-8 BOM 剥离展示：BOM 只存在于文件首行行首，删除内容中首个 U+FEFF 即可（写回侧自动保留）
            if (stripUtf8Bom) {
                int bomIdx = content.indexOf("\uFEFF");
                if (bomIdx >= 0) {
                    content.deleteCharAt(bomIdx);
                }
            }

            // 非 UTF-8 编码显式标注：auto 探测判定为非 UTF-8（如 GBK）时在结果尾部附加编码信息，
            // 让模型带着编码意识去构造后续修改（replace_file_content 将按同一编码写回），防止默认一切皆 UTF-8
            String footer = "";
            if (!StandardCharsets.UTF_8.name().equalsIgnoreCase(charset.name())) {
                footer = "\n[系统提示：本文件以 " + charset.name() + " 编码读取。"
                        + "replace_file_content 修改后将保持该编码写回，无需转换。]";
            }
            // 乱码感知提示（REPLACE 策略副作用）：无法解码的字节不会抛异常而是落为 U+FFFD 替换符（�），
            // 出现即代表模型看到了乱码而非文件真实内容，需显式提示换编码重读验证，防止基于乱码推理
            long replacementCount = content.chars().filter(c -> c == '\uFFFD').count();
            if (replacementCount >= 3) {
                footer += "\n[系统提示：本次读取内容中出现 " + replacementCount
                        + " 处 U+FFFD 替换符（�），说明文件存在按 " + charset.name()
                        + " 解码失败的字节（典型为编码错配产生的乱码）。"
                        + "请勿基于乱码内容做判断，建议显式指定 encoding=gbk 或 encoding=utf-8 重新读取验证。]";
            }
            return content + footer;
        } catch (CharacterCodingException e) {
            // 编码错配友好化兜底：Files.newBufferedReader 默认 REPLACE 策略，正常不抛此异常（产出 U+FFFD 落入上方乱码提示），
            // 此处防御性保留以覆盖未来解码策略调整场景。裸异常文案（如 "Input length = 1"）对使用者毫无可读性，
            // 转译为「已尝试的编码 + 疑似原因 + 可行动建议」的结构化提示，引导模型自行纠偏而非反复盲试
            log.warn("ReadFileTool 解码失败: {}, 尝试的编码: {}", pathVal, charset.name());
            return new JSONObject()
                    .fluentPut("error", "EncodingFailure")
                    .fluentPut("message", "文件按 " + charset.name()
                            + " 编码解码失败（存在非法字节序列）。该文件的真实编码大概率不是 " + charset.name()
                            + "，或文件存在混合编码/局部损坏。建议改用 encoding=auto（自动探测）重试；"
                            + "若已处于 auto 模式仍失败，可尝试显式指定 encoding=utf-8 或 encoding=gbk 逐一验证。")
                    .toJSONString();
        } catch (IOException e) {
            log.error("ReadFileTool 读取异常", e);
            return new JSONObject().fluentPut("error", "读取文件失败: " + e.getMessage()).toJSONString();
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
                    return new JSONObject().fluentPut("error",
                            "该文件为二进制格式（如 PDF/Word/Excel/PPT/图片/压缩包等），read_file 仅支持纯文本文件。"
                                    + "请改用 load_attachment 工具加载该文件，系统将自动解析内容"
                                    + "（图片与文档内嵌图片将以多模态视觉呈现，需模型支持视觉能力）。")
                            .toJSONString();
                }
            }
        } catch (IOException e) {
            log.warn("二进制探测读取失败，默认放行由后续读取兜底: {}, 异常: {}", file.getName(), e.getMessage());
        }
        return null;
    }

}
