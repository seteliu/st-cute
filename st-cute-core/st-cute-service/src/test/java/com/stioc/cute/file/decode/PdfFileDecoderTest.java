package com.stioc.cute.file.decode;

import com.stioc.cute.engine.llm.types.CuteAttachment;
import com.stioc.cute.file.types.DecodeParam;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 文档解码器单元测试。
 * 覆盖 PDF 格式支持判定、动态生成微型 PDF 解析、分页标识与正文提取。
 */
class PdfFileDecoderTest {

    private final PdfFileDecoder decoder = new PdfFileDecoder();

    @Test
    @DisplayName("扩展名与 MIME 支持判定")
    void supportsPdf() {
        assertTrue(decoder.supports("pdf", null));
        assertTrue(decoder.supports("PDF", null));
        assertTrue(decoder.supports("unknown", "application/pdf"));
        assertFalse(decoder.supports("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    @Test
    @DisplayName("动态生成并解析单页 PDF，验证分页标识与正文提取")
    void decodeSinglePagePdf(@TempDir Path tempDir) throws Exception {
        Path pdfPath = tempDir.resolve("sample.pdf");

        // 动态生成包含文本的单页 PDF 文档
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(100, 700);
                stream.showText("Hello ST-Cute Agent PDF Test");
                stream.endText();
            }

            document.save(pdfPath.toFile());
        }

        DecodeParam param = DecodeParam.textOnly("sample.pdf");
        List<CuteAttachment> attachments = decoder.decodeToAttachments(pdfPath.toFile(), param);

        assertNotNull(attachments);
        assertFalse(attachments.isEmpty());

        CuteAttachment textAttachment = attachments.get(0);
        assertFalse(textAttachment.isImage());
        assertNotNull(textAttachment.getTextContent());
        assertTrue(textAttachment.getTextContent().contains("Hello ST-Cute Agent PDF Test"));
        // 验证分页标识
        assertTrue(textAttachment.getTextContent().contains("--- [第 1 页] ---"));
    }
}
