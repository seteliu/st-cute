package com.stioc.cute.file.decode;

import com.stioc.cute.engine.llm.types.CuteAttachment;
import com.stioc.cute.file.types.DecodeParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片文件解码器单元测试。
 * 覆盖常见图片扩展名与 MIME 支持判定、非视觉模型占位说明与多模态 Base64 提取。
 */
class ImageFileDecoderTest {

    private final ImageFileDecoder decoder = new ImageFileDecoder();

    @ParameterizedTest(name = "支持常见图片扩展名: {0}")
    @ValueSource(strings = {"png", "jpg", "jpeg", "webp", "gif", "bmp", "PNG", "JPEG"})
    @DisplayName("常见图片格式支持判定")
    void supportsImageFormats(String ext) {
        assertTrue(decoder.supports(ext, null));
    }

    @Test
    @DisplayName("非图片格式返回 false")
    void nonImageReturnsFalse() {
        assertFalse(decoder.supports("txt", null));
        assertFalse(decoder.supports("pdf", null));
        assertFalse(decoder.supports("docx", null));
    }

    @Test
    @DisplayName("非多模态模式（allowImage=false）返回占位文本附件")
    void nonVisionModelProducesPlaceholder(@TempDir Path tempDir) throws Exception {
        Path imgPath = tempDir.resolve("icon.png");
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", imgPath.toFile());

        DecodeParam param = DecodeParam.builder()
                .allowImage(false)
                .sourceName("icon.png")
                .build();

        List<CuteAttachment> attachments = decoder.decodeToAttachments(imgPath.toFile(), param);
        assertNotNull(attachments);
        assertEquals(1, attachments.size());

        CuteAttachment att = attachments.get(0);
        assertFalse(att.isImage());
        assertTrue(att.getTextContent().contains("当前模型不支持视觉能力"));
    }

    @Test
    @DisplayName("多模态模式（allowImage=true）提取 Base64 图片附件")
    void visionModelProducesBase64Attachment(@TempDir Path tempDir) throws Exception {
        Path imgPath = tempDir.resolve("chart.png");
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", imgPath.toFile());

        DecodeParam param = DecodeParam.builder()
                .allowImage(true)
                .sourceName("chart.png")
                .build();

        List<CuteAttachment> attachments = decoder.decodeToAttachments(imgPath.toFile(), param);
        assertNotNull(attachments);
        assertEquals(1, attachments.size());

        CuteAttachment att = attachments.get(0);
        assertTrue(att.isImage());
        assertNotNull(att.getBase64Data());
        assertFalse(att.getBase64Data().isBlank());
        assertTrue(att.getSize() > 0);
    }
}
