package com.stioc.cute.file.decode.impl;

import com.stioc.cute.file.decode.FileDecoder;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;

/**
 * PDF 文档解码器
 * 基于 Apache PDFBox 解析并提取文本内容，保留分页标识便于模型理解与定位
 */
@Slf4j
@Component
public class PdfFileDecoder implements FileDecoder {

    @Override
    public boolean supports(String extension, String mimeType) {
        return "pdf".equalsIgnoreCase(extension) || "application/pdf".equalsIgnoreCase(mimeType);
    }

    @Override
    public String decode(File file) throws Exception {
        if (file == null || !file.exists()) {
            return "";
        }

        try (PDDocument document = Loader.loadPDF(file)) {
            if (document.isEncrypted()) {
                return "[PDF 文档受密码保护，无法直接读取内容]";
            }

            int numberOfPages = document.getNumberOfPages();
            if (numberOfPages == 0) {
                return "";
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("【PDF 文档解析，共 %d 页】\n", numberOfPages));

            for (int page = 1; page <= numberOfPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);

                if (StringUtils.hasText(pageText)) {
                    sb.append(String.format("\n--- [第 %d 页] ---\n", page));
                    sb.append(pageText.trim()).append("\n");
                }
            }

            return sb.toString().trim();
        }
    }
}
