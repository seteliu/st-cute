package com.stioc.cute.platform.common;

import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

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
     * 用 UTF-8 严格模式解码探测字节，成功返回 UTF-8，失败回退 Windows 系统级编码。
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
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            decoder.decode(ByteBuffer.wrap(data, 0, len));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            return fallback;
        }
    }
}
