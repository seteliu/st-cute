package com.stioc.cute.security.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * 文件内容哈希工具：为"read_file 读取门禁"提供内容一致性校验能力。
 * read_file / write_to_file / replace_file_content 写入或读取文件时记录 SHA-256 摘要，
 * 修改前校验摘要与磁盘当前内容一致才放行（防幻觉 + 防过时修改双保险）
 */
public final class FileHashSupport {

    private static final Logger log = LoggerFactory.getLogger(FileHashSupport.class);

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
    public static String computeFileHash(java.nio.file.Path path) {
        return path == null ? "" : computeFileHash(path.toFile());
    }
}
