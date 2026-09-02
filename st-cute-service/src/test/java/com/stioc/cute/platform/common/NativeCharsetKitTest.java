package com.stioc.cute.platform.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * NativeCharsetKit 编码探测单元测试。
 * <p>
 * 重点覆盖采样截断容忍重试逻辑：UTF-8 文件按固定长度采样时，
 * 样本末尾切断多字节中文字符序列的伪影不应导致误回退 GBK。
 * </p>
 */
class NativeCharsetKitTest {

    /**
     * 构造全 ASCII 内容（任何采样长度下都合法的 UTF-8）
     */
    private byte[] asciiBytes(int len) {
        byte[] data = new byte[len];
        Arrays.fill(data, (byte) 'a');
        return data;
    }

    @Test
    void testPureAsciiDetectUtf8() {
        // 纯 ASCII 是 UTF-8 子集，应判定为 UTF-8
        Charset detected = NativeCharsetKit.detectCharset(asciiBytes(100), 100);
        assertEquals(StandardCharsets.UTF_8, detected);
    }

    @Test
    void testValidUtf8ChineseDetectUtf8() {
        // 完整合法的 UTF-8 中文文本
        byte[] data = "需求拆解全流程编排：将原始需求文档系统化拆解".repeat(5).getBytes(StandardCharsets.UTF_8);
        Charset detected = NativeCharsetKit.detectCharset(data, data.length);
        assertEquals(StandardCharsets.UTF_8, detected);
    }

    @Test
    void testTruncatedUtf8SampleStillDetectsUtf8() {
        // 模拟 ReadFileTool 8KB 采样截断：高密度中文在 8192 字节处切断多字节序列
        String text = "这是一段高密度中文内容，用于测试采样边界切断多字节字符的场景。".repeat(400);
        byte[] full = text.getBytes(StandardCharsets.UTF_8);
        byte[] sample = Arrays.copyOf(full, 8192);
        Charset detected = NativeCharsetKit.detectCharset(sample, 8192);
        assertEquals(StandardCharsets.UTF_8, detected);
    }

    @Test
    void testTruncatedUtf8SampleAt1ByteBoundary() {
        // 切在 3 字节中文序列的第 1 字节处：只剩孤立头字节（如 e4）
        String text = "x".repeat(8191) + "中";
        byte[] full = text.getBytes(StandardCharsets.UTF_8);
        byte[] sample = Arrays.copyOf(full, 8192);
        // sample 末尾只剩 "中"(e4 b8 ad) 的头字节 e4 —— 截断伪影
        Charset detected = NativeCharsetKit.detectCharset(sample, 8192);
        assertEquals(StandardCharsets.UTF_8, detected);
    }

    @Test
    void testTruncatedUtf8SampleAt2ByteBoundary() {
        // 切在 3 字节中文序列的第 2 字节处：头字节 + 1 个后续字节（如 e4 b8）
        String text = "x".repeat(8190) + "中中";
        byte[] full = text.getBytes(StandardCharsets.UTF_8);
        byte[] sample = Arrays.copyOf(full, 8192);
        // sample 末尾 = 第一个 "中"(e4 b8 ad) 的前 2 字节 e4 b8 —— 截断伪影
        Charset detected = NativeCharsetKit.detectCharset(sample, 8192);
        assertEquals(StandardCharsets.UTF_8, detected);
    }

    @Test
    void testGbkSampleNotMisjudgedAsTruncation() {
        // 真 GBK 内容：UTF-8 严格校验在第一个中文字符（byte 0）即失败，
        // 失败点不在末尾容忍窗 → 不触发截断重试，正确回退系统原生编码
        byte[] gbkBytes = "这是 GBK 中文编码的文件内容测试，用于验证回退逻辑。".repeat(3)
                .getBytes(Charset.forName("GBK"));
        Charset detected = NativeCharsetKit.detectCharset(gbkBytes, gbkBytes.length);
        assertEquals(NativeCharsetKit.getSystemNativeCharset(), detected);
    }

    @Test
    void testMixedGarbageInMiddleFallsBackToNative() {
        // 样本中间的非法序列（0xFF 在 UTF-8 中永不合法）：不是截断伪影，应回退原生编码
        byte[] data = asciiBytes(8192);
        data[4000] = (byte) 0xFF;
        Charset detected = NativeCharsetKit.detectCharset(data, data.length);
        assertEquals(NativeCharsetKit.getSystemNativeCharset(), detected);
    }

    @Test
    void testEmptySampleFallsBackToNative() {
        // 空样本：直接回退系统原生编码
        Charset detected = NativeCharsetKit.detectCharset(new byte[0], 0);
        assertEquals(NativeCharsetKit.getSystemNativeCharset(), detected);
    }
}
