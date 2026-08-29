package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.file.FileStorageService;
import com.stioc.cute.file.ImageProcessUtils;
import com.stioc.cute.file.decode.FileDecodeService;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolExecutionContext;
import com.stioc.cute.tool.access.ToolNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.Map;

/**
 * 加载历史会话中上传的附件文件（支持图片、文档、表格、幻灯片、代码、文本等）。
 * 解决长会话中历史附件按需回读与 Token 经济性问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoadAttachmentTool implements CuteTool {

    private final FileStorageService fileStorageService;
    private final FileDecodeService fileDecodeService;

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getName() {
        return ToolNames.LOAD_ATTACHMENT;
    }

    @Override
    public String getDescription() {
        return "【安全核心工具】加载并查看历史会话中上传的附件文件（支持图片、代码、文本、数据文件等）。"
                + "【重要使用准则】"
                + "1. 用户在当前最新一轮发送的图片或文件已直接作为多模态内容送入上下文，你可直接观察和回答，严禁对当前最新消息的附件调用本工具；"
                + "2. 本工具仅用于在多轮长对话中，当某条历史消息明确标注了 '[📎 历史附件]' 且给出了具体相对路径时，如果需要重新审视该历史附件细节才可调用；"
                + "3. 严禁凭空猜测或臆造文件路径，path 必须严格来自历史消息中明确给出的路径字符串。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "历史消息中 [📎 历史附件] 明确列出的相对路径，必填。严禁自行猜测或编造路径。"
            },
            "reason": {
              "type": "string",
              "description": "需要重新查看该历史附件的具体原因说明，可选"
            }
          },
          "required": ["path"]
        }
        """;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        if (arguments == null || !arguments.containsKey("path")) {
            return new JSONObject().fluentPut("error", "参数 path 不能为空").toJSONString();
        }

        String path = String.valueOf(arguments.get("path")).trim();
        if (!StringUtils.hasText(path)) {
            return new JSONObject().fluentPut("error", "参数 path 不能为空").toJSONString();
        }

        try {
            File file = fileStorageService.getSafeFile(path);
            String ext = FileStorageService.getFileExtension(file.getName());
            String mimeType = FileStorageService.detectMimeType(ext);
            long fileSize = file.length();

            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("name", file.getName());
            result.put("path", path);
            result.put("size", fileSize);
            result.put("mimeType", mimeType);

            boolean isImg = ImageProcessUtils.isImage(ext, mimeType);
            if (isImg) {
                result.put("type", "image");
                result.put("message", "已成功定位并加载历史图片附件: " + file.getName());
                log.info("LoadAttachmentTool 成功定位图片附件: path={}, size={}", path, fileSize);
                return result.toJSONString();
            }

            // 对非图片文件（文档、表格、幻灯片、纯文本、代码等）调用解码服务提取文本
            String decodedContent = fileDecodeService.decode(file, ext, mimeType, FileDecodeService.DEFAULT_MAX_EXTRACT_CHARS);
            result.put("type", "text");
            result.put("content", decodedContent);
            result.put("message", "已成功读取并解码附件内容: " + file.getName());
            log.info("LoadAttachmentTool 成功加载并解码附件: path={}, size={}, decodedChars={}", path, fileSize, decodedContent.length());
            return result.toJSONString();

        } catch (Exception e) {
            log.warn("LoadAttachmentTool 加载附件失败: path={}, error={}", path, e.getMessage());
            return new JSONObject()
                    .fluentPut("status", "error")
                    .fluentPut("path", path)
                    .fluentPut("error", "加载附件失败: " + e.getMessage())
                    .toJSONString();
        }
    }
}
