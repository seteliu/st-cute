package com.stioc.cute.file.decode.impl;

import com.stioc.cute.file.decode.FileDecoder;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

/**
 * PPT 演示文稿解码器
 * 支持 .pptx (OOXML) 与 .ppt (PowerPoint 97-2003) 格式
 * 按幻灯片页（Slide）组织提取正文文本、文本框与演讲者备注
 */
@Slf4j
@Component
public class PptFileDecoder implements FileDecoder {

    @Override
    public boolean supports(String extension, String mimeType) {
        if ("pptx".equalsIgnoreCase(extension) || "ppt".equalsIgnoreCase(extension)) {
            return true;
        }
        if (StringUtils.hasText(mimeType)) {
            return mimeType.contains("presentationml") || "application/vnd.ms-powerpoint".equalsIgnoreCase(mimeType);
        }
        return false;
    }

    @Override
    public String decode(File file) throws Exception {
        if (file == null || !file.exists()) {
            return "";
        }

        String name = file.getName().toLowerCase();
        if (name.endsWith(".ppt")) {
            return decodePpt(file);
        } else {
            return decodePptx(file);
        }
    }

    /**
     * 解析 .pptx 格式幻灯片
     */
    private String decodePptx(File file) throws Exception {
        try (InputStream is = new FileInputStream(file);
             XMLSlideShow slideShow = new XMLSlideShow(is)) {

            List<XSLFSlide> slides = slideShow.getSlides();
            if (slides == null || slides.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("【PPT 演示文稿解析，共 %d 页】\n", slides.size()));

            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                StringBuilder slideText = new StringBuilder();

                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        for (XSLFTextParagraph p : textShape.getTextParagraphs()) {
                            String text = p.getText();
                            if (StringUtils.hasText(text)) {
                                slideText.append(text.trim()).append("\n");
                            }
                        }
                    }
                }

                // 提取演讲者备注（若存在）
                XSLFNotes notes = slide.getNotes();
                if (notes != null) {
                    for (XSLFShape shape : notes.getShapes()) {
                        if (shape instanceof XSLFTextShape textShape) {
                            String noteText = textShape.getText();
                            if (StringUtils.hasText(noteText)) {
                                slideText.append("[备注]: ").append(noteText.trim()).append("\n");
                            }
                        }
                    }
                }

                if (StringUtils.hasText(slideText)) {
                    sb.append(String.format("\n--- [Slide %d: %s] ---\n", i + 1, slide.getTitle() != null ? slide.getTitle() : "幻灯片 " + (i + 1)));
                    sb.append(slideText.toString().trim()).append("\n");
                }
            }

            return sb.toString().trim();
        }
    }

    /**
     * 解析旧版 .ppt 格式幻灯片
     */
    private String decodePpt(File file) throws Exception {
        try (InputStream is = new FileInputStream(file);
             HSLFSlideShow slideShow = new HSLFSlideShow(is)) {

            List<HSLFSlide> slides = slideShow.getSlides();
            if (slides == null || slides.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("【PPT 演示文稿解析，共 %d 页】\n", slides.size()));

            for (int i = 0; i < slides.size(); i++) {
                HSLFSlide slide = slides.get(i);
                StringBuilder slideText = new StringBuilder();

                for (List<HSLFTextParagraph> paragraphsList : slide.getTextParagraphs()) {
                    for (HSLFTextParagraph p : paragraphsList) {
                        String text = HSLFTextParagraph.getText(List.of(p));
                        if (StringUtils.hasText(text)) {
                            slideText.append(text.trim()).append("\n");
                        }
                    }
                }

                if (StringUtils.hasText(slideText)) {
                    sb.append(String.format("\n--- [Slide %d: %s] ---\n", i + 1, slide.getTitle() != null ? slide.getTitle() : "幻灯片 " + (i + 1)));
                    sb.append(slideText.toString().trim()).append("\n");
                }
            }

            return sb.toString().trim();
        }
    }
}
