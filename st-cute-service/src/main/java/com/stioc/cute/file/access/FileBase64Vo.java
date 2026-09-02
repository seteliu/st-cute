package com.stioc.cute.file.access;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件 Base64 编码数据传输对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileBase64Vo {

    /**
     * 文件 MIME 类型
     */
    private String mimeType;

    /**
     * 文件的 Base64 编码字符串
     */
    private String base64;

    /**
     * 文件大小（字节数）
     */
    private Long size;

    /**
     * 原始文件名（若有）
     */
    private String name;
}
