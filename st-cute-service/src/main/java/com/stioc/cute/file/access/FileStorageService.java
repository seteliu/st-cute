package com.stioc.cute.file.access;

import org.springframework.web.multipart.MultipartFile;

import java.io.File;

/**
 * 文件存储与管理核心服务接口
 */
public interface FileStorageService {

    /**
     * 虚拟路径前缀：表示当前操作系统用户主目录（如 $user/.st-cute/...）
     */
    String USER_HOME_PREFIX = "$user/";

    /**
     * 获取用户主目录下的 files 根物理目录
     */
    File getFilesRootDir();

    /**
     * 上传并持久化文件至指定会话的文件目录
     *
     * @param cid      会话 ID
     * @param file     上传的 MultipartFile
     * @param compress 是否开启图片压缩与等比缩放（默认 true）
     * @return 存储元数据对象
     */
    FileUploadVo uploadFile(Long cid, MultipartFile file, Boolean compress);

    /**
     * 根据相对路径获取安全校验后的物理文件对象（沙箱限定在用户主目录 files 根目录内）
     */
    File getSafeFile(String relativePath);

    /**
     * 多形态路径统一解析（供附件回填与文件查看接口共用）。
     * 支持三种形态：
     * 1. $user/...    → 用户主目录下（如 $user/.st-cute/files/cid_1/x.png）
     * 2. 相对路径     → 指定基准目录下（.st-cute/xxx 或 src/xxx 等）
     * 3. 绝对路径     → 直接规范化（如 D:/docs/a.pdf）
     *
     * @param pathVal 原始路径字符串
     * @param baseDir 相对路径的基准目录（项目根或 worktree 路径），可为 null
     * @return 解析后的物理文件；文件不存在或路径非法时返回 null
     */
    File resolveFlexiblePath(String pathVal, String baseDir);

    /**
     * 获取指定图片的等比缩略图字节数据
     */
    byte[] getThumbnailBytes(String relativePath);

    /**
     * 基于已解析的物理文件生成等比缩略图字节数据（生成失败时回退原始字节）
     */
    byte[] getThumbnailBytesFlexible(File file);

    /**
     * 获取指定文件的 Base64 编码数据传输对象
     */
    FileBase64Vo getFileBase64Vo(String relativePath);

    /**
     * 基于已解析的物理文件构建 Base64 编码数据传输对象
     */
    FileBase64Vo getFileBase64VoFlexible(File file);

    /**
     * 删除单条相对路径对应的物理文件
     */
    boolean deleteFile(String relativePath);

    /**
     * 级联删除会话对应的整个附件文件夹
     */
    void deleteConversationFiles(Long cid);

    // ==================== 静态工具方法 ====================

    /**
     * 提取文件扩展名（小写、不带点）
     */
    static String getFileExtension(String filename) {
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
    static String stripExt(String filename) {
        if (filename == null || filename.isBlank()) {
            return filename;
        }
        int idx = filename.lastIndexOf('.');
        return idx > 0 ? filename.substring(0, idx) : filename;
    }

    /**
     * 按扩展名推导常见 MIME 类型
     */
    static String detectMimeType(String extension) {
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
