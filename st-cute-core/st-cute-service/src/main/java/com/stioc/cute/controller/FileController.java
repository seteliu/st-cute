package com.stioc.cute.controller;

import com.stioc.cute.file.FileStorageService;
import com.stioc.cute.file.types.FileUploadVo;
import com.stioc.cute.platform.common.BusinessException;
import com.stioc.cute.platform.common.Result;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 文件上传与资源访问 API 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/file")
public class FileController {

    @Resource
    private FileStorageService fileStorageService;

    /**
     * 文件上传接口
     *
     * @param cid      会话 ID
     * @param file     文件对象
     * @param compress 是否进行图片等比缩放与画质压缩（默认 true）
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<FileUploadVo> upload(
            @RequestParam("cid") Long cid,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "compress", required = false, defaultValue = "true") Boolean compress) {
        FileUploadVo vo = fileStorageService.uploadFile(cid, file, compress);
        return Result.success(vo);
    }

    /**
     * 文件查看与下载接口 (支持原始文件流 raw、缩略图 thumbnail)
     * <p>
     * 读取范围受附件沙箱约束：仅允许读取 {@code ~/.st-cute/files} 目录内的文件，
     * 越权路径（含 {@code ..} 穿越、软链逃逸）一律按 404 处理，不暴露文件是否存在以外的任何信息。
     *
     * @param path     文件路径（绝对路径，且必须位于附件沙箱目录内）
     * @param mode     模式：raw (原文件) 或 thumbnail (缩略图)
     * @param download 是否强制下载
     * @param response HTTP 响应对象
     */
    @GetMapping("/view")
    public void view(
            @RequestParam("path") String path,
            @RequestParam(value = "mode", required = false, defaultValue = "raw") String mode,
            @RequestParam(value = "download", required = false, defaultValue = "false") Boolean download,
            HttpServletResponse response) {
        try {
            // 路径解析 + 沙箱校验：绝对路径且必须落在 ~/.st-cute/files 内
            File file = fileStorageService.resolveAttachmentFile(path);
            if (file == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            if ("thumbnail".equalsIgnoreCase(mode)) {
                byte[] thumbBytes = fileStorageService.getThumbnailBytes(file);
                response.setContentType("image/jpeg");
                response.setContentLength(thumbBytes.length);
                try (OutputStream os = response.getOutputStream()) {
                    os.write(thumbBytes);
                    os.flush();
                }
                return;
            }

            // 默认为 raw 原始文件模式
            String ext = FileStorageService.getFileExtension(file.getName());
            String mimeType = FileStorageService.detectMimeType(ext);

            response.setContentType(mimeType);
            response.setContentLengthLong(file.length());

            // SVG 内联安全防御：SVG 是唯一可携带脚本的"图片"格式，以 inline 方式输出时
            // 其内嵌 <script> 会在应用同源下执行，可读取会话数据或驱动接口。
            // 仅对 SVG 施加限制：强制降级为附件下载（不在浏览器内联渲染），
            // 并补 CSP 与 nosniff 双重兜底，避免直接打开链接即触发脚本
            if (isSvg(mimeType, ext)) {
                response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
            }
            response.setHeader("X-Content-Type-Options", "nosniff");

            String encodedFilename = URLEncoder.encode(file.getName(), StandardCharsets.UTF_8).replace("+", "%20");
            // SVG 无论调用方是否要求下载，一律以附件形式返回，杜绝内联渲染执行脚本
            boolean forceAttachment = isSvg(mimeType, ext);
            String dispositionType = (Boolean.TRUE.equals(download) || forceAttachment) ? "attachment" : "inline";
            response.setHeader("Content-Disposition", dispositionType + "; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename);

            try (InputStream is = new FileInputStream(file);
                 OutputStream os = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                os.flush();
            }

        } catch (Exception e) {
            log.warn("文件查看或下载异常: path={}, mode={}, error={}", path, mode, e.getMessage());
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    /**
     * 判定目标是否为 SVG（可按 MIME 或扩展名命中）。
     * <p>
     * SVG 属"可执行图片"：以 inline 渲染时其中的脚本会在应用同源下运行，
     * 故需单独施加更严格的响应头策略。
     * </p>
     *
     * @param mimeType 探测出的 MIME 类型
     * @param ext      文件扩展名（不含点）
     * @return true 表示是 SVG
     */
    private boolean isSvg(String mimeType, String ext) {
        return "svg".equalsIgnoreCase(ext)
                || (mimeType != null && mimeType.toLowerCase(Locale.ROOT).contains("image/svg"));
    }
}
