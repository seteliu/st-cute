package com.stioc.cute.file.decode;

import com.stioc.cute.file.access.DecodeParam;
import com.stioc.cute.llm.CuteAttachment;

import java.io.File;
import java.util.List;

/**
 * 文件内容解码器统一接口
 * 将各类物理文件（文档、表格、幻灯片、图片、纯文本等）解码提取为大模型可消费的附件集合。
 * 一个物理文件可衍生多个附件（如：文档文本块 + 若干内嵌图片）
 */
public interface FileDecoder {

    /**
     * 判断当前解码器是否支持该文件格式
     *
     * @param extension 文件小写扩展名（不带点）
     * @param mimeType  MIME 类型
     * @return 是否支持
     */
    boolean supports(String extension, String mimeType);

    /**
     * 将文件解码提取为大模型多附件列表
     *
     * @param file 物理文件对象
     * @param ctx  解码参数（控制是否允许图片产出、截断上限等），可为 null（视为禁止图片）
     * @return 附件列表（至少包含一个文本说明附件）
     * @throws Exception 解码异常
     */
    List<CuteAttachment> decodeToAttachments(File file, DecodeParam ctx) throws Exception;

    /**
     * 构建单文本附件的便捷方法（供纯文本类解码器复用）
     */
    default CuteAttachment buildTextAttachment(File file, DecodeParam ctx, String content) {
        String displayName = ctx != null && ctx.getSourceName() != null ? ctx.getSourceName() : file.getName();
        return CuteAttachment.builder()
                .name(displayName)
                .path(file.getAbsolutePath())
                .isImage(false)
                .textContent(content != null ? content : "")
                .build();
    }
}
