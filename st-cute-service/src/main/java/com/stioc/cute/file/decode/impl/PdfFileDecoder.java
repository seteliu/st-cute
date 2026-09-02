package com.stioc.cute.file.decode.impl;

import com.stioc.cute.file.ImageProcessUtils;
import com.stioc.cute.file.access.DecodeParam;
import com.stioc.cute.file.decode.FileDecoder;
import com.stioc.cute.llm.CuteAttachment;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * PDF 文档解码器
 * 基于 Apache PDFBox 解析并提取文本内容，保留分页标识便于模型理解与定位。
 * 多附件模式（decodeToAttachments）下：
 * 1. 文本页：逐页提取文本并聚合为单文本附件（保留分页标识）
 * 2. 含图页：提取页面资源字典中的内嵌图片为独立图片附件，文本中以页粒度占位符关联
 * 3. 扫描页（无文本层）：整页渲染为图片附件兜底
 */
@Slf4j
@Component
public class PdfFileDecoder implements FileDecoder {

    /**
     * 扫描页整页渲染 DPI
     */
    private static final float RENDER_DPI = 120f;

    /**
     * 单文档图片附件上限（含内嵌图与整页渲染），超出仅占位不提取
     */
    private static final int MAX_IMAGE_ATTACHMENTS = 20;

    /**
     * 单文档最大解析页数限制，超出截断并追加提示，防止超大文档解析耗时与内存失控
     */
    private static final int MAX_PAGES = 100;

    @Override
    public boolean supports(String extension, String mimeType) {
        return "pdf".equalsIgnoreCase(extension) || "application/pdf".equalsIgnoreCase(mimeType);
    }

    @Override
    public List<CuteAttachment> decodeToAttachments(File file, DecodeParam ctx) throws Exception {
        if (file == null || !file.exists()) {
            return List.of();
        }

        boolean allowImage = ctx != null && ctx.isAllowImage();
        String sourceName = ctx != null && ctx.getSourceName() != null ? ctx.getSourceName() : file.getName();

        try (PDDocument document = Loader.loadPDF(file)) {
            if (document.isEncrypted()) {
                return List.of(CuteAttachment.builder()
                        .name(sourceName)
                        .path(file.getAbsolutePath())
                        .isImage(false)
                        .textContent("[PDF 文档受密码保护，无法直接读取内容]")
                        .build());
            }

            int numberOfPages = document.getNumberOfPages();
            if (numberOfPages == 0) {
                return List.of(CuteAttachment.builder()
                        .name(sourceName)
                        .path(file.getAbsolutePath())
                        .isImage(false)
                        .textContent(String.format("[附件文件: %s 内容为空]", sourceName))
                        .build());
            }

            StringBuilder sb = new StringBuilder();
            List<CuteAttachment> imageAttachments = new ArrayList<>();
            int imageSeq = 0;
            int skipped = 0;

            sb.append(String.format("【PDF 文档: %s，共 %d 页】\n", sourceName, numberOfPages));

            // 页数超出上限时截断，仅解析前 MAX_PAGES 页
            int pageLimit = Math.min(numberOfPages, MAX_PAGES);
            for (int page = 1; page <= pageLimit; page++) {
                String pageText = extractPageText(document, page);
                boolean hasText = StringUtils.hasText(pageText);

                // 扫描页（无文本层）：整页渲染为图片兜底；不允许图片时仅文本说明
                if (!hasText) {
                    if (allowImage && imageSeq < MAX_IMAGE_ATTACHMENTS) {
                        byte[] rendered = renderPageImage(document, page);
                        if (rendered != null) {
                            imageSeq++;
                            String attachName = String.format("%s_p%d_scan.png", stripExt(sourceName), page);
                            imageAttachments.add(buildImageAttachment(rendered, attachName));
                            sb.append(String.format("\n--- [第 %d 页为扫描图像页，无文本层，已整页渲染为图片附件: %s] ---\n", page, attachName));
                            continue;
                        }
                    }
                    sb.append(String.format("\n--- [第 %d 页无文本层（可能为扫描图像页），未提取内容] ---\n", page));
                    continue;
                }

                // 文本页：输出文本；页内有内嵌图片时提取并追加页粒度占位符
                sb.append(String.format("\n--- [第 %d 页] ---\n", page));
                sb.append(pageText.trim()).append("\n");

                if (allowImage) {
                    List<byte[]> pageImages = extractPageImages(document, page);
                    if (!pageImages.isEmpty()) {
                        List<String> names = new ArrayList<>();
                        for (byte[] img : pageImages) {
                            if (imageSeq >= MAX_IMAGE_ATTACHMENTS) {
                                skipped++;
                                continue;
                            }
                            imageSeq++;
                            String attachName = String.format("%s_p%d_img%d.png", stripExt(sourceName), page, names.size() + 1);
                            names.add(attachName);
                            imageAttachments.add(buildImageAttachment(img, attachName));
                        }
                        if (!names.isEmpty()) {
                            sb.append(String.format("[第 %d 页含图片 %d 张，已提取为图片附件: %s]\n",
                                    page, names.size(), String.join(", ", names)));
                        }
                    }
                }
            }

            // 超出上限的图片统一在尾部汇总说明
            if (skipped > 0) {
                sb.append(String.format("\n[说明: 本文档图片数量超过单文档提取上限 %d 张，有 %d 张未提取，仅保留文本]\n",
                        MAX_IMAGE_ATTACHMENTS, skipped));
            }

            // 超出页数上限时统一在尾部汇总说明
            if (numberOfPages > MAX_PAGES) {
                sb.append(String.format("\n[说明: 本文档共 %d 页，超过单文档解析上限 %d 页，仅解析前 %d 页]\n",
                        numberOfPages, MAX_PAGES, MAX_PAGES));
            }

            CuteAttachment textAttachment = CuteAttachment.builder()
                    .name(sourceName)
                    .path(file.getAbsolutePath())
                    .isImage(false)
                    .textContent(sb.toString().trim())
                    .build();

            List<CuteAttachment> result = new ArrayList<>();
            result.add(textAttachment);
            result.addAll(imageAttachments);
            return result;
        }
    }

    /**
     * 提取指定页文本
     */
    private String extractPageText(PDDocument document, int page) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(document);
    }

    /**
     * 提取指定页资源字典中的内嵌图片字节（PNG 编码）。
     * 内联图片（BI/EI 操作符）与不支持的编码（JBIG2/CCITT 等）将被跳过，不影响整体解析
     */
    private List<byte[]> extractPageImages(PDDocument document, int page) {
        List<byte[]> images = new ArrayList<>();
        try {
            PDPage pdPage = document.getPage(page - 1);
            PdfImageExtractor extractor = new PdfImageExtractor();
            extractor.processPage(pdPage);
            images.addAll(extractor.getImages());
        } catch (Exception e) {
            log.warn("提取 PDF 第 {} 页内嵌图片失败，跳过: {}", page, e.getMessage());
        }
        return images;
    }

    /**
     * 扫描页整页渲染为 PNG 字节
     */
    private byte[] renderPageImage(PDDocument document, int page) {
        try {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(page - 1, RENDER_DPI);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("渲染 PDF 第 {} 页失败，跳过: {}", page, e.getMessage());
            return null;
        }
    }

    /**
     * 图片字节统一压缩后构建图片附件（压缩规格与跳过策略由 ImageProcessUtils.compressIfNeeded 统一管理）。
     * 压缩可能将无透明 PNG 转码为 JPEG，附件名与 MIME 以真实字节格式为准
     */
    private CuteAttachment buildImageAttachment(byte[] raw, String name) {
        byte[] data = ImageProcessUtils.compressIfNeeded(raw, "png");
        if (data == null || data.length == 0) {
            data = raw;
        }
        String realFormat = ImageProcessUtils.detectImageFormat(data);
        String effectiveExt = StringUtils.hasText(realFormat) ? realFormat : "png";
        String effectiveName = stripExt(name) + "." + effectiveExt;
        return CuteAttachment.builder()
                .name(effectiveName)
                .path(effectiveName)
                .size((long) data.length)
                .mimeType("image/" + ("jpg".equals(effectiveExt) ? "jpeg" : effectiveExt))
                .isImage(true)
                .base64Data(Base64.getEncoder().encodeToString(data))
                .build();
    }

    /**
     * 去除文件扩展名
     */
    private String stripExt(String name) {
        if (name == null || name.isEmpty()) {
            return "file";
        }
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    /**
     * 基于 PDFStreamEngine 的页面内嵌图片收集器。
     * 遍历页面内容流中的 Do 操作符，收集图片 XObject 的图像数据；
     * 无法解码的图（JBIG2/CCITT 等特殊编码）单独跳过，不影响其他图片与整体解析
     */
    private static class PdfImageExtractor extends PDFStreamEngine {

        private final List<byte[]> images = new ArrayList<>();

        PdfImageExtractor() {
            // 注册图形状态相关操作符，使内容流遍历可正确维护变换矩阵
            addOperator(new Concatenate(this));
            addOperator(new DrawObject(this));
            addOperator(new SetGraphicsStateParameters(this));
            addOperator(new Save(this));
            addOperator(new Restore(this));
            addOperator(new SetMatrix(this));
        }

        List<byte[]> getImages() {
            return images;
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if ("Do".equals(operator.getName()) && !operands.isEmpty() && operands.get(0) instanceof COSName objectName) {
                try {
                    PDXObject xobject = getResources().getXObject(objectName);
                    if (xobject instanceof PDImageXObject image) {
                        BufferedImage bufferedImage = image.getImage();
                        if (bufferedImage != null) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(bufferedImage, "png", baos);
                            images.add(baos.toByteArray());
                        }
                    }
                } catch (Exception e) {
                    // 单张图片解码失败（如 JBIG2/CCITT 等不支持的编码）跳过，不影响整体
                    log.debug("跳过无法解码的 PDF 内嵌图片: {}", e.getMessage());
                }
            } else {
                super.processOperator(operator, operands);
            }
        }
    }
}
