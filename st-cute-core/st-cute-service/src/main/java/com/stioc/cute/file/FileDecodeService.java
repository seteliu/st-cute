package com.stioc.cute.file;

import com.stioc.cute.engine.llm.types.CuteAttachment;
import com.stioc.cute.file.decode.FileDecoder;
import com.stioc.cute.file.types.DecodeParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件解码调度与管理服务。
 * 统筹所有文件解码器，负责文件格式匹配、多附件内容提取、大模型上下文截断保护与异常容错
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileDecodeService {

    /**
     * 单个附件提取文本的最大字符数上限（默认 100000 字符），防止撑爆大模型上下文窗口
     */
    public static final int DEFAULT_MAX_EXTRACT_CHARS = 100000;

    private final List<FileDecoder> decoders;

    /**
     * 解码指定文件为大模型多附件列表（一个文件可衍生多个附件：文本块 + 内嵌图片等）。
     * 无匹配解码器时返回格式说明附件；解码异常返回错误提示附件，保证调用方拿到非空列表
     *
     * @param file        物理文件
     * @param extension   文件扩展名
     * @param mimeType    MIME 类型
     * @param decodeParam 解码参数（allowImage 控制图片产出、maxChars 控制截断）
     * @return 附件列表（至少一个文本类附件，图片类附件取决于 allowImage 与文档内容）
     */
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
            // 兜底降级场景：错误信息已足够定位，无需打印完整堆栈，避免日志噪音
            log.warn("解码文件内容异常: filename={}, ext={}, error={}", file.getName(), extension, e.getMessage());
            return List.of(CuteAttachment.builder()
                    .name(displayName)
                    .path(file.getAbsolutePath())
                    .isImage(false)
                    .textContent(String.format("[附件解析提示: 文件 %s 解析失败，可能文件已损坏或包含密码保护 (%s)]", displayName, e.getMessage()))
                    .build());
        }
    }

    /**
     * 文本字符长度超限保护截断
     */
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
