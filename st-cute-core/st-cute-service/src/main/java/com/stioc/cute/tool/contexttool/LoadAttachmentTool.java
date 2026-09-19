package com.stioc.cute.tool.contexttool;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.llm.types.CuteAttachment;
import com.stioc.cute.engine.llm.types.Provider;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolResult;
import com.stioc.cute.file.FileDecodeService;
import com.stioc.cute.file.FileStorageService;
import com.stioc.cute.file.types.DecodeParam;
import com.stioc.cute.project.ProjectService;
import com.stioc.cute.provider.ProviderService;
import com.stioc.cute.tool.ToolNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 通用文件加载工具（多模态视觉与文档内容提取入口）。
 * 支持项目相对路径与绝对路径两种形态。
 * 图片/PDF/Word 等文件经解码服务处理后以多模态附件形式投喂给大模型（需模型支持视觉能力），
 * 文档内嵌图片与扫描页会自动衍生为独立图片附件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoadAttachmentTool implements CuteTool {

    private final FileStorageService fileStorageService;
    private final FileDecodeService fileDecodeService;
    private final ProviderService providerService;
    private final ProjectService projectService;

    @Override
    public String getRawName() {
        return ToolNames.LOAD_ATTACHMENT;
    }

    @Override
    public String getDescription() {
        return "加载并查看文件内容（支持图片、PDF、Word、Excel、PPT、代码、文本、数据文件等）。"
                + "文件将以多模态附件形式注入你的上下文：图片直接以视觉内容呈现（需当前模型支持视觉），"
                + "PDF/Word 提取全文文本且内嵌图片/扫描页自动衍生为图片附件，Excel/PPT 转结构化文本。"
                + "【重要使用准则】"
                + "1. 历史消息中 [📎 历史附件] 列出的路径可随时按路径重新加载查看；"
                + "2. 当前最新一轮用户消息直接附带的附件已注入上下文，无需重复调用本工具。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "目标文件路径，支持项目相对路径（以项目根目录为基准）或绝对路径。也可来自历史消息 [📎 历史附件] 列出的路径。目标必须真实存在且为文件"
            }
          },
          "required": ["path"]
        }
        """;
    }

    @Override
    public ToolAccessLevel getAccessLevel() {
        // 只读：仅加载文件内容注入上下文，无外部副作用
        return ToolAccessLevel.READ;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        LoadAttachmentArgs attArgs = LoadAttachmentArgs.from(arguments);
        String path = attArgs.path();
        if (!StringUtils.hasText(path)) {
            return ToolResult.error("参数 path 不能为空");
        }

        AgentContext agentContext = context.agentContext();
        try {
            // 路径解析：绝对路径或项目相对路径（基准复用工作区解析器：会话绑定项目根）
            String baseDir = projectService.getProjectBasePath(agentContext);
            File file = fileStorageService.resolveFlexiblePath(path, baseDir);
            if (file == null) {
                return ToolResult.error("文件不存在或路径非法: " + path,
                        obj -> obj.fluentPut("status", "error").fluentPut("path", path));
            }

            String ext = FileStorageService.getFileExtension(file.getName());
            String mimeType = FileStorageService.detectMimeType(ext);
            long fileSize = file.length();

            // 视觉能力跟随当前 Provider 配置
            boolean allowImage = false;
            if (agentContext != null) {
                Provider activeConfig = providerService.getProviderConfigForContext(agentContext);
                allowImage = activeConfig != null && Boolean.TRUE.equals(activeConfig.getMultimodal());
            }

            // 统一交由解码服务：单文件可衍生多附件（文本块 + 内嵌图片）
            List<CuteAttachment> attachments = fileDecodeService.decodeToAttachments(file, ext, mimeType,
                    DecodeParam.builder()
                            .allowImage(allowImage)
                            .maxChars(FileDecodeService.DEFAULT_MAX_EXTRACT_CHARS)
                            .sourceName(file.getName())
                            .build());

            JSONArray attachmentArr = new JSONArray();
            int imageCount = 0;
            int textCount = 0;
            for (CuteAttachment att : attachments) {
                JSONObject attObj = new JSONObject();
                if (att.isImage()) {
                    imageCount++;
                    attObj.put("type", "image");
                } else {
                    textCount++;
                    attObj.put("type", "text");
                    // 文本内容已在解码服务截断保护，直接携带便于当轮阅读
                    attObj.put("preview", truncate(att.getTextContent(), 2000));
                }
                attObj.put("name", att.getName());
                attObj.put("mimeType", att.getMimeType());
                attachmentArr.add(attObj);
            }

            // 构建附件元数据并挂载至执行上下文，供消息落库回填
            JSONArray attMetaArr = new JSONArray();
            JSONObject attMetaObj = new JSONObject();
            attMetaObj.put("path", path);
            attMetaObj.put("name", file.getName());
            attMetaObj.put("size", fileSize);
            attMetaObj.put("mimeType", mimeType);
            attMetaArr.add(attMetaObj);
            context.setAttachments(attMetaArr.toJSONString());

            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("path", path);
            result.put("name", file.getName());
            result.put("size", fileSize);
            result.put("mimeType", mimeType);
            result.put("allowImage", allowImage);
            result.put("attachmentCount", attachments.size());
            result.put("imageCount", imageCount);
            result.put("textCount", textCount);
            result.put("attachments", attachmentArr);
            result.put("message", String.format("已加载文件 %s：共衍生 %d 个附件（图片 %d，文本 %d），"
                            + "已全部注入上下文，可直接基于其内容进行分析",
                    file.getName(), attachments.size(), imageCount, textCount));

            log.info("LoadAttachmentTool 成功加载文件: path={}, 衍生附件 {} 个（图片 {}，文本 {}）",
                    path, attachments.size(), imageCount, textCount);
            return result.toJSONString();

        } catch (Exception e) {
            log.warn("LoadAttachmentTool 加载文件失败: path={}, error={}", path, e.getMessage());
            return ToolResult.error("加载文件失败: " + e.getMessage(),
                    obj -> obj.fluentPut("status", "error").fluentPut("path", path));
        }
    }

    /**
     * 预览文本截断
     */
    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + String.format("... [预览截断，完整内容共 %d 字符已注入上下文]", text.length());
    }
}
