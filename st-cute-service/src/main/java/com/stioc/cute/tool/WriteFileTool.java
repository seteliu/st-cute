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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 新建或覆写本地文件内容物理落地修改工具
 */
@Slf4j
@Component
public class WriteFileTool implements CuteTool {

    @Resource
    private WorkspacePathResolver workspacePathResolver;

    @Override
    public boolean isWriteTool() {
        return true;
    }

    @Override
    public String getName() {
        return ToolNames.WRITE_TO_FILE;
    }

    @Override
    public String getDescription() {
        return "覆盖写入文件或新建文件。如果文件所在父目录不存在，将自动级联创建父目录。";
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
            "content": {
              "type": "string",
              "description": "需要写入文件的完整文本内容"
            }
          },
          "required": ["path", "content"]
        }
        """;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        String pathVal = (String) arguments.get("path");
        String contentVal = (String) arguments.get("content");

        if (pathVal == null || pathVal.isBlank()) {
            return new JSONObject().fluentPut("error", "参数 'path' 不能为空。").toJSONString();
        }
        if (contentVal == null) {
            contentVal = "";
        }

        try {
            File file = workspacePathResolver.resolvePath(pathVal, agentContext).toFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                if (!parent.mkdirs()) {
                    return new JSONObject().fluentPut("error", "无法创建父目录: " + parent.getAbsolutePath()).toJSONString();
                }
                log.info("自动创建了目录: {}", parent.getAbsolutePath());
            }

            // 覆写已有文件时的文本元数据保真：EOL 与 UTF-8 BOM 跟随原文件（新建文件按模型内容原样写入），
            // 防止 write_to_file 整文件覆写把 CRLF 老文件转成 LF、或丢失原 BOM
            String encodingNotice = "";
            if (file.exists()) {
                NativeCharsetKit.FileTextMeta meta = NativeCharsetKit.detectFileMeta(file.toPath());
                if (meta.utf16Bom()) {
                    // UTF-16 文件无法按原编码覆写（本工具仅支持 UTF-8 落盘），放行但显式告知编码将变更
                    encodingNotice = "（注意：原文件为 UTF-16 编码，本次覆写后文件已变更为 UTF-8 编码）";
                } else {
                    // EOL 保真：按原文件主导风格归一（内置防重复归一，模型已传 CRLF 不会二次转换）
                    contentVal = NativeCharsetKit.normalizeEolToStyle(contentVal, meta.eolStyle());
                    // BOM 保真：原文件含 UTF-8 BOM 时补回（读取侧透明化剥离后模型内容天然不含 BOM）
                    if (meta.hasUtf8Bom() && !contentVal.startsWith("\uFEFF")) {
                        contentVal = "\uFEFF" + contentVal;
                    }
                }
            }

            Files.writeString(file.toPath(), contentVal, StandardCharsets.UTF_8);

            // 写入成功后记录内容哈希：write_to_file 产物天然是最新上下文，后续 replace 修改无需重复 read_file
            if (agentContext != null) {
                agentContext.getReadFiles().put(file.getAbsolutePath(),
                        com.stioc.cute.security.access.FileHashSupport.computeFileHash(file));
            }

            log.info("WriteFileTool 执行成功: {}", pathVal);
            return new JSONObject()
                    .fluentPut("success", true)
                    .fluentPut("message", "已成功写入文件: " + pathVal + encodingNotice)
                    .toJSONString();
        } catch (IOException e) {
            log.error("WriteFileTool 写入异常", e);
            return new JSONObject().fluentPut("error", "写入文件失败: " + e.getMessage()).toJSONString();
        }
    }
}
