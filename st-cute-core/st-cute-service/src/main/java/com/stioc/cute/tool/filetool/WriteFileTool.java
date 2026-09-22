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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * 新建或覆写本地文件内容物理落地修改工具
 */
@Slf4j
@Component
public class WriteFileTool extends AbstractFileTool {

    /**
     * 单次写入体量上限（10MB，按 UTF-8 编码后的字节数口径）。
     * <p>
     * 写入内容字符串与目标编码字节副本会同时驻留内存，超限拒绝写入防止巨内容打满内存。
     * 口径与 {@link EditFileTool} 的编辑上限保持一致：二者若不一致，会出现「能读不能改」的
     * 中间区间（如 10MB~100MB 的文件既超 write_file 上限、又超 edit_file 上限，
     * 而 read_file 能读，导致无工具可用的死路）。
     * </p>
     */
    private static final long MAX_WRITE_CONTENT_BYTES = 10 * 1024 * 1024L;

    /**
     * 单文件可读上限（与 {@link ReadFileTool} 对齐）：超出此体积的文件本就读不进来，
     * 允许写入反而会产出无法被后续读取的"盲区文件"
     */
    private static final long MAX_WRITE_FILE_BYTES = 100 * 1024 * 1024L;

    @Override
    public String getRawName() {
        return ToolNames.WRITE_FILE;
    }

    @Override
    public String getDescription() {
        return "覆盖写入文件或新建文件。如果文件所在父目录不存在，将自动级联创建父目录。"
                + "默认以 UTF-8 编码落盘；可通过 encoding 参数指定其他编码（如 gbk），实现文件编码转换。"
                + "注意：覆写已存在的非空文件前，必须先用 read_file 读取过该文件的最新内容（否则会被门禁拦截），"
                 + "防止在未见过现有内容的情况下盲目推平整个文件；新建文件与空文件覆写无此要求。";
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
            "content": {
              "type": "string",
              "description": "需要写入文件的完整文本内容"
            },
            "encoding": {
              "type": "string",
              "description": "写出该文件时所用的落盘编码（默认 utf-8）。需把现有文件转换为其他编码（如 UTF-8 转 GBK）时显式指定，如 gbk，也支持其他 Java 合法字符集名。仅决定写入编码，与读取时的解码编码无关",
              "default": "utf-8"
            }
          },
          "required": ["path", "content"]
        }
        """;
    }

    @Override
    public ToolAccessLevel getAccessLevel() {
        // 写级：整文件覆写，随文件写类工具治理（智能审批放行）
        return ToolAccessLevel.WRITE;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        WriteFileArgs writeArgs = WriteFileArgs.from(arguments);
        String pathVal = writeArgs.path();
        String contentVal = writeArgs.content();

        if (pathVal == null || pathVal.isBlank()) {
            return ToolResult.error("参数 'path' 不能为空。");
        }

        // 体量防御：按目标编码后的实际字节数判定（而非字符数）。
        // 字符数口径对 CJK 内容严重低估——10MB 字符的中文按 UTF-8 落盘约 30MB，
        // 会绕过上限造成内存与磁盘的双重超预期占用
        Charset sizeProbeCharset = resolveSizeProbeCharset(writeArgs);
        long contentBytes = contentVal.getBytes(sizeProbeCharset).length;
        if (contentBytes > MAX_WRITE_CONTENT_BYTES) {
            return ToolResult.error("写入内容过大（约 " + (contentBytes / 1024 / 1024) + " MB），超过 write_file 单次处理上限（"
                    + (MAX_WRITE_CONTENT_BYTES / 1024 / 1024) + " MB）。请将内容拆分为多次局部写入（write_file 分段 + edit_file 追加替换），"
                    + "或改用命令行工具从外部文件复制生成。");
        }

        // 解析落盘编码参数：默认 utf-8；显式指定（如 gbk）即执行编码转换，非法字符集名直接报错
        Charset writeCharset = StandardCharsets.UTF_8;
        boolean explicitEncoding = writeArgs.hasExplicitEncoding();
        String encodingVal = writeArgs.encoding();
        if (explicitEncoding) {
            try {
                writeCharset = Charset.forName(encodingVal);
            } catch (Exception e) {
                return ToolResult.error("参数 'encoding' 的值 '" + encodingVal + "' 不是合法字符集名。常用取值：utf-8（默认）、gbk。");
            }
        }

        try {
            File file = resolveFile(pathVal, agentContext);
            File parent = file.getParentFile();

            // 覆写已有非空文件的强制安全门禁：write_file 的破坏力最大（可推平全文且无 oldContent 自证），
            // 要求"先读后写"确保模型见过现有内容才允许覆写；新建文件与空文件（无内容可毁）豁免。
            // edit_file 不设此门禁：其 oldContent 唯一匹配本身即是更强的局部自证（CAS 语义）。
            if (file.exists() && file.length() > 0) {
                String guardError = verifyReadBeforeWrite(agentContext, file);
                if (guardError != null) {
                    return guardError;
                }
            }

            if (parent != null && !parent.exists()) {
                if (!parent.mkdirs()) {
                    return ToolResult.error("无法创建父目录: " + parent.getAbsolutePath());
                }
                log.info("自动创建了目录: {}", parent.getAbsolutePath());
            }

            // 覆写已有文件时的文本元数据保真：EOL 与 UTF-8 BOM 跟随原文件（新建文件按模型内容原样写入），
            // 防止 write_file 整文件覆写把 CRLF 老文件转成 LF、或丢失原 BOM
            String encodingNotice = "";
            if (file.exists()) {
                NativeCharsetKit.FileTextMeta meta = NativeCharsetKit.detectFileMeta(file.toPath());
                if (meta.utf16Bom()) {
                    // UTF-16 文件无法按原编码覆写，放行但显式告知编码将变更（显式指定目标编码时同样告知）
                    encodingNotice = "（注意：原文件为 UTF-16 编码，本次覆写后文件已变更为 " + writeCharset.name() + " 编码）";
                } else if (explicitEncoding) {
                    // 显式编码转换：用户有意为之，按需保留 EOL 风格；若目标仍为 UTF-8 且原文件有 BOM，继续保真保留
                    contentVal = NativeCharsetKit.normalizeEolToStyle(contentVal, meta.eolStyle());
                    if (StandardCharsets.UTF_8.equals(writeCharset) && meta.hasUtf8Bom() && !contentVal.startsWith("\uFEFF")) {
                        contentVal = "\uFEFF" + contentVal;
                    }
                } else if (!StandardCharsets.UTF_8.name().equalsIgnoreCase(meta.charset().name())) {
                    // 隐式转码告知：默认 UTF-8 落盘但原文件为 GBK 等其他编码，覆写后编码将变更，显式提示防静默转码
                    encodingNotice = "（注意：原文件为 " + meta.charset().name() + " 编码，本次覆写后文件已变更为 UTF-8 编码）";
                    contentVal = NativeCharsetKit.normalizeEolToStyle(contentVal, meta.eolStyle());
                    if (meta.hasUtf8Bom() && !contentVal.startsWith("\uFEFF")) {
                        contentVal = "\uFEFF" + contentVal;
                    }
                } else {
                    // 同编码覆写：EOL 与 BOM 完整保真
                    contentVal = NativeCharsetKit.normalizeEolToStyle(contentVal, meta.eolStyle());
                    if (meta.hasUtf8Bom() && !contentVal.startsWith("\uFEFF")) {
                        contentVal = "\uFEFF" + contentVal;
                    }
                }
            }

            Files.writeString(file.toPath(), contentVal, writeCharset);

            // 写入成功后记录内容哈希：write_file 产物天然是最新上下文，后续 edit 修改无需重复 read_file
            recordFileHash(agentContext, file);

            // 落盘后体积复核：编码转换（如 UTF-8 → GBK）可能显著改变字节数，
            // 超出可读上限则产出的是后续读不回来的"盲区文件"，需明确告警
            String sizeNotice = "";
            if (file.length() > MAX_WRITE_FILE_BYTES) {
                sizeNotice = "（注意：落盘体积约 " + (file.length() / 1024 / 1024)
                        + " MB，已超出 read_file 的 100 MB 读取上限，后续将无法通过 read_file 读回该文件）";
                log.warn("WriteFileTool 落盘文件超出读取上限: path={}, size={}", pathVal, file.length());
            }

            log.info("WriteFileTool 执行成功: {}", pathVal);
            return new JSONObject()
                    .fluentPut("success", true)
                    .fluentPut("message", "文件写入成功: " + pathVal + encodingNotice + sizeNotice)
                    .fluentPut("bytesWritten", file.length())
                    .toJSONString();

        } catch (IOException e) {
            log.error("WriteFileTool 写入异常", e);
            return ToolResult.error("写入文件失败: " + getSafeErrorMessage(e));
        }
    }

    /**
     * 解析用于体量判定的目标字符集：显式 encoding 优先，非法或未指定时回退 UTF-8。
     * <p>
     * 仅用于换算字节数，不作为实际落盘编码（落盘编码在后续流程中单独解析并做合法性报错）。
     * </p>
     */
    private Charset resolveSizeProbeCharset(WriteFileArgs writeArgs) {
        if (writeArgs.hasExplicitEncoding() && writeArgs.encoding() != null) {
            try {
                return Charset.forName(writeArgs.encoding());
            } catch (Exception ignored) {
                // 非法编码名：交由后续正式解析流程给出明确报错，此处按 UTF-8 估算
            }
        }
        return StandardCharsets.UTF_8;
    }
}
