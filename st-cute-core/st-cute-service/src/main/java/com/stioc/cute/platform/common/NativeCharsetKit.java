package com.stioc.cute.platform.common;

import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.io.InputStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 系统原生编码探测工具集。
 * <p>
 * Windows 环境下子进程输出与历史文件的编码不归一（UTF-8 与系统 ANSI 码页混排并存），
 * 此处提供统一的探测策略：UTF-8 严格模式试解码优先，失败回退系统原生编码。
 * </p>
 */
@Slf4j
public final class NativeCharsetKit {

    private NativeCharsetKit() {
    }

    /**
     * 获取操作系统原生的 ANSI Codepage 编码。
     * <p>
     * 优先读取 {@code sun.jnu.encoding}（Windows 上对应系统区域设置，中文 → GBK、英文 → windows-1252 等），
     * 它反映的是操作系统区域设置的真实编码，不受 JVM 启动参数 {@code -Dfile.encoding} 的覆盖影响。
     * 读取失败时降级为 {@link Charset#defaultCharset()}。
     * </p>
     */
    public static Charset getSystemNativeCharset() {
        String jnuEncoding = System.getProperty("sun.jnu.encoding");
        if (jnuEncoding != null && !jnuEncoding.isBlank()) {
            try {
                return Charset.forName(jnuEncoding);
            } catch (Exception e) {
                log.warn("[编码探测] sun.jnu.encoding 值 '{}' 无法识别，降级为 JVM 默认编码", jnuEncoding);
            }
        }
        return Charset.defaultCharset();
    }

    /**
     * 采样截断容忍窗（字节）：UTF-8 多字节序列最长 4 字节（中文 3 字节、emoji 4 字节）。
     * <p>
     * 调用方按固定长度采样（如 ReadFileTool 采样文件头部 8KB）时，样本末尾可能恰好
     * 切在多字节序列中间，产生残缺的序列尾巴。容忍窗用于区分"采样截断伪影"与"真编码证据"：
     * 仅当非法序列的起点落在样本末尾 4 字节内、且残缺序列一直延伸到样本末尾时，
     * 才视为截断伪影并触发重试。
     * </p>
     */
    private static final int TRUNCATION_TOLERANCE_BYTES = 4;

    /**
     * 用 UTF-8 严格模式解码探测字节，成功返回 UTF-8，失败回退 Windows 系统级编码。
     * <p>
     * 采样截断容忍重试：严格校验失败后，若非法序列恰好延伸到样本末尾（采样把多字节
     * 序列切断所致的伪影，而非真实编码证据），则截掉残缺尾部后重新校验一次；
     * 重试通过判定为 UTF-8，仍失败才回退系统原生编码。
     * 典型修复场景：ReadFileTool 采样 8KB 头部切断中文字符（如 e6 b8 b8 只剩 e6 b8），
     * 原逻辑误回退 GBK 导致后续整文件 UTF-8 解码抛 {@code Input length = 1} 读取失败。
     * </p>
     *
     * @param data 字节样本数组
     * @param len  有效字节长度
     * @return 判定出的字符集
     */
    public static Charset detectCharset(byte[] data, int len) {
        Charset fallback = getSystemNativeCharset();
        if (len == 0) {
            return fallback;
        }
        // 判定用解码：输出缓冲区按最坏情况（每字节 1 字符）分配，避免输出区不足提前中断校验
        CharsetDecoder decoder = newStrictUtf8Decoder();
        ByteBuffer input = ByteBuffer.wrap(data, 0, len);
        CoderResult result = decoder.decode(input, CharBuffer.allocate(len), true);
        if (!result.isError()) {
            return StandardCharsets.UTF_8;
        }
        // 失败点定位：输入缓冲区位置指向非法序列起点，length() 为残缺序列长度
        int errorPosition = input.position();
        int malformedLength = result.length();
        // 截断伪影判定：非法序列起点在容忍窗内，且残缺序列一直延伸到样本末尾（无后续有效字节）
        if (errorPosition > 0 && malformedLength > 0
                && len - errorPosition <= TRUNCATION_TOLERANCE_BYTES
                && errorPosition + malformedLength >= len) {
            // 截断容忍重试：截掉残缺尾部后再做一次严格校验，通过则样本主体确为合法 UTF-8
            CharsetDecoder tailDecoder = newStrictUtf8Decoder();
            CoderResult retry = tailDecoder.decode(
                    ByteBuffer.wrap(data, 0, errorPosition), CharBuffer.allocate(errorPosition), true);
            if (!retry.isError()) {
                return StandardCharsets.UTF_8;
            }
        }
        return fallback;
    }

    /**
     * 构造严格模式的 UTF-8 解码器（非法/不可映射输入直接报错而非替换）
     */
    private static CharsetDecoder newStrictUtf8Decoder() {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    /**
     * 文件级编码探测的采样长度：仅读文件头部 8KB 采样判定，避免大文件整读的内存压力
     */
    private static final int FILE_CHARSET_SAMPLE_SIZE = 8 * 1024;

    /**
     * 文本换行符风格枚举（按 8KB 采样内主导形态判定）。
     * 混合风格文件按数量多者归化，原有内容区域一个字节不动，主导风格仅用于注入内容格式对齐。
     */
    public enum EolStyle {
        /** Windows 风格 \r\n */
        CRLF,
        /** Unix 风格 \n */
        LF,
        /** 采样中无任何换行符（单行文件或空文件），处理时按 LF 对待 */
        NONE;

        /**
         * 基于原始字节样本判定主导换行风格。
         * 统计 CRLF 与孤立 LF 数量（孤立 CR 为经典 Mac 风格，极罕见，不参与计数），
         * CRLF 数量占优判定为 CRLF 文件，否则为 LF 文件；样本内无换行符返回 NONE。
         *
         * @param sample 字节样本
         * @param len    有效字节长度
         * @return 主导换行风格
         */
        public static EolStyle fromSample(byte[] sample, int len) {
            int crlf = 0;
            int lf = 0;
            for (int i = 0; i < len; i++) {
                if (sample[i] == '\r') {
                    if (i + 1 < len && sample[i + 1] == '\n') {
                        crlf++;
                        i++;
                    }
                } else if (sample[i] == '\n') {
                    lf++;
                }
            }
            if (crlf + lf == 0) {
                return NONE;
            }
            return crlf >= lf ? CRLF : LF;
        }
    }

    /**
     * 文件文本元数据快照：编码、UTF-8 BOM、UTF-16 BOM、换行符风格。
     * 一次 8KB 采样同时判定，供读取侧展示与修改写回侧保真共用，
     * 保证两侧对同一文件的判定严格一致（写回不漂移的前提）。
     *
     * @param charset    判定出的字符集（utf16Bom 为 true 时仅为标注值，调用方须先检查 utf16Bom 并拒绝）
     * @param hasUtf8Bom 文件头含 UTF-8 BOM（EF BB BF）
     * @param utf16Bom   文件头含 UTF-16 BOM（FF FE / FE FF），此类文件不支持编辑
     * @param eolStyle   主导换行符风格
     */
    public record FileTextMeta(
            Charset charset,
            boolean hasUtf8Bom,
            boolean utf16Bom,
            EolStyle eolStyle) {
    }

    /**
     * 采样探测指定文件的文本元数据（编码 + BOM + 换行符风格一次判定）。
     * <p>
     * BOM 检测前置：UTF-16 BOM 直接标记返回（字节含 \x00，常规探测必失败，须调用方显式拒绝）；
     * UTF-8 BOM 确定性锁定 UTF-8（绕过采样截断容忍重试）；无 BOM 走 {@link #detectCharset} 常规判定。
     * 空文件或读取失败时降级为系统原生编码、无 BOM、NONE 换行风格。
     * </p>
     *
     * @param path 目标文件路径
     * @return 文件文本元数据
     */
    public static FileTextMeta detectFileMeta(Path path) {
        byte[] sample = new byte[FILE_CHARSET_SAMPLE_SIZE];
        int read;
        try (InputStream in = Files.newInputStream(path)) {
            read = in.read(sample);
        } catch (Exception e) {
            log.warn("文件文本元数据探测读取失败，降级系统原生编码: {}, 异常: {}", path, e.getMessage());
            return new FileTextMeta(getSystemNativeCharset(), false, false, EolStyle.NONE);
        }
        if (read <= 0) {
            // 空文件无字节可判，直接走系统原生编码兜底
            return new FileTextMeta(getSystemNativeCharset(), false, false, EolStyle.NONE);
        }

        // UTF-16 BOM 检测前置（FF FE = LE，FE FF = BE）：此类文件被调用方显式拒绝，charset 仅为标注
        if (read >= 2 && (sample[0] & 0xFF) == 0xFF && (sample[1] & 0xFF) == 0xFE) {
            return new FileTextMeta(StandardCharsets.UTF_16, false, true, EolStyle.NONE);
        }
        if (read >= 2 && (sample[0] & 0xFF) == 0xFE && (sample[1] & 0xFF) == 0xFF) {
            return new FileTextMeta(StandardCharsets.UTF_16, false, true, EolStyle.NONE);
        }

        // UTF-8 BOM（EF BB BF）：确定性锁定 UTF-8，无需采样校验
        boolean hasUtf8Bom = read >= 3 && (sample[0] & 0xFF) == 0xEF
                && (sample[1] & 0xFF) == 0xBB && (sample[2] & 0xFF) == 0xBF;
        Charset charset = hasUtf8Bom ? StandardCharsets.UTF_8 : detectCharset(sample, read);
        return new FileTextMeta(charset, hasUtf8Bom, false, EolStyle.fromSample(sample, read));
    }

    /**
     * 采样探测指定文件的字符集：仅读取文件头部 {@link #FILE_CHARSET_SAMPLE_SIZE} 字节交给
     * {@link #detectCharset} 判定（UTF-8 严格校验优先，失败回退系统原生编码），空文件或读取失败时
     * 降级为系统原生编码。供 ReadFileTool 与 ModifyFileTool 共用，保证「读取侧」与「修改写回侧」
     * 对同一文件的编码判定严格一致——两侧判定一致是写回编码不漂移的前提。
     *
     * @param path 目标文件路径
     * @return 判定出的字符集
     */
    public static Charset detectFileCharset(Path path) {
        return detectFileMeta(path).charset();
    }

    /**
     * 将模型传入的内容按目标文件主导换行风格归一转换。
     * 先做防重复归一（\r\n 与孤立 \r 统一为 \n，防止模型已传 CRLF 时二次转换产生 \r\r\n），
     * 再按 CRLF 文件转为 \r\n（LF/NONE 保持 \n）。供 modify 注入与 write 覆写共用。
     *
     * @param content 待归一的模型侧文本内容
     * @param style   目标文件主导换行风格
     * @return 按目标风格归一后的内容
     */
    public static String normalizeEolToStyle(String content, EolStyle style) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        return style == EolStyle.CRLF ? normalized.replace("\n", "\r\n") : normalized;
    }
}
