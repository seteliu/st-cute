package com.stioc.cute.file;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * 文件内容哈希工具：为"read_file 读取门禁"提供内容一致性校验能力。
 * read_file / write_file / edit_file 写入或读取文件时记录 SHA-256 摘要，
 * 修改前校验摘要与磁盘当前内容一致才放行（防幻觉 + 防过时修改双保险）
 */
@Slf4j
public final class FileHashSupport {

    private FileHashSupport() {
        // 工具类禁止实例化
    }

    /**
     * 计算文件完整内容的 SHA-256 摘要（十六进制小写）。
     * 读取失败时返回空串（调用方将空串视为失配，安全方向保守）
     */
    public static String computeFileHash(File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("计算文件哈希失败: {}, 异常: {}", file.getAbsolutePath(), e.getMessage());
            return "";
        }
    }

    /**
     * 按路径计算文件哈希（等价于 computeFileHash(new File(path))）
     */
    public static String computeFileHash(Path path) {
        return path == null ? "" : computeFileHash(path.toFile());
    }

    /**
     * 获取用于在 readFiles 缓存中作为唯一 Key 的文件规范路径。
     * 在 Windows 下通过 getCanonicalPath 消除盘符大小写与路径规范化差异，
     * 避免因大小写不一致触发门禁误拦截。
     */
    public static String toStorageKey(File file) {
        if (file == null) {
            return "";
        }
        try {
            return file.getCanonicalPath();
        } catch (Exception e) {
            return file.getAbsolutePath();
        }
    }

    /**
     * 按 Path 获取用于在 readFiles 缓存中作为唯一 Key 的文件规范路径。
     */
    public static String toStorageKey(Path path) {
        return path == null ? "" : toStorageKey(path.toFile());
    }
}
