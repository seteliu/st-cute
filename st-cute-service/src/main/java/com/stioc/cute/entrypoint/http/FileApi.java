package com.stioc.cute.entrypoint.http;

import com.stioc.cute.file.FileBase64Vo;
import com.stioc.cute.file.FileStorageService;
import com.stioc.cute.file.FileUploadVo;
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

/**
 * 文件上传与资源访问 API 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/file")
public class FileApi {

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
     *
     * @param path     相对路径，如 .st-cute/files/cid_1/20260828_173852_000_3217.png
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
            if ("thumbnail".equalsIgnoreCase(mode)) {
                byte[] thumbBytes = fileStorageService.getThumbnailBytes(path);
                response.setContentType("image/jpeg");
                response.setContentLength(thumbBytes.length);
                try (OutputStream os = response.getOutputStream()) {
                    os.write(thumbBytes);
                    os.flush();
                }
                return;
            }

            // 默认为 raw 原始文件模式
            File file = fileStorageService.getSafeFile(path);
            String ext = FileStorageService.getFileExtension(file.getName());
            String mimeType = FileStorageService.detectMimeType(ext);

            response.setContentType(mimeType);
            response.setContentLengthLong(file.length());

            String encodedFilename = URLEncoder.encode(file.getName(), StandardCharsets.UTF_8).replace("+", "%20");
            String dispositionType = Boolean.TRUE.equals(download) ? "attachment" : "inline";
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
     * 获取指定文件的 Base64 编码数据
     *
     * @param path 相对路径
     */
    @GetMapping("/base64")
    public Result<FileBase64Vo> getBase64(@RequestParam("path") String path) {
        FileBase64Vo vo = fileStorageService.getFileBase64Vo(path);
        return Result.success(vo);
    }
}
