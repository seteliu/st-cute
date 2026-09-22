package com.stioc.cute.file;

import com.stioc.cute.engine.llm.types.CuteAttachment;
import com.stioc.cute.file.decode.FileDecoder;
import com.stioc.cute.file.decode.PlainTextFileDecoder;
import com.stioc.cute.file.types.DecodeParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * 文件解码调度与管理服务测试。
 * 覆盖解码器匹配分发、未知文件类型兜底、异常保护以及文本超长截断。
 * <p>
 * 【日志规约】本类的异常容错用例（decoderExceptionHandledGracefully）属于"预期异常"验证：
 * 生产代码 catch 兜底分支只允许打印一行简明日志（仅 e.getMessage()），
 * 禁止把异常对象作为最后一个参数传给 log.warn/error（那样会输出几十行完整堆栈），
 * 否则测试输出会被大量预期堆栈淹没，极易误判为真实故障。
 * 例外：真正意外的、未被 catch 语义覆盖的异常才需要带堆栈打印以便排查。
 */
class FileDecodeServiceTest {

    private FileDecodeService service;
    private PlainTextFileDecoder plainTextDecoder;

    @BeforeEach
    void setUp() {
        plainTextDecoder = new PlainTextFileDecoder();
        // 构造注入真实纯文本解码器
        service = new FileDecodeService(List.of(plainTextDecoder));
    }

    @Nested
    @DisplayName("decodeToAttachments 调度与兜底保护测试")
    class DecodeDispatchTests {

        @Test
        @DisplayName("目标文件为 null 或不存在时安全返回空列表")
        void nullOrMissingFileReturnsEmpty() {
            assertTrue(service.decodeToAttachments(null, "txt", "text/plain", null).isEmpty());
            assertTrue(service.decodeToAttachments(new File("non_existent_file.xyz"), "xyz", null, null).isEmpty());
        }

        @Test
        @DisplayName("未找到匹配解码器时返回格式说明附件，不抛出异常")
        void unknownFormatReturnsExplanationAttachment(@TempDir Path tempDir) throws IOException {
            Path unknownFile = tempDir.resolve("binary.dat");
            Files.write(unknownFile, new byte[]{0x01, 0x02, 0x03});

            List<CuteAttachment> attachments = service.decodeToAttachments(
                    unknownFile.toFile(), "dat", "application/octet-stream",
                    DecodeParam.textOnly("binary.dat")
            );

            assertNotNull(attachments);
            assertEquals(1, attachments.size());

            CuteAttachment att = attachments.get(0);
            assertFalse(att.isImage());
            assertTrue(att.getTextContent().contains("暂不支持内容直接解析"));
        }

        @Test
        @DisplayName("命中解码器成功解析文本内容")
        void matchedDecoderParsesContent(@TempDir Path tempDir) throws IOException {
            Path txtFile = tempDir.resolve("readme.txt");
            Files.writeString(txtFile, "ST-Cute Backend Testing", StandardCharsets.UTF_8);

            List<CuteAttachment> attachments = service.decodeToAttachments(
                    txtFile.toFile(), "txt", "text/plain",
                    DecodeParam.textOnly("readme.txt")
            );

            assertNotNull(attachments);
            assertEquals(1, attachments.size());

            CuteAttachment att = attachments.get(0);
            assertTrue(att.getTextContent().contains("ST-Cute Backend Testing"));
        }

        @Test
        @DisplayName("解码器抛出异常时容错兜底并返回失败说明附件")
        void decoderExceptionHandledGracefully(@TempDir Path tempDir) throws IOException {
            Path file = tempDir.resolve("corrupt.err");
            Files.writeString(file, "content");

            // 构造一个必定抛异常的故障测试解码器
            FileDecoder faultyDecoder = new FileDecoder() {
                @Override
                public boolean supports(String extension, String mimeType) {
                    return "err".equalsIgnoreCase(extension);
                }

                @Override
                public List<CuteAttachment> decodeToAttachments(File file, DecodeParam ctx) throws Exception {
                    throw new RuntimeException("文件校验和损坏");
                }
            };

            FileDecodeService faultTolerantService = new FileDecodeService(List.of(faultyDecoder));

            List<CuteAttachment> attachments = faultTolerantService.decodeToAttachments(
                    file.toFile(), "err", null, DecodeParam.textOnly("corrupt.err")
            );

            assertNotNull(attachments);
            assertEquals(1, attachments.size());

            CuteAttachment att = attachments.get(0);
            assertTrue(att.getTextContent().contains("解析失败"));
            assertTrue(att.getTextContent().contains("文件校验和损坏"));
        }
    }

    @Nested
    @DisplayName("truncateIfNecessary 文本超长截断保护测试")
    class TruncateTests {

        @Test
        @DisplayName("未超限文本原样返回")
        void underLimitReturnsOriginal() {
            String shortText = "hello world";
            assertEquals(shortText, service.truncateIfNecessary(shortText, 50));
        }

        @Test
        @DisplayName("超限文本截断并追加自描述提示")
        void overLimitTruncatedWithNotice() {
            String longText = "1234567890ABCDEFGHIJ";
            String truncated = service.truncateIfNecessary(longText, 10);

            assertTrue(truncated.startsWith("1234567890"));
            assertTrue(truncated.contains("已截断显示前 10 字符，实际总共 20 字符"));
        }
    }
}
