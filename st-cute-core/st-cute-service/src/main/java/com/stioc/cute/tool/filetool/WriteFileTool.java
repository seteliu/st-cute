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
     * 单次写入体量上限（10MB）：写入内容字符串与目标编码字节副本会同时驻留内存，
     * 超限拒绝写入防止巨内容打满内存，引导分片或外部方式处理
     */
    private static final long MAX_WRITE_CONTENT_SIZE = 10 * 1024 * 1024L;

    @Override
    public String getRawName() {
        return ToolNames.WRITE_FILE;
    }

    @Override
    public String getDescription() {
        return "覆盖写入文件或新建文件。如果文件所在父目录不存在，将自动级联创建父目录。"
                + "默认以 UTF-8 编码落盘；可通过 encoding 参数指定其他编码（如 gbk），实现文件编码转换。";
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

        // 体量防御：超长内容（字符串 + 编码字节副本同时驻留）直接拒绝，防止巨内容打满内存
        if (contentVal.length() > MAX_WRITE_CONTENT_SIZE) {
            return ToolResult.error("写入内容过大（" + contentVal.length() + " 字符），超过 write_file 单次处理上限（"
                    + (MAX_WRITE_CONTENT_SIZE / 1024 / 1024) + " MB）。请将内容拆分为多次局部写入（write_file 分段 + edit_file 追加替换），"
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

            log.info("WriteFileTool 执行成功: {}", pathVal);
            return new JSONObject()
                    .fluentPut("success", true)
                    .fluentPut("message", "文件写入成功: " + pathVal + encodingNotice)
                    .fluentPut("bytesWritten", file.length())
                    .toJSONString();

        } catch (IOException e) {
            log.error("WriteFileTool 写入异常", e);
            return ToolResult.error("写入文件失败: " + getSafeErrorMessage(e));
        }
    }
}
