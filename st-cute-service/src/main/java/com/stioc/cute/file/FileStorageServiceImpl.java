package com.stioc.cute.file;

import com.stioc.cute.file.access.FileBase64Vo;
import com.stioc.cute.file.access.FileStorageService;
import com.stioc.cute.file.access.FileUploadVo;
import com.stioc.cute.platform.common.BusinessException;
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
 * 文件存储与管理核心服务实现
 */
@Slf4j
@Service
public class FileStorageServiceImpl implements FileStorageService {

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

    @Override
    public File getFilesRootDir() {
        File userHome = new File(System.getProperty("user.home"));
        return new File(userHome, ".st-cute/files");
    }

    @Override
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
            String relativePath = USER_HOME_PREFIX + ".st-cute/files/cid_" + cid + "/" + newFilename;
            // 后缀随压缩转码同步后，MIME 以落盘文件的真实后缀为准；后缀未变时优先保留原始 Content-Type
            String mimeType;
            if (compressed && StringUtils.hasText(file.getContentType())
                    && file.getContentType().equalsIgnoreCase(FileStorageService.detectMimeType(extension))) {
                mimeType = file.getContentType();
            } else {
                mimeType = FileStorageService.detectMimeType(extension);
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

    @Override
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

    @Override
    public File resolveFlexiblePath(String pathVal, String baseDir) {
        if (!StringUtils.hasText(pathVal)) {
            return null;
        }
        String cleanPath = pathVal.trim().replace('\\', '/');

        // $user/ 前缀：映射到用户主目录
        if (cleanPath.startsWith(USER_HOME_PREFIX)) {
            String rest = cleanPath.substring(USER_HOME_PREFIX.length());
            if (rest.isBlank() || rest.contains("..")) {
                return null;
            }
            Path userHome = Paths.get(System.getProperty("user.home"));
            Path target = userHome.resolve(rest).toAbsolutePath().normalize();
            return target.toFile().isFile() ? target.toFile() : null;
        }

        // Windows 绝对路径（盘符）与 Unix 绝对路径（/ 开头）
        Path path = Paths.get(cleanPath);
        if (path.isAbsolute()) {
            Path target = path.toAbsolutePath().normalize();
            return target.toFile().isFile() ? target.toFile() : null;
        }

        // 相对路径：以项目根/worktree 为基准
        if (!StringUtils.hasText(baseDir)) {
            return null;
        }
        Path target = Paths.get(baseDir).resolve(cleanPath).toAbsolutePath().normalize();
        return target.toFile().isFile() ? target.toFile() : null;
    }

    @Override
    public byte[] getThumbnailBytes(String relativePath) {
        File file = getSafeFile(relativePath);
        return getThumbnailBytesFlexible(file);
    }

    @Override
    public byte[] getThumbnailBytesFlexible(File file) {
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

    @Override
    public FileBase64Vo getFileBase64Vo(String relativePath) {
        File file = getSafeFile(relativePath);
        return getFileBase64VoFlexible(file);
    }

    @Override
    public FileBase64Vo getFileBase64VoFlexible(File file) {
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

    @Override
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

    @Override
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

    /**
     * 判断扩展名是否在上传白名单内
     */
    private static boolean isExtensionAllowed(String extension) {
        if (!StringUtils.hasText(extension)) {
            return true;
        }
        return ALLOWED_EXTENSIONS.contains(extension.toLowerCase());
    }
}
