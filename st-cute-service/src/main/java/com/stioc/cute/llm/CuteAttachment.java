package com.stioc.cute.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 大模型交互中携带的多模态附件信息模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CuteAttachment {

    /**
     * 附件原始文件名
     */
    private String name;

    /**
     * 附件相对存储路径，如 .st-cute/files/cid_1/xxx.png
     */
    private String path;

    /**
     * 附件 MIME 类型，如 image/png, application/pdf
     */
    private String mimeType;

    /**
     * 附件字节大小
     */
    private Long size;

    /**
     * 是否为图片类型
     */
    private boolean isImage;

    /**
     * 文件二进制内容的 Base64 编码字符串（主要供图片、PDF 使用）
     */
    private String base64Data;

    /**
     * 文本类附件的纯文本内容（主要供 txt, md, 代码等文本文件内联嵌入 Prompt）
     */
    private String textContent;
}
