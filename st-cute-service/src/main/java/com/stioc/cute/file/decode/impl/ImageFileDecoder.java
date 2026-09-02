package com.stioc.cute.file.decode.impl;

import com.stioc.cute.file.access.FileStorageService;
import com.stioc.cute.file.ImageProcessUtils;
import com.stioc.cute.file.access.DecodeParam;
import com.stioc.cute.file.decode.FileDecoder;
import com.stioc.cute.llm.CuteAttachment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;

/**
 * 图片文件解码器
 * 将 jpg/jpeg/png/webp/gif/bmp 图片转为 base64 多模态附件；
 * 压缩策略统一走 ImageProcessUtils.compressIfNeeded（小文件跳过，大图按平台统一规格压缩并转码 JPEG，GIF 取首帧）；
 * 非多模态模型（allowImage=false）返回占位文本说明
 */
@Slf4j
@Component
public class ImageFileDecoder implements FileDecoder {

    @Override
    public boolean supports(String extension, String mimeType) {
        return ImageProcessUtils.isImage(extension, mimeType);
    }

    @Override
    public List<CuteAttachment> decodeToAttachments(File file, DecodeParam ctx) throws Exception {
        String displayName = ctx != null && ctx.getSourceName() != null ? ctx.getSourceName() : file.getName();

        // 非多模态模型：无法消费图片，返回占位文本
        if (ctx == null || !ctx.isAllowImage()) {
            return List.of(CuteAttachment.builder()
                    .name(displayName)
                    .path(file.getAbsolutePath())
                    .size(file.length())
                    .isImage(false)
                    .textContent(String.format("[图片文件: %s，当前模型不支持视觉能力，无法查看图片内容]", displayName))
                    .build());
        }

        byte[] raw = Files.readAllBytes(file.toPath());
        String ext = FileStorageService.getFileExtension(file.getName());

        // 智能压缩：GIF 与小文件（含上传链路已压产物）自动跳过，仅大图按统一规格压缩
        byte[] data = ImageProcessUtils.compressIfNeeded(raw, ext);
        if (data == null || data.length == 0) {
            data = raw;
        }

        // 压缩可能转码（无透明 png/bmp → jpg），以真实字节格式推导 MIME，防止 MIME 与实际编码不一致
        String realFormat = ImageProcessUtils.detectImageFormat(data);
        String effectiveExt = StringUtils.hasText(realFormat) ? realFormat : ext;
        String mimeType = FileStorageService.detectMimeType(effectiveExt);

        log.info("ImageFileDecoder 图片加载成功: {}, 原始 {} bytes, 处理后 {} bytes, 格式 {} -> {}",
                file.getName(), raw.length, data.length, ext, effectiveExt);

        return List.of(CuteAttachment.builder()
                .name(displayName)
                .path(file.getAbsolutePath())
                .size((long) data.length)
                .mimeType(mimeType)
                .isImage(true)
                .base64Data(Base64.getEncoder().encodeToString(data))
                .build());
    }
}
