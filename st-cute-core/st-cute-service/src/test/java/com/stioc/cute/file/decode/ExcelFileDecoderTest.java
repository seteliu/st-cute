package com.stioc.cute.file.decode;

import com.stioc.cute.engine.llm.types.CuteAttachment;
import com.stioc.cute.file.types.DecodeParam;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
 * Excel 表格解码器单元测试。
 * 覆盖 xlsx/xls 格式支持判定、动态生成微型表格、Sheet 分块与标准 Markdown 表格转换。
 */
class ExcelFileDecoderTest {

    private final ExcelFileDecoder decoder = new ExcelFileDecoder();

    @Test
    @DisplayName("格式支持与 MIME 判定")
    void supportsExcel() {
        assertTrue(decoder.supports("xlsx", null));
        assertTrue(decoder.supports("xls", null));
        assertTrue(decoder.supports("XLSX", null));
        assertTrue(decoder.supports("unknown", "application/vnd.ms-excel"));
        assertTrue(decoder.supports("unknown", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        assertFalse(decoder.supports("txt", "text/plain"));
    }

    @Test
    @DisplayName("动态生成微型 Excel 表格并解码为 Markdown 格式")
    void decodeWorkbookToMarkdown(@TempDir Path tempDir) throws Exception {
        Path excelPath = tempDir.resolve("users.xlsx");

        // 动态生成包含表头与数据的测试工作簿
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("UserSheet");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("ID");
            header.createCell(1).setCellValue("Name");
            header.createCell(2).setCellValue("Role");

            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("101");
            row1.createCell(1).setCellValue("Alice");
            row1.createCell(2).setCellValue("Developer");

            try (FileOutputStream fos = new FileOutputStream(excelPath.toFile())) {
                workbook.write(fos);
            }
        }

        DecodeParam param = DecodeParam.textOnly("users.xlsx");
        List<CuteAttachment> attachments = decoder.decodeToAttachments(excelPath.toFile(), param);

        assertNotNull(attachments);
        assertFalse(attachments.isEmpty());

        CuteAttachment att = attachments.get(0);
        assertFalse(att.isImage());
        String content = att.getTextContent();
        assertNotNull(content);

        // 验证 Sheet 标题标识
        assertTrue(content.contains("UserSheet"));
        // 验证 Markdown 表格结构与单元格数据
        assertTrue(content.contains("ID"));
        assertTrue(content.contains("Name"));
        assertTrue(content.contains("Role"));
        assertTrue(content.contains("Alice"));
        assertTrue(content.contains("Developer"));
        assertTrue(content.contains("|"));
    }
}
