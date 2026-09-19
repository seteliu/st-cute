package com.stioc.cute.file.decode;

import com.stioc.cute.engine.llm.types.CuteAttachment;
import com.stioc.cute.file.types.DecodeParam;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
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
 * Word 文档解码器单元测试。
 * 覆盖 docx/doc 格式支持判定、动态生成微型 Word 文档、段落与表格文本抽取。
 */
class WordFileDecoderTest {

    private final WordFileDecoder decoder = new WordFileDecoder();

    @Test
    @DisplayName("格式支持与 MIME 判定")
    void supportsWord() {
        assertTrue(decoder.supports("docx", null));
        assertTrue(decoder.supports("doc", null));
        assertTrue(decoder.supports("DOCX", null));
        assertTrue(decoder.supports("unknown", "application/msword"));
        assertTrue(decoder.supports("unknown", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertFalse(decoder.supports("pdf", "application/pdf"));
    }

    @Test
    @DisplayName("动态生成微型 Word 文档并解码段落与表格内容")
    void decodeWordDocument(@TempDir Path tempDir) throws Exception {
        Path wordPath = tempDir.resolve("sample.docx");

        // 动态生成包含段落与表格的 docx 文档
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText("This is an intro paragraph for ST-Cute.");

            XWPFTable table = document.createTable();
            XWPFTableRow header = table.getRow(0);
            header.getCell(0).setText("Task");
            header.addNewTableCell().setText("Status");

            XWPFTableRow row = table.createRow();
            row.getCell(0).setText("L3 Test Suite");
            row.getCell(1).setText("In Progress");

            try (FileOutputStream fos = new FileOutputStream(wordPath.toFile())) {
                document.write(fos);
            }
        }

        DecodeParam param = DecodeParam.textOnly("sample.docx");
        List<CuteAttachment> attachments = decoder.decodeToAttachments(wordPath.toFile(), param);

        assertNotNull(attachments);
        assertFalse(attachments.isEmpty());

        CuteAttachment att = attachments.get(0);
        assertFalse(att.isImage());
        String content = att.getTextContent();
        assertNotNull(content);

        // 验证段落文本
        assertTrue(content.contains("This is an intro paragraph for ST-Cute."));
        // 验证表格内容
        assertTrue(content.contains("Task"));
        assertTrue(content.contains("Status"));
        assertTrue(content.contains("L3 Test Suite"));
        assertTrue(content.contains("In Progress"));
    }
}
