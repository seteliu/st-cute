package com.stioc.cute.file.decode.impl;

import com.stioc.cute.file.decode.FileDecoder;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Word 文档解码器
 * 支持 .docx (OOXML) 和 .doc (Word 97-2003) 格式
 * 提取段落文本并将表格转换为标准 Markdown 表格
 */
@Slf4j
@Component
public class WordFileDecoder implements FileDecoder {

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
    public String decode(File file) throws Exception {
        if (file == null || !file.exists()) {
            return "";
        }

        String name = file.getName().toLowerCase();
        if (name.endsWith(".doc")) {
            return decodeDoc(file);
        } else {
            return decodeDocx(file);
        }
    }

    /**
     * 解析 .docx 格式文档（支持段落与表格转 Markdown）
     */
    private String decodeDocx(File file) throws Exception {
        try (InputStream is = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(is)) {

            StringBuilder sb = new StringBuilder();
            List<IBodyElement> bodyElements = doc.getBodyElements();

            for (IBodyElement element : bodyElements) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (StringUtils.hasText(text)) {
                        sb.append(text).append("\n");
                    }
                } else if (element instanceof XWPFTable table) {
                    String tableMarkdown = renderTableAsMarkdown(table);
                    if (StringUtils.hasText(tableMarkdown)) {
                        sb.append("\n").append(tableMarkdown).append("\n");
                    }
                }
            }

            return sb.toString().trim();
        }
    }

    /**
     * 解析旧版 .doc 格式文档
     */
    private String decodeDoc(File file) throws Exception {
        try (InputStream is = new FileInputStream(file);
             HWPFDocument doc = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(doc)) {
            return extractor.getText().trim();
        }
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

        for (XWPFTableRow row : rows) {
            List<String> rowData = new ArrayList<>();
            List<XWPFTableCell> cells = row.getTableCells();
            for (XWPFTableCell cell : cells) {
                String text = cell.getText();
                text = text != null ? text.trim().replace("\n", " ").replace("|", "\\|") : "";
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

        return sb.toString();
    }
}
