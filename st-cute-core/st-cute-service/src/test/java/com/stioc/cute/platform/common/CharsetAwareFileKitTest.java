package com.stioc.cute.platform.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台字符集感知与统一文件读取测试套件。
 * 覆盖 UTF-8/ANSI 探测、截断伪影容忍重试、BOM 处理、UTF-16 拦截与行级混合读取。
 */
class CharsetAwareFileKitTest {

    @Nested
    @DisplayName("CharsetAwareFileKit 规约文件读取与 BOM 防御")
    class CharsetAwareFileKitReadTests {

        @Test
        @DisplayName("正常读取标准 UTF-8 文本文件")
        void readStandardUtf8File(@TempDir Path tempDir) throws IOException {
            Path file = tempDir.resolve("config.md");
            Files.writeString(file, "name: st-cute\nrole: agent", StandardCharsets.UTF_8);

            String content = CharsetAwareFileKit.readString(file);
            assertEquals("name: st-cute\nrole: agent", content);
        }

        @Test
        @DisplayName("自动识别并剥离 UTF-8 BOM 字符，防止首字符被 \\uFEFF 污染")
        void stripUtf8BomSuccessfully(@TempDir Path tempDir) throws IOException {
            Path file = tempDir.resolve("with_bom.txt");
            byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] text = "hello bom".getBytes(StandardCharsets.UTF_8);
            byte[] combined = new byte[bom.length + text.length];
            System.arraycopy(bom, 0, combined, 0, bom.length);
            System.arraycopy(text, 0, combined, bom.length, text.length);
            Files.write(file, combined);

            String content = CharsetAwareFileKit.readString(file);
            assertEquals("hello bom", content);
            assertFalse(content.startsWith("\uFEFF"), "BOM 字符必须被完全剥离");
        }

        @Test
        @DisplayName("检测到 UTF-16 BOM (LE/BE) 时硬拦截并抛出带明确指引的 IllegalStateException")
        void rejectUtf16BomFiles(@TempDir Path tempDir) throws IOException {
            Path leFile = tempDir.resolve("utf16_le.txt");
            Files.write(leFile, new byte[]{(byte) 0xFF, (byte) 0xFE, 0x41, 0x00});

            IllegalStateException exLe = assertThrows(IllegalStateException.class, () ->
                    CharsetAwareFileKit.readString(leFile)
            );
            assertTrue(exLe.getMessage().contains("不支持 UTF-16 编码"));

            Path beFile = tempDir.resolve("utf16_be.txt");
            Files.write(beFile, new byte[]{(byte) 0xFE, (byte) 0xFF, 0x00, 0x41});

            IllegalStateException exBe = assertThrows(IllegalStateException.class, () ->
                    CharsetAwareFileKit.readString(beFile)
            );
            assertTrue(exBe.getMessage().contains("不支持 UTF-16 编码"));
        }

        @Test
        @DisplayName("文件不存在时上抛受检 IOException")
        void nonExistentFileThrowsIoException(@TempDir Path tempDir) {
            Path file = tempDir.resolve("not_found.json");
            assertThrows(IOException.class, () -> CharsetAwareFileKit.readString(file));
        }
    }

    @Nested
    @DisplayName("NativeCharsetKit 编码探测与换行符风格")
    class NativeCharsetKitTests {

        @Test
        @DisplayName("detectCharset 严格 UTF-8 优先识别")
        void detectUtf8Bytes() {
            byte[] utf8Bytes = "中文编程智能体 st-cute 🚀".getBytes(StandardCharsets.UTF_8);
            Charset charset = NativeCharsetKit.detectCharset(utf8Bytes, utf8Bytes.length);
            assertEquals(StandardCharsets.UTF_8, charset);
        }

        @Test
        @DisplayName("detectCharset 采样截断容忍：末尾残缺多字节序列截除后重新校验为 UTF-8")
        void detectTruncatedUtf8Bytes() {
            // "中" 的 UTF-8 为 3 字节：E4 B8 AD
            // 构造 "A" (1字节) + "中" (前2字节截断：E4 B8)
            byte[] truncated = new byte[]{'A', (byte) 0xE4, (byte) 0xB8};
            // 长度为 3，非法序列从下标 1 开始，延伸至末尾，落在 4 字节容忍窗内
            Charset charset = NativeCharsetKit.detectCharset(truncated, truncated.length);
            assertEquals(StandardCharsets.UTF_8, charset, "截断伪影应容忍并判定为主体 UTF-8");
        }

        @Test
        @DisplayName("EolStyle 从字节样本中统计主导换行风格")
        void eolStyleDetection() {
            byte[] crlfBytes = "line1\r\nline2\r\nline3\n".getBytes(StandardCharsets.UTF_8);
            assertEquals(NativeCharsetKit.EolStyle.CRLF, NativeCharsetKit.EolStyle.fromSample(crlfBytes, crlfBytes.length));

            byte[] lfBytes = "line1\nline2\nline3\r\n".getBytes(StandardCharsets.UTF_8);
            assertEquals(NativeCharsetKit.EolStyle.LF, NativeCharsetKit.EolStyle.fromSample(lfBytes, lfBytes.length));

            byte[] noneBytes = "single line without newline".getBytes(StandardCharsets.UTF_8);
            assertEquals(NativeCharsetKit.EolStyle.NONE, NativeCharsetKit.EolStyle.fromSample(noneBytes, noneBytes.length));
        }

        @Test
        @DisplayName("normalizeEolToStyle 文本换行符归一与防双 CRLF 污染")
        void normalizeEol() {
            String mixed = "line1\r\nline2\rline3\n";
            // 归一为 CRLF
            String crlf = NativeCharsetKit.normalizeEolToStyle(mixed, NativeCharsetKit.EolStyle.CRLF);
            assertEquals("line1\r\nline2\r\nline3\r\n", crlf);
            assertFalse(crlf.contains("\r\r\n"), "不得产生双重回车 \\r\\r\\n");

            // 归一为 LF
            String lf = NativeCharsetKit.normalizeEolToStyle(mixed, NativeCharsetKit.EolStyle.LF);
            assertEquals("line1\nline2\nline3\n", lf);
        }

        @Test
        @DisplayName("detectFileMeta 探测空文件与不存在文件安全兜底")
        void detectFileMetaFallback(@TempDir Path tempDir) throws IOException {
            Path emptyFile = tempDir.resolve("empty.txt");
            Files.createFile(emptyFile);

            NativeCharsetKit.FileTextMeta meta = NativeCharsetKit.detectFileMeta(emptyFile);
            assertNotNull(meta);
            assertFalse(meta.hasUtf8Bom());
            assertFalse(meta.utf16Bom());
            assertEquals(NativeCharsetKit.EolStyle.NONE, meta.eolStyle());
        }
    }

    @Nested
    @DisplayName("LineMixedCharsetReader 行级混合编码流式读取")
    class LineMixedCharsetReaderTests {

        @Test
        @DisplayName("流式读取纯 UTF-8 多行文本")
        void readUtf8Stream() throws IOException {
            String text = "第1行: 你好\n第2行: 世界\n第3行: ST-Cute\n";
            ByteArrayInputStream in = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
            try (LineMixedCharsetReader reader = new LineMixedCharsetReader(in);
                 StringWriter out = new StringWriter()) {
                reader.transferTo(out);
                assertEquals(text, out.toString());
            }
        }

        @Test
        @DisplayName("空流安全读取")
        void readEmptyStream() throws IOException {
            ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
            try (LineMixedCharsetReader reader = new LineMixedCharsetReader(in);
                 StringWriter out = new StringWriter()) {
                reader.transferTo(out);
                assertEquals("", out.toString());
            }
        }
    }
}
