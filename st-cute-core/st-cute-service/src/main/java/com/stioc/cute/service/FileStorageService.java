package com.stioc.cute.service;

import com.stioc.cute.service.types.FileBase64Vo;
import com.stioc.cute.service.types.FileUploadVo;
import com.stioc.cute.platform.common.BusinessException;
import com.stioc.cute.platform.contract.ContractFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 文件存储与管理核心服务。
 * <p>
 * 对外契约：上传 {@link #uploadFile(Long, MultipartFile, Boolean)}、
 * 多形态路径统一解析 {@link #resolveFlexiblePath(String, String)}、
 * 缩略图与 Base64 读取、文件与会话附件级联删除，另附文件扩展名 / MIME 推导静态工具。
 * </p>
 * <p>
 * 路径语义：上传返回附件文件的绝对路径；读取侧统一接受项目相对路径或绝对路径。
 * </p>
 */
@Slf4j
@Service
public class FileStorageService {

    /**
     * 单文件大小上限：10 MB
     */
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024L;

    /**
     * 支持的文件格式白名单
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            // 图片格式
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "svg",
            // 文本与文档格式
            "txt", "md", "markdown", "json", "csv", "xml", "yaml", "yml", "log",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf",
            // 常见代码格式
            "java", "py", "js", "ts", "html", "css", "sql", "sh", "bat", "cmd",
            "c", "cpp", "h", "hpp", "go", "rs", "kt", "vue"
    );

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    public File getFilesRootDir() {
        File userHome = new File(System.getProperty("user.home"));
        return new File(userHome, ".st-cute/files");
    }
    public FileUploadVo uploadFile(Long cid, MultipartFile file, Boolean compress) {
        if (cid == null || cid <= 0) {
            throw new BusinessException("会话 ID 无效");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传的文件内容为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("文件大小超过上限限制 (最大允许 10MB)");
        }

        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename)) {
            originalFilename = "unknown_" + System.currentTimeMillis();
        }

        String extension = FileStorageService.getFileExtension(originalFilename);
        if (!isExtensionAllowed(extension)) {
            throw new BusinessException("不支持的文件格式: " + extension);
        }

        // 构造会话物理存储目录（并发安全创建）
        File rootDir = getFilesRootDir();
        File cidDir = new File(rootDir, "cid_" + cid);
        try {
            Files.createDirectories(cidDir.toPath());
        } catch (Exception e) {
            log.error("创建会话附件存储目录失败: cid={}", cid, e);
            throw new BusinessException("创建会话附件存储目录失败: " + e.getMessage());
        }

        // 生成存储文件名：yyyyMMdd_HHmmss_SSS_xxxx.ext，并确保并发文件名无冲突
        String timestamp = LocalDateTime.now().format(DATE_TIME_FORMATTER);
        File targetFile = null;
        String newFilename = null;
        for (int i = 0; i < 20; i++) {
            int randomNum = ThreadLocalRandom.current().nextInt(10000);
            newFilename = String.format("%s_%04d%s", timestamp, randomNum, StringUtils.hasText(extension) ? "." + extension : "");
            File candidate = new File(cidDir, newFilename);
            if (!candidate.exists()) {
                targetFile = candidate;
                break;
            }
        }
        if (targetFile == null) {
            newFilename = String.format("%s_%s%s", timestamp, UUID.randomUUID().toString().substring(0, 8), StringUtils.hasText(extension) ? "." + extension : "");
            targetFile = new File(cidDir, newFilename);
        }
        boolean compressed = false;
        boolean shouldCompress = (compress == null || compress) && ImageProcessUtils.isImage(extension, file.getContentType());

        try {
            if (shouldCompress) {
                byte[] rawBytes = file.getBytes();
                // 上传链路压缩按平台统一规格执行（ImageProcessUtils 集中维护），大于原始体积时自动保留原字节
                byte[] processedBytes = ImageProcessUtils.compressAndResize(rawBytes, extension,
                        ImageProcessUtils.MAX_DIMENSION, ImageProcessUtils.COMPRESS_QUALITY);
                if (processedBytes != null && processedBytes.length > 0) {
                    compressed = processedBytes.length < rawBytes.length;
                    if (compressed) {
                        // 压缩转码可能改变真实格式（如无透明 png/bmp 转 JPEG），探测真实格式同步存储后缀，
                        // 保证落盘文件后缀、实际字节编码、对外的 MIME 三者一致
                        String realFormat = ImageProcessUtils.detectImageFormat(processedBytes);
                        if (StringUtils.hasText(realFormat) && !realFormat.equalsIgnoreCase(extension)) {
                            extension = realFormat;
                            newFilename = String.format("%s%s", FileStorageService.stripExt(newFilename),
                                    StringUtils.hasText(extension) ? "." + extension : "");
                            targetFile = new File(cidDir, newFilename);
                        }
                        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                            fos.write(processedBytes);
                        }
                    }
                }
            }

            if (!compressed) {
                file.transferTo(targetFile);
            }

            long actualSize = targetFile.length();
            // 存储路径统一对外暴露为绝对路径（保留正斜杠，与平台其他路径展示口径一致），
            // 前端回显与模型侧读取均按绝对路径直达，不再引入虚拟前缀寻址
            String absolutePath = targetFile.getAbsolutePath().replace("\\", "/");
            // 后缀随压缩转码同步后，MIME 以落盘文件的真实后缀为准；后缀未变时优先保留原始 Content-Type
            String mimeType;
            if (compressed && StringUtils.hasText(file.getContentType())
                    && file.getContentType().equalsIgnoreCase(FileStorageService.detectMimeType(extension))) {
                mimeType = file.getContentType();
            } else {
                mimeType = FileStorageService.detectMimeType(extension);
            }

            log.info("文件上传成功: cid={}, 原始名={}, 存储路径={}, 大小={} bytes, 压缩={}",
                    cid, originalFilename, absolutePath, actualSize, compressed);

            return FileUploadVo.builder()
                    .path(absolutePath)
                    .name(originalFilename)
                    .size(actualSize)
                    .mimeType(mimeType)
                    .compressed(compressed)
                    .build();

        } catch (Exception e) {
            log.error("文件上传保存失败: cid={}, filename={}", cid, originalFilename, e);
            throw new BusinessException("文件上传处理失败: " + e.getMessage());
        }
    }
    public File resolveFlexiblePath(String pathVal, String baseDir) {
        if (!StringUtils.hasText(pathVal)) {
            return null;
        }
        String cleanPath = pathVal.trim().replace('\\', '/');

        // Windows 绝对路径（盘符）与 Unix 绝对路径（/ 开头）
        Path path = Paths.get(cleanPath);
        if (path.isAbsolute()) {
            Path target = path.toAbsolutePath().normalize();
            return target.toFile().isFile() ? target.toFile() : null;
        }

        // 相对路径：以项目根目录为基准
        if (!StringUtils.hasText(baseDir)) {
            return null;
        }
        Path target = Paths.get(baseDir).resolve(cleanPath).toAbsolutePath().normalize();
        return target.toFile().isFile() ? target.toFile() : null;
    }
    /**
     * 生成指定文件的缩略图字节（缩略图生成失败时降级返回原文件字节）
     */
    public byte[] getThumbnailBytes(File file) {
        String ext = FileStorageService.getFileExtension(file.getName());
        byte[] thumbnail = ImageProcessUtils.generateThumbnail(file, ext);
        if (thumbnail == null) {
            try {
                return Files.readAllBytes(file.toPath());
            } catch (Exception e) {
                throw new BusinessException("读取缩略图失败: " + e.getMessage());
            }
        }
        return thumbnail;
    }
    /**
     * 读取指定文件的 Base64 编码与元数据
     */
    public FileBase64Vo getFileBase64Vo(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String base64Str = Base64.getEncoder().encodeToString(bytes);
            String ext = FileStorageService.getFileExtension(file.getName());
            String mimeType = FileStorageService.detectMimeType(ext);

            return FileBase64Vo.builder()
                    .name(file.getName())
                    .size(file.length())
                    .mimeType(mimeType)
                    .base64(base64Str)
                    .build();
        } catch (Exception e) {
            log.error("读取文件转 Base64 异常: {}", e.getMessage(), e);
            throw new BusinessException("读取文件 Base64 失败: " + e.getMessage());
        }
    }
    public void deleteConversationFiles(Long cid) {
        if (cid == null || cid <= 0) {
            return;
        }
        try {
            // 级联清理该会话的附件目录（files）与临时目录（tmp）下的 cid 文件夹
            deleteCidDirectory(getFilesRootDir(), cid);
            deleteCidDirectory(getTmpRootDir(), cid);
            log.info("已成功级联清空并删除会话物理文件目录: cid={}", cid);
        } catch (Exception e) {
            log.warn("清理会话物理附件目录异常: cid={}, error={}", cid, e.getMessage());
        }
    }

    /**
     * 获取临时文件根目录：~/.st-cute/tmp（会话级临时目录约定为该目录下的 cid_{cid} 子目录，
     * 随会话删除一并级联清理）
     */
    public File getTmpRootDir() {
        return new File(ContractFile.getGlobalDir(), "tmp");
    }

    /**
     * 递归清空并删除指定根目录下的会话级 cid_{cid} 目录（目录不存在时静默跳过）
     */
    private void deleteCidDirectory(File rootDir, Long cid) {
        try {
            File cidDir = new File(rootDir, "cid_" + cid);
            if (cidDir.exists() && cidDir.isDirectory()) {
                Files.walk(cidDir.toPath())
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (Exception e) {
            log.warn("删除会话目录失败: root={}, cid={}, error={}", rootDir.getName(), cid, e.getMessage());
        }
    }

    /**
     * 判断扩展名是否在上传白名单内
     */
    private static boolean isExtensionAllowed(String extension) {
        if (!StringUtils.hasText(extension)) {
            return true;
        }
        return ALLOWED_EXTENSIONS.contains(extension.toLowerCase());
    }

    // ==================== 静态工具方法 ====================

    /**
     * 提取文件扩展名（小写、不带点）
     */
    public static String getFileExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        int idx = filename.lastIndexOf('.');
        if (idx >= 0 && idx < filename.length() - 1) {
            return filename.substring(idx + 1).toLowerCase();
        }
        return "";
    }

    /**
     * 去除文件名中的扩展名部分（无扩展名时原样返回）
     */
    public static String stripExt(String filename) {
        if (filename == null || filename.isBlank()) {
            return filename;
        }
        int idx = filename.lastIndexOf('.');
        return idx > 0 ? filename.substring(0, idx) : filename;
    }

    /**
     * 按扩展名推导常见 MIME 类型
     */
    public static String detectMimeType(String extension) {
        if (extension == null || extension.isBlank()) {
            return "application/octet-stream";
        }
        return switch (extension.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "svg" -> "image/svg+xml";
            case "pdf" -> "application/pdf";
            case "txt", "log" -> "text/plain";
            case "md", "markdown" -> "text/markdown";
            case "json" -> "application/json";
            case "csv" -> "text/csv";
            case "xml" -> "application/xml";
            case "yaml", "yml" -> "text/yaml";
            case "html" -> "text/html";
            case "css" -> "text/css";
            case "js" -> "application/javascript";
            case "ts" -> "application/typescript";
            case "java" -> "text/x-java-source";
            case "py" -> "text/x-python";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "application/octet-stream";
        };
    }
}
