package com.stioc.cute.file;

import com.stioc.cute.file.access.DecodeParam;
import com.stioc.cute.file.access.FileDecodeService;
import com.stioc.cute.file.decode.FileDecoder;
import com.stioc.cute.llm.CuteAttachment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件解码调度与管理服务实现
 * 统筹管理所有文件解码器，负责文件格式匹配、内容提取、大模型上下文截断保护与异常容错
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileDecodeServiceImpl implements FileDecodeService {

    private final List<FileDecoder> decoders;

    @Override
    public List<CuteAttachment> decodeToAttachments(File file, String extension, String mimeType, DecodeParam decodeParam) {
        if (file == null || !file.exists() || !file.isFile()) {
            return List.of();
        }

        FileDecoder matchedDecoder = findDecoder(extension, mimeType);
        String displayName = decodeParam != null && decodeParam.getSourceName() != null ? decodeParam.getSourceName() : file.getName();

        if (matchedDecoder == null) {
            log.debug("未找到针对该文件格式的专用解码器: filename={}, ext={}, mimeType={}", file.getName(), extension, mimeType);
            return List.of(CuteAttachment.builder()
                    .name(displayName)
                    .path(file.getAbsolutePath())
                    .isImage(false)
                    .textContent(String.format("[附件文件: %s (格式: %s，大小: %d 字节，暂不支持内容直接解析)]", displayName, extension, file.length()))
                    .build());
        }

        try {
            List<CuteAttachment> attachments = matchedDecoder.decodeToAttachments(file, decodeParam);
            if (attachments == null || attachments.isEmpty()) {
                return List.of(CuteAttachment.builder()
                        .name(displayName)
                        .path(file.getAbsolutePath())
                        .isImage(false)
                        .textContent(String.format("[附件文件: %s 内容为空]", displayName))
                        .build());
            }

            // 文本附件统一做超长截断保护（图片附件不动）
            int maxChars = decodeParam != null && decodeParam.getMaxChars() > 0 ? decodeParam.getMaxChars() : DEFAULT_MAX_EXTRACT_CHARS;
            List<CuteAttachment> result = new ArrayList<>(attachments.size());
            for (CuteAttachment att : attachments) {
                if (!att.isImage() && att.getTextContent() != null) {
                    att.setTextContent(truncateIfNecessary(att.getTextContent(), maxChars));
                }
                result.add(att);
            }
            return result;
        } catch (Exception e) {
            log.warn("解码文件内容异常: filename={}, ext={}, error={}", file.getName(), extension, e.getMessage(), e);
            return List.of(CuteAttachment.builder()
                    .name(displayName)
                    .path(file.getAbsolutePath())
                    .isImage(false)
                    .textContent(String.format("[附件解析提示: 文件 %s 解析失败，可能文件已损坏或包含密码保护 (%s)]", displayName, e.getMessage()))
                    .build());
        }
    }

    @Override
    public String truncateIfNecessary(String content, int maxCharacters) {
        if (content == null || content.length() <= maxCharacters || maxCharacters <= 0) {
            return content;
        }
        int originalLength = content.length();
        return content.substring(0, maxCharacters) +
                String.format("\n\n... [附件内容过长，已截断显示前 %d 字符，实际总共 %d 字符] ...", maxCharacters, originalLength);
    }

    /**
     * 按扩展名与 MIME 类型匹配第一个支持的解码器
     */
    private FileDecoder findDecoder(String extension, String mimeType) {
        if (!StringUtils.hasText(extension) && !StringUtils.hasText(mimeType)) {
            return null;
        }
        for (FileDecoder decoder : decoders) {
            if (decoder.supports(extension, mimeType)) {
                return decoder;
            }
        }
        return null;
    }
}
