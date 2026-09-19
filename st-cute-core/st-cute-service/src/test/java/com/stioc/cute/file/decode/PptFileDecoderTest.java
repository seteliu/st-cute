package com.stioc.cute.file.decode;

import com.stioc.cute.engine.llm.types.CuteAttachment;
import com.stioc.cute.file.types.DecodeParam;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PPT 演示文稿解码器单元测试。
 * 覆盖 pptx/ppt 格式支持判定、动态生成微型幻灯片、页面标记与文本抽取。
 */
class PptFileDecoderTest {

    private final PptFileDecoder decoder = new PptFileDecoder();

    @Test
    @DisplayName("格式支持与 MIME 判定")
    void supportsPpt() {
        assertTrue(decoder.supports("pptx", null));
        assertTrue(decoder.supports("ppt", null));
        assertTrue(decoder.supports("PPTX", null));
        assertTrue(decoder.supports("unknown", "application/vnd.ms-powerpoint"));
        assertTrue(decoder.supports("unknown", "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        assertFalse(decoder.supports("xlsx", "application/vnd.ms-excel"));
    }

    @Test
    @DisplayName("动态生成微型 PPTX 演示文稿并解码文本与页码说明")
    void decodePptxPresentation(@TempDir Path tempDir) throws Exception {
        Path pptPath = tempDir.resolve("deck.pptx");

        // 动态生成包含单页文本的 pptx
        try (XMLSlideShow slideShow = new XMLSlideShow()) {
            XSLFSlide slide = slideShow.createSlide();
            XSLFTextBox textBox = slide.createTextBox();
            XSLFTextParagraph paragraph = textBox.addNewTextParagraph();
            XSLFTextRun run = paragraph.addNewTextRun();
            run.setText("ST-Cute Slide Presentation Text");

            try (FileOutputStream fos = new FileOutputStream(pptPath.toFile())) {
                slideShow.write(fos);
            }
        }

        DecodeParam param = DecodeParam.textOnly("deck.pptx");
        List<CuteAttachment> attachments = decoder.decodeToAttachments(pptPath.toFile(), param);

        assertNotNull(attachments);
        assertFalse(attachments.isEmpty());

        CuteAttachment att = attachments.get(0);
        assertFalse(att.isImage());
        String content = att.getTextContent();
        assertNotNull(content);

        // 验证总页数说明与正文文本
        assertTrue(content.contains("【PPT 演示文稿解析，共 1 页】"));
        assertTrue(content.contains("ST-Cute Slide Presentation Text"));
    }
}
