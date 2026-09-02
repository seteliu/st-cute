package com.stioc.cute.file.access;

import lombok.Builder;
import lombok.Data;

/**
 * 文件解码参数
 * 控制解码器的多附件产出行为：是否允许产出图片附件、文本截断上限等。
 * 一个物理文件经解码后可衍生多个附件（如：文档文本块 + 若干内嵌图片）
 */
@Data
@Builder
public class DecodeParam {

    /**
     * 是否允许产出图片类附件（通常仅多模态模型开启）。
     * 关闭时扫描页/内嵌图片仅生成占位说明文本，不提取图像数据
     */
    private boolean allowImage;

    /**
     * 文本内容最大字符数上限，超过将被截断保护（<=0 时由 FileDecodeService 兜底默认值）
     */
    private int maxChars;

    /**
     * 源文件显示名（用于占位符与附件命名）
     */
    private String sourceName;

    /**
     * 构建纯文本模式参数（禁止图片产出，截断由服务层兜底）
     */
    public static DecodeParam textOnly(String sourceName) {
        return DecodeParam.builder()
                .allowImage(false)
                .maxChars(0)
                .sourceName(sourceName)
                .build();
    }
}
