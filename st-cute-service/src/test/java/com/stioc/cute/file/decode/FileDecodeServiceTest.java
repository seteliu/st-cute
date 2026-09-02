package com.stioc.cute.file.decode;

import com.stioc.cute.file.FileDecodeServiceImpl;
import com.stioc.cute.file.access.DecodeParam;
import com.stioc.cute.file.access.FileDecodeService;
import com.stioc.cute.file.decode.impl.ExcelFileDecoder;
import com.stioc.cute.file.decode.impl.ImageFileDecoder;
import com.stioc.cute.file.decode.impl.PdfFileDecoder;
import com.stioc.cute.file.decode.impl.PlainTextFileDecoder;
import com.stioc.cute.file.decode.impl.PptFileDecoder;
import com.stioc.cute.file.decode.impl.WordFileDecoder;
import com.stioc.cute.llm.CuteAttachment;
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

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                new ImageFileDecoder(),
                new PdfFileDecoder(),
                new WordFileDecoder(),
                new ExcelFileDecoder(),
                new PptFileDecoder()
        );
        decodeService = new FileDecodeServiceImpl(decoders);
    }

    /**
     * 解码并提取唯一文本附件的内容（文本类格式的通用断言辅助）
     */
    private String decodeText(File file, String ext, String mimeType) {
        List<CuteAttachment> attachments = decodeService.decodeToAttachments(
                file, ext, mimeType,
                DecodeParam.builder().allowImage(false).sourceName(file.getName()).build());
        assertEquals(1, attachments.size());
        assertFalse(attachments.get(0).isImage());
        return attachments.get(0).getTextContent();
    }

    @Test
    void testPlainTextDecode() throws Exception {
        Path txtPath = tempDir.resolve("test.txt");
        Files.writeString(txtPath, "你好，这是一个文本附件测试！\nLine 2.", StandardCharsets.UTF_8);

        String result = decodeText(txtPath.toFile(), "txt", "text/plain");
        assertNotNull(result);
        assertTrue(result.contains("你好，这是一个文本附件测试！"));
        assertTrue(result.contains("Line 2."));
    }

    @Test
    void testGbkPlainTextDecode() throws Exception {
        Path gbkPath = tempDir.resolve("test_gbk.txt");
        byte[] gbkBytes = "这是 GBK 中文编码的文件内容测试".getBytes(Charset.forName("GBK"));
        Files.write(gbkPath, gbkBytes);

        String result = decodeText(gbkPath.toFile(), "txt", "text/plain");
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

        String result = decodeText(pdfPath.toFile(), "pdf", "application/pdf");
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

        String result = decodeText(docxPath.toFile(), "docx", null);
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

        String result = decodeText(xlsxPath.toFile(), "xlsx", null);
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

        String result = decodeText(pptxPath.toFile(), "pptx", null);
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

    // ==================== 多附件模式（decodeToAttachments）测试 ====================

    /**
     * 生成测试用 PNG 图片字节
     */
    private byte[] createTestPngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setColor(Color.BLUE);
        g2d.fillRect(0, 0, width, height);
        g2d.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    @Test
    void testImageDecodeToAttachmentsMultimodal() throws Exception {
        Path imgPath = tempDir.resolve("photo.png");
        Files.write(imgPath, createTestPngBytes(300, 200));

        List<CuteAttachment> attachments = decodeService.decodeToAttachments(
                imgPath.toFile(), "png", "image/png",
                DecodeParam.builder().allowImage(true).sourceName("photo.png").build());

        assertEquals(1, attachments.size());
        assertTrue(attachments.get(0).isImage());
        assertNotNull(attachments.get(0).getBase64Data());
    }

    @Test
    void testImageDecodeToAttachmentsTextOnly() throws Exception {
        Path imgPath = tempDir.resolve("photo2.png");
        Files.write(imgPath, createTestPngBytes(100, 100));

        // 非多模态模型：返回占位文本附件，无图片产出
        List<CuteAttachment> attachments = decodeService.decodeToAttachments(
                imgPath.toFile(), "png", "image/png",
                DecodeParam.builder().allowImage(false).sourceName("photo2.png").build());

        assertEquals(1, attachments.size());
        assertFalse(attachments.get(0).isImage());
        assertNotNull(attachments.get(0).getTextContent());
        assertTrue(attachments.get(0).getTextContent().contains("不支持视觉"));
    }

    @Test
    void testPdfDecodeToAttachmentsTextPage() throws Exception {
        Path pdfPath = tempDir.resolve("text.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("Multi attachment PDF Test");
                stream.endText();
            }
            doc.save(pdfPath.toFile());
        }

        List<CuteAttachment> attachments = decodeService.decodeToAttachments(
                pdfPath.toFile(), "pdf", "application/pdf",
                DecodeParam.builder().allowImage(true).sourceName("text.pdf").build());

        // 纯文本页：单文本附件，无图片衍生
        assertEquals(1, attachments.size());
        assertFalse(attachments.get(0).isImage());
        assertTrue(attachments.get(0).getTextContent().contains("Multi attachment PDF Test"));
        assertTrue(attachments.get(0).getTextContent().contains("第 1 页"));
    }

    @Test
    void testPdfDecodeToAttachmentsScanPage() throws Exception {
        // 构造无文本层的空白页（模拟扫描页），多模态开启时应整页渲染为图片附件
        Path pdfPath = tempDir.resolve("scan.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pdfPath.toFile());
        }

        List<CuteAttachment> attachments = decodeService.decodeToAttachments(
                pdfPath.toFile(), "pdf", "application/pdf",
                DecodeParam.builder().allowImage(true).sourceName("scan.pdf").build());

        assertEquals(2, attachments.size());
        assertFalse(attachments.get(0).isImage());
        assertTrue(attachments.get(0).getTextContent().contains("扫描图像页"));
        assertTrue(attachments.get(1).isImage());
        assertNotNull(attachments.get(1).getBase64Data());
        assertTrue(attachments.get(1).getName().contains("_p1_scan"));
    }

    @Test
    void testPdfDecodeToAttachmentsScanPageTextOnly() throws Exception {
        // 扫描页 + 非多模态：只有文本占位说明，无图片衍生
        Path pdfPath = tempDir.resolve("scan2.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pdfPath.toFile());
        }

        List<CuteAttachment> attachments = decodeService.decodeToAttachments(
                pdfPath.toFile(), "pdf", "application/pdf",
                DecodeParam.builder().allowImage(false).sourceName("scan2.pdf").build());

        assertEquals(1, attachments.size());
        assertFalse(attachments.get(0).isImage());
        assertTrue(attachments.get(0).getTextContent().contains("无文本层"));
    }

    @Test
    void testDocxDecodeToAttachmentsWithPicture() throws Exception {
        // 构造含内嵌图片的 docx：段落文本 + 图片 run + 后续文本
        Path docxPath = tempDir.resolve("pic.docx");
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream fos = new FileOutputStream(docxPath.toFile())) {

            XWPFParagraph p1 = doc.createParagraph();
            p1.createRun().setText("图片前文本");

            XWPFParagraph p2 = doc.createParagraph();
            XWPFRun imgRun = p2.createRun();
            try (ByteArrayInputStream bais = new ByteArrayInputStream(createTestPngBytes(80, 60))) {
                imgRun.addPicture(bais, XWPFDocument.PICTURE_TYPE_PNG, "test.png",
                        org.apache.poi.util.Units.toEMU(80), org.apache.poi.util.Units.toEMU(60));
            }

            XWPFParagraph p3 = doc.createParagraph();
            p3.createRun().setText("图片后文本");

            doc.write(fos);
        }

        List<CuteAttachment> attachments = decodeService.decodeToAttachments(
                docxPath.toFile(), "docx", null,
                DecodeParam.builder().allowImage(true).sourceName("pic.docx").build());

        // 1 个文本附件 + 1 个图片附件
        assertEquals(2, attachments.size());
        assertFalse(attachments.get(0).isImage());
        String text = attachments.get(0).getTextContent();
        assertTrue(text.contains("图片前文本"));
        assertTrue(text.contains("图片后文本"));
        assertTrue(text.contains("[图片 1"));

        assertTrue(attachments.get(1).isImage());
        assertNotNull(attachments.get(1).getBase64Data());
        assertTrue(attachments.get(1).getName().contains("img1"));
    }

    @Test
    void testDocxDecodeToAttachmentsTextOnlyMode() throws Exception {
        // 非多模态模式：图片仅占位不提取，单文本附件
        Path docxPath = tempDir.resolve("pic2.docx");
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream fos = new FileOutputStream(docxPath.toFile())) {

            XWPFParagraph p = doc.createParagraph();
            XWPFRun imgRun = p.createRun();
            try (ByteArrayInputStream bais = new ByteArrayInputStream(createTestPngBytes(50, 50))) {
                imgRun.addPicture(bais, XWPFDocument.PICTURE_TYPE_PNG, "t.png",
                        org.apache.poi.util.Units.toEMU(50), org.apache.poi.util.Units.toEMU(50));
            }
            doc.write(fos);
        }

        List<CuteAttachment> attachments = decodeService.decodeToAttachments(
                docxPath.toFile(), "docx", null,
                DecodeParam.builder().allowImage(false).sourceName("pic2.docx").build());

        assertEquals(1, attachments.size());
        assertFalse(attachments.get(0).isImage());
        assertTrue(attachments.get(0).getTextContent().contains("未提取"));
    }

    @Test
    void testPlainTextDecodeToAttachments() throws Exception {
        Path txtPath = tempDir.resolve("multi.txt");
        Files.writeString(txtPath, "多附件模式纯文本测试", StandardCharsets.UTF_8);

        List<CuteAttachment> attachments = decodeService.decodeToAttachments(
                txtPath.toFile(), "txt", "text/plain",
                DecodeParam.builder().allowImage(true).sourceName("multi.txt").build());

        // 纯文本实现：单文本附件
        assertEquals(1, attachments.size());
        assertFalse(attachments.get(0).isImage());
        assertTrue(attachments.get(0).getTextContent().contains("多附件模式纯文本测试"));
    }

    @Test
    void testUnsupportedFormatDecodeToAttachments() throws Exception {
        Path zipPath = tempDir.resolve("data.zip");
        Files.write(zipPath, new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00});

        List<CuteAttachment> attachments = decodeService.decodeToAttachments(
                zipPath.toFile(), "zip", "application/zip",
                DecodeParam.builder().allowImage(true).sourceName("data.zip").build());

        // 无匹配解码器：返回格式说明附件而非空列表
        assertEquals(1, attachments.size());
        assertFalse(attachments.get(0).isImage());
        assertTrue(attachments.get(0).getTextContent().contains("暂不支持内容直接解析"));
    }
}
