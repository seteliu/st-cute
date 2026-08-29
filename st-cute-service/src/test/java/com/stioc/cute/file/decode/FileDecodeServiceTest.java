package com.stioc.cute.file.decode;

import com.stioc.cute.file.decode.impl.ExcelFileDecoder;
import com.stioc.cute.file.decode.impl.PdfFileDecoder;
import com.stioc.cute.file.decode.impl.PlainTextFileDecoder;
import com.stioc.cute.file.decode.impl.PptFileDecoder;
import com.stioc.cute.file.decode.impl.WordFileDecoder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件解码模块综合单元测试
 */
class FileDecodeServiceTest {

    private FileDecodeService decodeService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        List<FileDecoder> decoders = List.of(
                new PlainTextFileDecoder(),
                new PdfFileDecoder(),
                new WordFileDecoder(),
                new ExcelFileDecoder(),
                new PptFileDecoder()
        );
        decodeService = new FileDecodeService(decoders);
    }

    @Test
    void testPlainTextDecode() throws Exception {
        Path txtPath = tempDir.resolve("test.txt");
        Files.writeString(txtPath, "你好，这是一个文本附件测试！\nLine 2.", StandardCharsets.UTF_8);

        String result = decodeService.decode(txtPath.toFile());
        assertNotNull(result);
        assertTrue(result.contains("你好，这是一个文本附件测试！"));
        assertTrue(result.contains("Line 2."));
    }

    @Test
    void testGbkPlainTextDecode() throws Exception {
        Path gbkPath = tempDir.resolve("test_gbk.txt");
        byte[] gbkBytes = "这是 GBK 中文编码的文件内容测试".getBytes(Charset.forName("GBK"));
        Files.write(gbkPath, gbkBytes);

        String result = decodeService.decode(gbkPath.toFile());
        assertNotNull(result);
        assertTrue(result.contains("这是 GBK 中文编码的文件内容测试"));
    }

    @Test
    void testPdfDecode() throws Exception {
        Path pdfPath = tempDir.resolve("test.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("Hello Cute Agent PDF Test");
                stream.endText();
            }
            doc.save(pdfPath.toFile());
        }

        String result = decodeService.decode(pdfPath.toFile());
        assertNotNull(result);
        assertTrue(result.contains("Hello Cute Agent PDF Test"));
        assertTrue(result.contains("第 1 页"));
    }

    @Test
    void testDocxDecodeWithTable() throws Exception {
        Path docxPath = tempDir.resolve("test.docx");
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream fos = new FileOutputStream(docxPath.toFile())) {

            XWPFParagraph p = doc.createParagraph();
            XWPFRun r = p.createRun();
            r.setText("这是 Word 正文段落");

            XWPFTable table = doc.createTable(2, 2);
            XWPFTableRow r0 = table.getRow(0);
            r0.getCell(0).setText("表头1");
            r0.getCell(1).setText("表头2");

            XWPFTableRow r1 = table.getRow(1);
            r1.getCell(0).setText("数据A");
            r1.getCell(1).setText("数据B");

            doc.write(fos);
        }

        String result = decodeService.decode(docxPath.toFile());
        assertNotNull(result);
        assertTrue(result.contains("这是 Word 正文段落"));
        assertTrue(result.contains("表头1"));
        assertTrue(result.contains("表头2"));
        assertTrue(result.contains("数据A"));
        assertTrue(result.contains("---|"));
    }

    @Test
    void testExcelDecodeToMarkdown() throws Exception {
        Path xlsxPath = tempDir.resolve("test.xlsx");
        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(xlsxPath.toFile())) {

            Sheet sheet = wb.createSheet("报表");
            Row r0 = sheet.createRow(0);
            r0.createCell(0).setCellValue("姓名");
            r0.createCell(1).setCellValue("成绩");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("张三");
            r1.createCell(1).setCellValue(98.5);

            wb.write(fos);
        }

        String result = decodeService.decode(xlsxPath.toFile());
        assertNotNull(result);
        assertTrue(result.contains("### 工作表: 报表"));
        assertTrue(result.contains("姓名"));
        assertTrue(result.contains("成绩"));
        assertTrue(result.contains("张三"));
        assertTrue(result.contains("98.5"));
    }

    @Test
    void testPptxDecode() throws Exception {
        Path pptxPath = tempDir.resolve("test.pptx");
        try (XMLSlideShow ppt = new XMLSlideShow();
             FileOutputStream fos = new FileOutputStream(pptxPath.toFile())) {

            XSLFSlide slide = ppt.createSlide();
            XSLFTextBox textBox = slide.createTextBox();
            textBox.setText("PPT 幻灯片标题内容");

            ppt.write(fos);
        }

        String result = decodeService.decode(pptxPath.toFile());
        assertNotNull(result);
        assertTrue(result.contains("PPT 幻灯片标题内容"));
        assertTrue(result.contains("Slide 1"));
    }

    @Test
    void testTruncate() {
        String longText = "a".repeat(100);
        String truncated = decodeService.truncateIfNecessary(longText, 50);
        assertTrue(truncated.length() > 50);
        assertTrue(truncated.contains("已截断显示前 50 字符"));
        assertTrue(truncated.startsWith("a".repeat(50)));
    }
}
