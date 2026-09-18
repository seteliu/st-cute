package com.stioc.cute.runtime.loop;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.llm.types.CuteAttachment;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.service.types.DecodeParam;
import com.stioc.cute.service.FileDecodeService;
import com.stioc.cute.service.FileStorageService;
import com.stioc.cute.project.ProjectService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 附件内容装载服务：包装宿主文件存储、解码服务与工作区路径解析，
 * 供用户/工具消息附件拦截器完成附件物理数据解码与历史占位符渲染。
 */
@Slf4j
@Component
public class AttachmentContentLoader {

    @Resource
    private FileStorageService fileStorageService;
    @Resource
    private FileDecodeService fileDecodeService;
    @Resource
    private ProjectService projectService;

    public List<CuteAttachment> loadAttachments(String rawAttachments, AgentContext context, boolean allowImage) {
        List<CuteAttachment> list = new ArrayList<>();
        if (!StringUtils.hasText(rawAttachments)) {
            return list;
        }

        String baseDir = getProjectBasePath(context);

        try {
            JSONArray arr = JSON.parseArray(rawAttachments.trim());
            if (arr == null || arr.isEmpty()) {
                return list;
            }
            for (int i = 0; i < arr.size(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                if (obj == null) {
                    continue;
                }
                String path = obj.getString("path");
                String name = obj.getString("name");
                String mimeType = obj.getString("mimeType");
                if (!StringUtils.hasText(path)) {
                    continue;
                }
                try {
                    // 路径解析：绝对路径或项目相对路径（经宿主文件服务）
                    File file = resolveFlexiblePath(path, baseDir);
                    if (file == null) {
                        // 文件已被删除或路径非法：不静默跳过，生成占位附件让模型明确感知该附件当前不可用。
                        // 措辞必须带上时间语义：消息落库当时附件可能是加载成功的（如临时文件事后被清理），
                        // 若只说「无法加载内容」会与消息正文中「已成功注入」的记录自相矛盾，误导模型推翻有效结论
                        log.warn("附件文件不存在或路径非法，生成占位提示: path={}", path);
                        String displayName = StringUtils.hasText(name) ? name : path;
                        list.add(CuteAttachment.builder()
                                .name(displayName)
                                .path(path)
                                .mimeType(mimeType)
                                .isImage(false)
                                .textContent(String.format(
                                        "[📎 附件 %s 当前不可用：原文件已不存在或路径非法（可能已被清理/删除）。"
                                                + "本消息正文中当时已成功加载的内容仍然有效，请勿仅凭附件缺失推翻历史结论；"
                                                + "如确需附件内容，请重新获取文件或让用户再次提供。]", displayName))
                                .build());
                        continue;
                    }

                    // 统一交由解码服务处理：文本附件 + 图片衍生附件（allowImage 跟随多模态能力）
                    list.addAll(decodeToAttachments(file, name, mimeType, allowImage));
                } catch (Exception e) {
                    log.warn("加载消息附件数据异常: path={}, error={}", path, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("解析消息附件 JSON 异常: json={}", rawAttachments, e);
        }
        return list;
    }

    public String buildAttachmentPlaceholder(String rawAttachments) {
        if (!StringUtils.hasText(rawAttachments)) {
            return null;
        }
        try {
            JSONArray arr = JSON.parseArray(rawAttachments.trim());
            if (arr == null || arr.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("[📎 历史附件（已省略具体内容以节省上下文，需要时可使用 load_attachment 工具按路径加载）]:\n");
            for (int i = 0; i < arr.size(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                if (obj == null) {
                    continue;
                }
                String name = obj.getString("name");
                String path = obj.getString("path");
                Long size = obj.getLong("size");
                String mimeType = obj.getString("mimeType");

                String sizeStr = size != null ? formatFileSize(size) : "未知大小";
                String typeStr = "文件";
                if (mimeType != null && mimeType.startsWith("image/")) {
                    typeStr = "图片";
                } else if ("application/pdf".equalsIgnoreCase(mimeType)) {
                    typeStr = "PDF文档";
                }

                sb.append(String.format("- 附件 %d: `%s` (%s, %s, 路径: `%s`)\n",
                        i + 1,
                        name != null ? name : "未命名文件",
                        typeStr,
                        sizeStr,
                        path != null ? path : ""));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("构建历史附件占位元数据异常: {}", e.getMessage());
            return null;
        }
    }

    private String getProjectBasePath(AgentContext context) {
        if (context == null || projectService == null) {
            return null;
        }
        try {
            return projectService.getProjectBasePath(context);
        } catch (Exception e) {
            log.warn("解析会话工作区失败，附件相对路径解析降级: cid={}", context.getCid(), e);
            return null;
        }
    }

    private File resolveFlexiblePath(String pathVal, String baseDir) {
        return fileStorageService.resolveFlexiblePath(pathVal, baseDir);
    }

    private List<CuteAttachment> decodeToAttachments(File file, String sourceName, String mimeType, boolean allowImage) {
        String ext = FileStorageService.getFileExtension(file.getName());
        if (!StringUtils.hasText(mimeType)) {
            mimeType = FileStorageService.detectMimeType(ext);
        }
        DecodeParam decodeParam = DecodeParam.builder()
                .allowImage(allowImage)
                .maxChars(FileDecodeService.DEFAULT_MAX_EXTRACT_CHARS)
                .sourceName(sourceName != null ? sourceName : file.getName())
                .build();
        return fileDecodeService.decodeToAttachments(file, ext, mimeType, decodeParam);
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format("%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}
