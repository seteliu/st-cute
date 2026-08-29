package com.stioc.cute.file.decode;

import java.io.File;

/**
 * 文件内容解码器统一接口
 * 用于将各种物理文件（文档、表格、幻灯片、纯文本等）解析转换为大模型可直接理解的结构化文本
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
     * 将文件解码提取为结构化文本
     *
     * @param file 物理文件对象
     * @return 解码后的纯文本或 Markdown 文本
     * @throws Exception 解码异常
     */
    String decode(File file) throws Exception;
}
