package com.stioc.cute.file.access;

import java.util.List;

import com.stioc.cute.llm.CuteAttachment;

import java.io.File;

/**
 * 文件解码调度与管理服务接口
 * 统筹所有文件解码器，负责文件格式匹配、多附件内容提取、大模型上下文截断保护与异常容错
 */
public interface FileDecodeService {

    /**
     * 单个附件提取文本的最大字符数上限（默认 100000 字符），防止撑爆大模型上下文窗口
     */
    int DEFAULT_MAX_EXTRACT_CHARS = 100000;

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
    List<CuteAttachment> decodeToAttachments(File file, String extension, String mimeType, DecodeParam decodeParam);

    /**
     * 文本字符长度超限保护截断
     */
    String truncateIfNecessary(String content, int maxCharacters);
}
