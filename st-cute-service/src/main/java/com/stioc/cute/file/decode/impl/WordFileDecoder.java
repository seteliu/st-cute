package com.stioc.cute.file.decode.impl;

import com.stioc.cute.file.access.FileStorageService;
import com.stioc.cute.file.ImageProcessUtils;
import com.stioc.cute.file.access.DecodeParam;
import com.stioc.cute.file.decode.FileDecoder;
import com.stioc.cute.llm.CuteAttachment;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hwpf.model.PicturesTable;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Word 文档解码器
 * 支持 .docx (OOXML) 和 .doc (Word 97-2003) 格式
 * 提取段落文本并将表格转换为标准 Markdown 表格。
 * 多附件模式（decodeToAttachments）下：文本全量提取，内嵌图片在原位置插入占位符，
 * 图片字节独立导出为压缩后的图片附件，通过占位符编号与文本位置关联
 */
@Slf4j
@Component
public class WordFileDecoder implements FileDecoder {

    /**
     * 单文档图片附件上限，超出仅占位不提取
     */
    private static final int MAX_IMAGE_ATTACHMENTS = 20;

    /**
     * 内嵌表格最大行数限制，超出截断并追加提示
     */
    private static final int MAX_TABLE_ROWS = 10000;

    /**
     * 内嵌表格单行最大列数限制，超出截断并追加提示
     */
    private static final int MAX_TABLE_COLS = 100;

    /**
     * 表格单元格最大字符数限制，超出截断
     */
    private static final int MAX_CHARS_PER_CELL = 10000;

    @Override
    public boolean supports(String extension, String mimeType) {
        if ("docx".equalsIgnoreCase(extension) || "doc".equalsIgnoreCase(extension)) {
            return true;
        }
        if (StringUtils.hasText(mimeType)) {
            return mimeType.contains("wordprocessingml") || "application/msword".equalsIgnoreCase(mimeType);
        }
        return false;
    }

    @Override
    public List<CuteAttachment> decodeToAttachments(File file, DecodeParam ctx) throws Exception {
        if (file == null || !file.exists()) {
            return List.of();
        }

        boolean allowImage = ctx != null && ctx.isAllowImage();
        String sourceName = ctx != null && ctx.getSourceName() != null ? ctx.getSourceName() : file.getName();

        String name = file.getName().toLowerCase();
        StringBuilder sb = new StringBuilder();
        List<CuteAttachment> imageAttachments = new ArrayList<>();

        if (name.endsWith(".doc")) {
            decodeDocToAttachments(file, sourceName, allowImage, sb, imageAttachments);
        } else {
            decodeDocxToAttachments(file, sourceName, allowImage, sb, imageAttachments);
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

    /**
     * 多附件模式解析 .docx：按 run 顺序遍历正文，图片在原文本位置插入占位符并独立导出
     */
    private void decodeDocxToAttachments(File file, String sourceName, boolean allowImage,
                                         StringBuilder sb, List<CuteAttachment> imageAttachments) throws Exception {
        try (InputStream is = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(is)) {

            sb.append(String.format("【Word 文档: %s】\n", sourceName));

            int imageSeq = 0;
            List<IBodyElement> bodyElements = doc.getBodyElements();

            for (IBodyElement element : bodyElements) {
                if (element instanceof XWPFParagraph paragraph) {
                    // 按 run 顺序输出，保证图片占位符落在原文精确位置
                    List<XWPFRun> runs = paragraph.getRuns();
                    if (runs == null || runs.isEmpty()) {
                        String text = paragraph.getText();
                        if (StringUtils.hasText(text)) {
                            sb.append(text).append("\n");
                        }
                        continue;
                    }
                    StringBuilder lineSb = new StringBuilder();
                    boolean hasContent = false;
                    for (XWPFRun run : runs) {
                        if (run == null) {
                            continue;
                        }
                        String runText = run.text();
                        if (StringUtils.hasText(runText)) {
                            lineSb.append(runText);
                            hasContent = true;
                        }
                        List<XWPFPicture> pictures = run.getEmbeddedPictures();
                        if (pictures != null && !pictures.isEmpty()) {
                            for (XWPFPicture picture : pictures) {
                                hasContent = true;
                                if (!allowImage || imageSeq >= MAX_IMAGE_ATTACHMENTS) {
                                    lineSb.append(String.format("[图片 %d 未提取]", imageSeq + 1));
                                    continue;
                                }
                                imageSeq++;
                                String attachName = String.format("%s_img%d", stripExt(sourceName), imageSeq);
                                String ext = exportPicture(picture.getPictureData(), attachName, imageAttachments);
                                if (ext != null) {
                                    lineSb.append(String.format("[图片 %d: %s.%s]", imageSeq, attachName, ext));
                                } else {
                                    lineSb.append("[图片无法解码，未提取]");
                                }
                            }
                        }
                    }
                    if (hasContent) {
                        sb.append(lineSb).append("\n");
                    }
                } else if (element instanceof XWPFTable table) {
                    String tableMarkdown = renderTableAsMarkdown(table);
                    if (StringUtils.hasText(tableMarkdown)) {
                        sb.append("\n").append(tableMarkdown).append("\n");
                    }
                }
            }

            if (imageSeq >= MAX_IMAGE_ATTACHMENTS) {
                sb.append(String.format("\n[说明: 本文档图片数量超过单文档提取上限 %d 张，后续图片仅占位未提取]\n", MAX_IMAGE_ATTACHMENTS));
            }
        }
    }

    /**
     * 多附件模式解析 .doc：老格式图片位置难以精确定位，文本全量提取，图片统一在文末汇总导出
     */
    private void decodeDocToAttachments(File file, String sourceName, boolean allowImage,
                                        StringBuilder sb, List<CuteAttachment> imageAttachments) throws Exception {
        try (InputStream is = new FileInputStream(file);
             HWPFDocument doc = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(doc)) {

            sb.append(String.format("【Word 文档: %s】\n", sourceName));
            String text = extractor.getText();
            if (StringUtils.hasText(text)) {
                sb.append(text.trim()).append("\n");
            }

            if (allowImage) {
                PicturesTable picturesTable = doc.getPicturesTable();
                List<Picture> pictures = picturesTable.getAllPictures();
                if (pictures != null && !pictures.isEmpty()) {
                    int limit = Math.min(pictures.size(), MAX_IMAGE_ATTACHMENTS);
                    List<String> names = new ArrayList<>();
                    for (int i = 0; i < limit; i++) {
                        Picture picture = pictures.get(i);
                        String attachName = String.format("%s_img%d", stripExt(sourceName), i + 1);
                        String ext = exportDocPicture(picture, attachName, imageAttachments);
                        if (ext != null) {
                            names.add(attachName + "." + ext);
                        }
                    }
                    if (!names.isEmpty()) {
                        sb.append(String.format("\n[文档内嵌图片 %d 张，已提取为图片附件: %s", names.size(), String.join(", ", names)));
                        if (pictures.size() > limit) {
                            sb.append(String.format("（另有 %d 张超过上限未提取）", pictures.size() - limit));
                        }
                        sb.append("]\n");
                    }
                }
            }
        }
    }

    /**
     * 导出 docx 内嵌图片为压缩图片附件
     *
     * @return 图片扩展名；解码失败返回 null
     */
    private String exportPicture(XWPFPictureData pictureData, String attachName, List<CuteAttachment> imageAttachments) {
        try {
            if (pictureData == null) {
                return null;
            }
            byte[] raw = pictureData.getData();
            if (raw == null || raw.length == 0) {
                return null;
            }
            String ext = pictureData.suggestFileExtension();
            if (!StringUtils.hasText(ext)) {
                ext = "png";
            }
            ext = ext.toLowerCase();
            if (!ImageProcessUtils.isImage(ext, null)) {
                return null;
            }
            buildImageAttachment(raw, ext, attachName, imageAttachments);
            return ext;
        } catch (Exception e) {
            log.warn("导出 docx 内嵌图片失败，跳过: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 导出 doc 老格式内嵌图片为压缩图片附件
     *
     * @return 图片扩展名；解码失败返回 null
     */
    private String exportDocPicture(Picture picture, String attachName, List<CuteAttachment> imageAttachments) {
        try {
            if (picture == null) {
                return null;
            }
            byte[] raw = picture.getContent();
            if (raw == null || raw.length == 0) {
                return null;
            }
            String ext = picture.suggestFileExtension();
            if (!StringUtils.hasText(ext)) {
                ext = "png";
            }
            ext = ext.toLowerCase();
            if (!ImageProcessUtils.isImage(ext, null)) {
                return null;
            }
            buildImageAttachment(raw, ext, attachName, imageAttachments);
            return ext;
        } catch (Exception e) {
            log.warn("导出 doc 内嵌图片失败，跳过: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 图片字节统一压缩后构建图片附件并加入列表（压缩规格与跳过策略由 ImageProcessUtils.compressIfNeeded 统一管理）。
     * 压缩可能将无透明 png/bmp 转码为 JPEG，附件名与 MIME 以真实字节格式为准
     */
    private void buildImageAttachment(byte[] raw, String ext, String attachName, List<CuteAttachment> imageAttachments) {
        byte[] data = ImageProcessUtils.compressIfNeeded(raw, ext);
        if (data == null || data.length == 0) {
            data = raw;
        }
        String realFormat = ImageProcessUtils.detectImageFormat(data);
        String effectiveExt = StringUtils.hasText(realFormat) ? realFormat : ext;
        imageAttachments.add(CuteAttachment.builder()
                .name(attachName + "." + effectiveExt)
                .path(attachName + "." + effectiveExt)
                .size((long) data.length)
                .mimeType(FileStorageService.detectMimeType(effectiveExt))
                .isImage(true)
                .base64Data(Base64.getEncoder().encodeToString(data))
                .build());
    }

    /**
     * 将 Word 表格转换为 Markdown 表格格式
     */
    private String renderTableAsMarkdown(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows == null || rows.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int maxCols = 0;
        List<List<String>> matrix = new ArrayList<>();
        // 记录全表最大列数，用于判断是否发生列截断
        int totalMaxCols = 0;

        // 行数超出上限时截断，仅处理前 MAX_TABLE_ROWS 行
        int rowLimit = Math.min(rows.size(), MAX_TABLE_ROWS);
        for (int rowIdx = 0; rowIdx < rowLimit; rowIdx++) {
            XWPFTableRow row = rows.get(rowIdx);
            List<String> rowData = new ArrayList<>();
            List<XWPFTableCell> cells = row.getTableCells();
            if (cells.size() > totalMaxCols) {
                totalMaxCols = cells.size();
            }
            // 列数超出上限时截断，仅处理前 MAX_TABLE_COLS 列
            int colLimit = Math.min(cells.size(), MAX_TABLE_COLS);
            for (int colIdx = 0; colIdx < colLimit; colIdx++) {
                XWPFTableCell cell = cells.get(colIdx);
                String text = cell.getText();
                text = text != null ? text.trim().replace("\n", " ").replace("|", "\\|") : "";
                // 单格超长截断，防止极端大单元格撑爆内存与上下文
                if (text.length() > MAX_CHARS_PER_CELL) {
                    text = text.substring(0, MAX_CHARS_PER_CELL) + "…(单元格超长已截断)";
                }
                rowData.add(text);
            }
            if (rowData.size() > maxCols) {
                maxCols = rowData.size();
            }
            matrix.add(rowData);
        }

        if (maxCols == 0 || matrix.isEmpty()) {
            return "";
        }

        // 输出表头（第一行）
        List<String> header = matrix.get(0);
        sb.append("|");
        for (int c = 0; c < maxCols; c++) {
            String val = c < header.size() ? header.get(c) : "";
            sb.append(" ").append(val).append(" |");
        }
        sb.append("\n|");
        for (int c = 0; c < maxCols; c++) {
            sb.append("---|");
        }
        sb.append("\n");

        // 输出后续行
        for (int r = 1; r < matrix.size(); r++) {
            List<String> row = matrix.get(r);
            sb.append("|");
            for (int c = 0; c < maxCols; c++) {
                String val = c < row.size() ? row.get(c) : "";
                sb.append(" ").append(val).append(" |");
            }
            sb.append("\n");
        }

        if (rows.size() > MAX_TABLE_ROWS) {
            sb.append(String.format("\n*(当前表格总共 %d 行，已截取展示前 %d 行)*\n",
                    rows.size(), MAX_TABLE_ROWS));
        }
        if (totalMaxCols > MAX_TABLE_COLS) {
            sb.append(String.format("\n*(当前表格最宽行共 %d 列，已截取展示前 %d 列)*\n",
                    totalMaxCols, MAX_TABLE_COLS));
        }

        return sb.toString();
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
}
