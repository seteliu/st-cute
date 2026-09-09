package com.stioc.cute.platform.common;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;

/**
 * 行级混合编码字符流读取器。
 * <p>
 * Windows 下子进程输出常见 UTF-8 与系统原生编码（如 GBK）混流：同一命令输出里一部分行是 UTF-8
 * （如 git 提交说明、mvn 自身输出），另一部分行是 GBK（如 git 本地化提示、javac 编译诊断），
 * 整流单编码探测必然顾此失彼。此读取器按行（以 \n 分界）独立做编码判定：
 * 每行先做 UTF-8 严格校验，失败回退系统原生编码，判定策略与 NativeCharsetKit#detectCharset
 * 完全同源，仅粒度细化为行，混合流两侧均可正确还原。
 * </p>
 * <p>
 * 超长无换行的行（如压缩成单行的 JSON）按 {@link #OVERSIZE_SEGMENT_BYTES} 字节分片独立判定，
 * 防止单行字节缓冲无限膨胀。
 * </p>
 */
public class LineMixedCharsetReader extends Reader {

    /**
     * 超长行的分片阈值（字节）：连续无换行符的字节流按此大小切片独立解码，防单行缓冲膨胀
     */
    private static final int OVERSIZE_SEGMENT_BYTES = 64 * 1024;

    /**
     * 底层原始字节流（已加读缓冲，行内逐字节扫描换行符不再逐次触发系统调用）
     */
    private final InputStream in;

    /**
     * 当前段的字节缓冲区（一段 = 一个完整行或一个超长分片）
     */
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    /**
     * 已解码、待上层消费的字符队列
     */
    private final StringBuilder pending = new StringBuilder();

    /**
     * 底层流是否已读尽
     */
    private boolean eof = false;

    public LineMixedCharsetReader(InputStream in) {
        // 外包一层读缓冲：本读取器按单字节扫描换行符，无缓冲时每次读取都是一次系统调用
        this.in = new BufferedInputStream(in, OVERSIZE_SEGMENT_BYTES);
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (off < 0 || off > cbuf.length || off + len < 0 || off + len > cbuf.length) {
            throw new IndexOutOfBoundsException("读取区间越界: off=" + off + ", len=" + len + ", cbuf.length=" + cbuf.length);
        }
        // 待消费字符耗尽且底层流未读尽时，继续读取下一段（完整行或超长分片）并解码补充
        while (pending.length() == 0 && !eof) {
            readAndDecodeSegment();
        }
        if (pending.length() == 0) {
            return -1;
        }
        int n = Math.min(len, pending.length());
        pending.getChars(0, n, cbuf, off);
        pending.delete(0, n);
        return n;
    }

    @Override
    public boolean ready() {
        return pending.length() > 0;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /**
     * 从底层流读取一个完整段（至换行符、流末尾或超长分片阈值），按行级编码策略解码后放入待消费队列
     */
    private void readAndDecodeSegment() throws IOException {
        buffer.reset();
        while (true) {
            int b = in.read();
            if (b == -1) {
                eof = true;
                break;
            }
            buffer.write(b);
            if (b == '\n' || buffer.size() >= OVERSIZE_SEGMENT_BYTES) {
                break;
            }
        }
        if (buffer.size() == 0) {
            return;
        }
        byte[] bytes = buffer.toByteArray();
        // 行级编码判定：UTF-8 严格校验优先，失败回退系统原生编码（与整体探测策略同源）
        Charset charset = NativeCharsetKit.detectCharset(bytes, bytes.length);
        pending.append(new String(bytes, charset));
    }
}
