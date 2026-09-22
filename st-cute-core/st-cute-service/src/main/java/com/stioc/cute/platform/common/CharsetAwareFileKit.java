package com.stioc.cute.platform.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 规约与配置文件的编码感知统一读取工具。
 * <p>
 * Windows 环境下 AGENTS.md、rules/*.md、hooks.json、mcp_servers.json、SKILL.md 等附属规约文件的
 * 编码不归一（UTF-8 与系统 ANSI 码页如 GBK 混排并存），此前各装载器直接用 UTF-8 强制解码，
 * 本地 ANSI 编码的文件会静默乱码注入提示词或导致 JSON/YAML 解析失败。
 * 本工具统一收口规则与配置文件的读取入口：走 {@link NativeCharsetKit} 的 8KB 采样探测
 * （UTF-8 严格校验优先，失败回退系统原生编码），并额外处理 UTF-8 BOM 剥离与 UTF-16 拒读。
 * </p>
 */
public final class CharsetAwareFileKit {

    private CharsetAwareFileKit() {
    }

    /**
     * 编码感知地读取指定文本文件的完整内容。
     * <p>
     * 编码判定与 ReadFileTool / ModifyFileTool 等工具链完全同源，保证同一文件在
     * 「智能体工具读取侧」与「规约装载侧」的解码结果严格一致。
     * </p>
     *
     * @param path 目标文件路径
     * @return 解码后的完整文本内容（UTF-8 BOM 已剥离）
     * @throws IOException 文件不存在或 IO 读取异常；文件为不支持的 UTF-16 编码时抛非受检的 IllegalStateException
     */
    public static String readString(Path path) throws IOException {
        // 单次采样同时取得编码与 UTF-16 BOM 标记：原先分别调用 detectFileMeta 与 detectFileCharset，
        // 而 detectFileCharset 内部就是 detectFileMeta 的一行委托，导致同一文件被重复 open、
        // 重复做 8KB 采样解码（每个文件 3 次 IO 降为 2 次：1 次采样 + 1 次全量读取）
        NativeCharsetKit.FileTextMeta meta = NativeCharsetKit.detectFileMeta(path);
        // UTF-16 BOM 文件无法安全采样判定（字节含 \x00），直接抛出带明确指引的异常，由调用方按既有容错路径处理
        if (meta.utf16Bom()) {
            throw new IllegalStateException(
                    "不支持 UTF-16 编码的规约配置文件（含 UTF-16 BOM），请转存为 UTF-8 或系统 ANSI 编码后重试: " + path);
        }
        String content = Files.readString(path, meta.charset());
        // UTF-8 BOM 剥离：防止 BOM 字符残留为正文首个不可见字符，干扰 frontmatter 分隔符与 JSON/YAML 解析
        if (content != null && !content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        return content;
    }
}
