package com.stioc.cute.file.decode.impl;

import com.stioc.cute.file.decode.FileDecoder;
import com.stioc.cute.platform.common.NativeCharsetKit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Set;

/**
 * 纯文本、代码及通用文本文件解码器
 * 复用 NativeCharsetKit 进行严格 UTF-8 校验与系统原生 ANSI/GBK 编码自动探测
 */
@Slf4j
@Component
public class PlainTextFileDecoder implements FileDecoder {

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "json", "csv", "xml", "yaml", "yml", "log",
            "java", "py", "js", "ts", "html", "css", "sql", "sh", "bat", "cmd",
            "c", "cpp", "h", "hpp", "go", "rs", "kt", "vue", "properties", "ini",
            "conf", "toml", "env", "gitignore", "dockerfile"
    );

    @Override
    public boolean supports(String extension, String mimeType) {
        if (StringUtils.hasText(extension) && TEXT_EXTENSIONS.contains(extension.toLowerCase())) {
            return true;
        }
        return StringUtils.hasText(mimeType) && (mimeType.startsWith("text/") || "application/json".equalsIgnoreCase(mimeType) || "application/xml".equalsIgnoreCase(mimeType));
    }

    @Override
    public String decode(File file) throws Exception {
        if (file == null || !file.exists()) {
            return "";
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length == 0) {
            return "";
        }

        // 复用 NativeCharsetKit 严格探测字符集（UTF-8 优先，失败自动回退 Windows ANSI/GBK 原生编码）
        Charset charset = NativeCharsetKit.detectCharset(bytes, bytes.length);
        return new String(bytes, charset);
    }
}
