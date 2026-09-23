package com.stioc.cute.controller;

import com.stioc.cute.file.FileStorageService;
import com.stioc.cute.file.types.FileUploadVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FileController} 切片测试。
 * <p>
 * 重点：upload 参数透传、view 的 raw/thumbnail 分支、SVG 纵深防御（CSP + nosniff + 强制附件下载）、
 * 越权路径 404。文件流直读部分以真实临时文件驱动（resolveAttachmentFile 指向 @TempDir 内文件）。
 * </p>
 */
@WebMvcTest(controllers = FileController.class)
@Import(FileController.class)
class FileControllerTest extends AbstractControllerSliceTest {

    @MockitoBean
    private FileStorageService fileStorageService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void pinMode() {
        ensurePasswordlessMode();
    }

    @Test
    @DisplayName("upload：multipart 参数透传（compress 缺省 true）")
    void uploadDefaults() throws Exception {
        FileUploadVo vo = FileUploadVo.builder()
                .path("/files/1/a.png").name("a.png").size(3L)
                .mimeType("image/png").compressed(true).build();
        when(fileStorageService.uploadFile(eq(1L), any(), eq(true)))
                .thenReturn(vo);

        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png",
                "abc".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(localMultipart("/api/file/upload")
                        .file(file)
                        .param("cid", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("a.png"))
                .andExpect(jsonPath("$.data.compressed").value(true));
    }

    @Test
    @DisplayName("upload：compress=false 显式关闭压缩")
    void uploadWithoutCompression() throws Exception {
        when(fileStorageService.uploadFile(eq(1L), any(), eq(false)))
                .thenReturn(FileUploadVo.builder().name("b.png").build());

        MockMultipartFile file = new MockMultipartFile("file", "b.png", "image/png", new byte[]{1});
        mockMvc.perform(localMultipart("/api/file/upload")
                        .file(file)
                        .param("cid", "1")
                        .param("compress", "false"))
                .andExpect(status().isOk());

        verify(fileStorageService).uploadFile(eq(1L), any(), eq(false));
    }

    @Test
    @DisplayName("view：raw 模式回写文件流并带 inline disposition")
    void viewRawStreamsFileBytes() throws Exception {
        Path target = tempDir.resolve("note.txt");
        Files.writeString(target, "hello-st-cute");

        when(fileStorageService.resolveAttachmentFile(target.toAbsolutePath().toString()))
                .thenReturn(target.toFile());

        mockMvc.perform(localGet("/api/file/view")
                        .param("path", target.toAbsolutePath().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string("hello-st-cute"))
                .andExpect(header().string("Content-Disposition", containsString("inline")));
    }

    @Test
    @DisplayName("view：download=true 时强制 attachment")
    void viewDownloadForcesAttachment() throws Exception {
        Path target = tempDir.resolve("dl.txt");
        Files.writeString(target, "x");
        when(fileStorageService.resolveAttachmentFile(target.toAbsolutePath().toString()))
                .thenReturn(target.toFile());

        mockMvc.perform(localGet("/api/file/view")
                        .param("path", target.toAbsolutePath().toString())
                        .param("download", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")));
    }

    @Test
    @DisplayName("view：thumbnail 模式回写缩略图字节")
    void viewThumbnailWritesBytes() throws Exception {
        Path target = tempDir.resolve("img.jpg");
        Files.write(target, new byte[]{1, 2, 3});
        when(fileStorageService.resolveAttachmentFile(target.toAbsolutePath().toString()))
                .thenReturn(target.toFile());
        when(fileStorageService.getThumbnailBytes(target.toFile())).thenReturn(new byte[]{9, 9});

        mockMvc.perform(localGet("/api/file/view")
                        .param("path", target.toAbsolutePath().toString())
                        .param("mode", "thumbnail"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{9, 9}));
    }

    @Test
    @DisplayName("view：SVG 强制附件下载 + CSP 沙箱 + nosniff（内联脚本纵深防御）")
    void viewSvgAppliesInlineDefense() throws Exception {
        Path target = tempDir.resolve("evil.svg");
        Files.writeString(target, "<svg><script>alert(1)</script></svg>");
        when(fileStorageService.resolveAttachmentFile(target.toAbsolutePath().toString()))
                .thenReturn(target.toFile());

        mockMvc.perform(localGet("/api/file/view")
                        .param("path", target.toAbsolutePath().toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; sandbox"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("view：沙箱外/越权路径按 404 处理")
    void viewOutsideSandboxReturns404() throws Exception {
        when(fileStorageService.resolveAttachmentFile("C:/Windows/system32/x")).thenReturn(null);

        mockMvc.perform(localGet("/api/file/view")
                        .param("path", "C:/Windows/system32/x"))
                .andExpect(status().isNotFound());
    }
}
