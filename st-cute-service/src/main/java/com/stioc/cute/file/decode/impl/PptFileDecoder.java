package com.stioc.cute.file.decode.impl;

import com.stioc.cute.file.access.DecodeParam;
import com.stioc.cute.file.decode.FileDecoder;
import com.stioc.cute.llm.CuteAttachment;
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

    /**
     * 单文档最大解析幻灯片页数限制，超出截断并追加提示
     */
    private static final int MAX_SLIDES = 100;

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
    public List<CuteAttachment> decodeToAttachments(File file, DecodeParam ctx) throws Exception {
        String name = file.getName().toLowerCase();
        String content = name.endsWith(".ppt") ? decodePpt(file) : decodePptx(file);
        return List.of(buildTextAttachment(file, ctx, content));
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

            // 页数超出上限时截断，仅解析前 MAX_SLIDES 页
            int slideLimit = Math.min(slides.size(), MAX_SLIDES);
            for (int i = 0; i < slideLimit; i++) {
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

            // 未展示的页统一汇总提示
            if (slides.size() > MAX_SLIDES) {
                sb.append(String.format("\n[说明: 本演示文稿共 %d 页，超过单文档解析上限 %d 页，仅解析前 %d 页]\n",
                        slides.size(), MAX_SLIDES, MAX_SLIDES));
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

            // 页数超出上限时截断，仅解析前 MAX_SLIDES 页
            int slideLimit = Math.min(slides.size(), MAX_SLIDES);
            for (int i = 0; i < slideLimit; i++) {
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

            // 未展示的页统一汇总提示
            if (slides.size() > MAX_SLIDES) {
                sb.append(String.format("\n[说明: 本演示文稿共 %d 页，超过单文档解析上限 %d 页，仅解析前 %d 页]\n",
                        slides.size(), MAX_SLIDES, MAX_SLIDES));
            }

            return sb.toString().trim();
        }
    }
}
