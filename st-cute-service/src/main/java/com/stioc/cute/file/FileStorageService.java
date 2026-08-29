package com.stioc.cute.file;

import com.stioc.cute.platform.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 文件存储与管理核心服务
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

    /**
     * 获取用户主目录下的 files 根物理目录
     */
    public File getFilesRootDir() {
        File userHome = new File(System.getProperty("user.home"));
        return new File(userHome, ".st-cute/files");
    }

    /**
     * 上传并持久化文件至指定会话的文件目录
     *
     * @param cid      会话 ID
     * @param file     上传的 MultipartFile
     * @param compress 是否开启图片压缩与等比缩放（默认 true）
     * @return 存储元数据对象
     */
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

        String extension = getFileExtension(originalFilename);
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
                byte[] processedBytes = ImageProcessUtils.compressAndResize(rawBytes, extension, 2048, 0.75f);
                if (processedBytes != null && processedBytes.length > 0) {
                    try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                        fos.write(processedBytes);
                    }
                    compressed = processedBytes.length < rawBytes.length;
                }
            }

            if (!compressed) {
                file.transferTo(targetFile);
            }

            long actualSize = targetFile.length();
            String relativePath = ".st-cute/files/cid_" + cid + "/" + newFilename;
            String mimeType = file.getContentType();
            if (!StringUtils.hasText(mimeType)) {
                mimeType = detectMimeType(extension);
            }

            log.info("文件上传成功: cid={}, 原始名={}, 存储路径={}, 大小={} bytes, 压缩={}",
                    cid, originalFilename, relativePath, actualSize, compressed);

            return FileUploadVo.builder()
                    .path(relativePath)
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

    /**
     * 根据相对路径获取安全校验后的物理文件对象
     */
    public File getSafeFile(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            throw new BusinessException("文件路径不能为空");
        }

        // 统一斜杠并清理首尾空格
        String cleanPath = relativePath.trim().replace('\\', '/');
        if (cleanPath.contains("..")) {
            throw new BusinessException("非法的文件访问路径");
        }

        File userHome = new File(System.getProperty("user.home"));
        File targetFile = new File(userHome, cleanPath);

        // 路径沙箱保护：确保目标物理文件绝对路径必须以 files 根目录开头
        File rootDir = getFilesRootDir();
        Path rootPath = rootDir.toPath().toAbsolutePath().normalize();
        Path targetPath = targetFile.toPath().toAbsolutePath().normalize();

        if (!targetPath.startsWith(rootPath)) {
            throw new BusinessException("拒绝访问：目标文件超出沙箱工作区范围");
        }

        if (!targetFile.exists() || !targetFile.isFile()) {
            throw new BusinessException("未找到指定的文件: " + relativePath);
        }

        return targetFile;
    }

    /**
     * 获取指定图片的等比缩略图字节数据
     */
    public byte[] getThumbnailBytes(String relativePath) {
        File file = getSafeFile(relativePath);
        String ext = getFileExtension(file.getName());
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
     * 获取指定文件的 Base64 编码数据传输对象
     */
    public FileBase64Vo getFileBase64Vo(String relativePath) {
        File file = getSafeFile(relativePath);
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String base64Str = Base64.getEncoder().encodeToString(bytes);
            String ext = getFileExtension(file.getName());
            String mimeType = detectMimeType(ext);

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

    /**
     * 删除单条相对路径对应的物理文件
     */
    public boolean deleteFile(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            return false;
        }
        try {
            File file = getSafeFile(relativePath);
            if (file.exists() && file.isFile()) {
                boolean deleted = file.delete();
                log.info("物理文件删除: path={}, success={}", relativePath, deleted);
                return deleted;
            }
        } catch (Exception e) {
            log.warn("物理文件删除失败或文件不存在: path={}, error={}", relativePath, e.getMessage());
        }
        return false;
    }

    /**
     * 级联删除会话对应的整个附件文件夹
     */
    public void deleteConversationFiles(Long cid) {
        if (cid == null || cid <= 0) {
            return;
        }
        try {
            File rootDir = getFilesRootDir();
            File cidDir = new File(rootDir, "cid_" + cid);
            if (cidDir.exists() && cidDir.isDirectory()) {
                Files.walk(cidDir.toPath())
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                log.info("已成功级联清空并删除会话物理文件目录: cid={}", cid);
            }
        } catch (Exception e) {
            log.warn("清理会话物理附件目录异常: cid={}, error={}", cid, e.getMessage());
        }
    }

    public static String getFileExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int idx = filename.lastIndexOf('.');
        if (idx >= 0 && idx < filename.length() - 1) {
            return filename.substring(idx + 1).toLowerCase();
        }
        return "";
    }

    public static boolean isExtensionAllowed(String extension) {
        if (!StringUtils.hasText(extension)) {
            return true;
        }
        return ALLOWED_EXTENSIONS.contains(extension.toLowerCase());
    }

    public static String detectMimeType(String extension) {
        if (!StringUtils.hasText(extension)) {
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
