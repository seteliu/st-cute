package com.stioc.cute.file.decode;

import com.stioc.cute.engine.llm.types.CuteAttachment;
import com.stioc.cute.file.types.DecodeParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯文本与代码文件解码器单元测试。
 * 覆盖常见文本/源码扩展名支持校验、内容解码与附件封装。
 */
class PlainTextFileDecoderTest {

    private final PlainTextFileDecoder decoder = new PlainTextFileDecoder();

    @ParameterizedTest(name = "支持文本类扩展名: {0}")
    @ValueSource(strings = {"txt", "md", "markdown", "json", "csv", "xml", "yaml", "yml", "java", "py", "js", "ts", "sql", "sh"})
    @DisplayName("常见纯文本与代码文件格式匹配校验")
    void supportsTextExtensions(String ext) {
        assertTrue(decoder.supports(ext, null));
        assertTrue(decoder.supports(ext.toUpperCase(), null), "扩展名判定大小写不敏感");
    }

    @Test
    @DisplayName("通过 MIME 类型判定支持")
    void supportsMimeTypes() {
        assertTrue(decoder.supports("unknown", "text/plain"));
        assertTrue(decoder.supports("unknown", "text/html"));
        assertTrue(decoder.supports("unknown", "application/json"));
        assertTrue(decoder.supports("unknown", "application/xml"));

        assertFalse(decoder.supports("pdf", "application/pdf"));
        assertFalse(decoder.supports("png", "image/png"));
    }

    @Test
    @DisplayName("成功解码纯文本文件为 CuteAttachment 文本附件")
    void decodePlainTextFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("demo.java");
        Files.writeString(file, "public class Demo {\n    int a = 1;\n}", StandardCharsets.UTF_8);

        DecodeParam param = DecodeParam.textOnly("demo.java");
        List<CuteAttachment> attachments = decoder.decodeToAttachments(file.toFile(), param);

        assertNotNull(attachments);
        assertEquals(1, attachments.size());

        CuteAttachment att = attachments.get(0);
        assertFalse(att.isImage());
        assertEquals("demo.java", att.getName());
        assertTrue(att.getTextContent().contains("public class Demo"));
    }

    @Test
    @DisplayName("空文件解码安全返回空字符串正文")
    void decodeEmptyFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("empty.txt");
        Files.createFile(file);

        List<CuteAttachment> attachments = decoder.decodeToAttachments(file.toFile(), DecodeParam.textOnly("empty.txt"));
        assertNotNull(attachments);
        assertEquals(1, attachments.size());
        assertEquals("", attachments.get(0).getTextContent());
    }
}
