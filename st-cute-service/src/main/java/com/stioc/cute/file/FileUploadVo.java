package com.stioc.cute.file;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件上传成功后返回的元数据传输对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadVo {

    /**
     * 文件相对保存路径，如 .st-cute/files/cid_1/20260828_173852_000_3217.png
     */
    private String path;

    /**
     * 客户端上传时的原始文件名
     */
    private String name;

    /**
     * 文件实际存储大小（字节数）
     */
    private Long size;

    /**
     * 文件的 MIME 类型，如 image/png, application/pdf
     */
    private String mimeType;

    /**
     * 是否经过了图片缩放/质量压缩处理
     */
    private Boolean compressed;
}
