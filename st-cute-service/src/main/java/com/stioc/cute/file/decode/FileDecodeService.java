package com.stioc.cute.file.decode;

import com.stioc.cute.file.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.List;

/**
 * 文件解码调度与管理服务
 * 统筹管理所有文件解码器，负责文件格式匹配、内容提取、大模型上下文截断保护与异常容错
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileDecodeService {

    /**
     * 单个附件提取文本的最大字符数上限（默认 60000 字符），防止撑爆大模型上下文窗口
     */
    public static final int DEFAULT_MAX_EXTRACT_CHARS = 60000;

    private final List<FileDecoder> decoders;

    /**
     * 判断是否支持该文件格式的文本提取解码
     *
     * @param extension 文件小写后缀（不带点）
     * @param mimeType  MIME 类型
     * @return 是否支持解码
     */
    public boolean isSupported(String extension, String mimeType) {
        if (!StringUtils.hasText(extension) && !StringUtils.hasText(mimeType)) {
            return false;
        }
        for (FileDecoder decoder : decoders) {
            if (decoder.supports(extension, mimeType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解码指定文件为结构化纯文本
     *
     * @param file 物理文件
     * @return 提取并格式化后的文本内容（已附带超长截断保护）
     */
    public String decode(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return "";
        }
        String ext = FileStorageService.getFileExtension(file.getName());
        String mimeType = FileStorageService.detectMimeType(ext);
        return decode(file, ext, mimeType, DEFAULT_MAX_EXTRACT_CHARS);
    }

    /**
     * 解码指定文件为结构化纯文本
     *
     * @param file          物理文件
     * @param extension     文件扩展名
     * @param mimeType      MIME 类型
     * @param maxCharacters 最大允许字符数
     * @return 解码后的文本内容
     */
    public String decode(File file, String extension, String mimeType, int maxCharacters) {
        if (file == null || !file.exists() || !file.isFile()) {
            return "";
        }

        FileDecoder matchedDecoder = null;
        for (FileDecoder decoder : decoders) {
            if (decoder.supports(extension, mimeType)) {
                matchedDecoder = decoder;
                break;
            }
        }

        if (matchedDecoder == null) {
            log.debug("未找到针对该文件格式的专用解码器: filename={}, ext={}, mimeType={}", file.getName(), extension, mimeType);
            return String.format("[附件文件: %s (格式: %s，大小: %d 字节，暂不支持内容直接解析)]", file.getName(), extension, file.length());
        }

        try {
            String content = matchedDecoder.decode(file);
            if (!StringUtils.hasText(content)) {
                return String.format("[附件文件: %s 内容为空]", file.getName());
            }
            return truncateIfNecessary(content, maxCharacters);
        } catch (Exception e) {
            log.warn("解码文件内容异常: filename={}, ext={}, error={}", file.getName(), extension, e.getMessage(), e);
            return String.format("[附件解析提示: 文件 %s 解析失败，可能文件已损坏或包含密码保护 (%s)]", file.getName(), e.getMessage());
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
}
