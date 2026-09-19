package com.stioc.cute.tool.filetool;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件局部代码替换匹配定位器。
 * <p>
 * 职责收敛：
 * <ul>
 *   <li>三阶段递进匹配算法（精确匹配 → CRLF/LF 换行变体精确匹配 → 空白不敏感模糊匹配）；</li>
 *   <li>消除行号范围局部搜索与全文件搜索之间的重复逻辑（DRY）；</li>
 *   <li>具有空白折叠兼容性的正则表达式构建；</li>
 *   <li>基于字符偏移量的行号统计与上下文切片截取。</li>
 * </ul>
 * </p>
 */
@Slf4j
public final class FileEditMatcher {

    private FileEditMatcher() {
    }

    /**
     * 在目标文本中执行三阶段递进定位匹配。
     *
     * @param searchTarget        检索目标文本（局部行文本或整文件文本）
     * @param originalExpectedOld 模型传入的原始 oldContent
     * @param normalizedOld       按文件主导风格归一化后的 oldContent
     * @param altVariantOld       互补 EOL 变体形态的 oldContent
     * @param newContent          替换后的新内容（用于防参数颠倒预检）
     * @param wholeFileContent    完整文件内容（用于防参数颠倒预检）
     * @param scopeDesc           作用域描述文案（如 "指定的行号范围 [1, 10]" 或 "文件 [App.java]"）
     * @param isRange             是否为行号范围局部检索
     * @return 匹配定位结果
     */
    public static MatchLocateResult locateMatch(String searchTarget, String originalExpectedOld,
                                                String normalizedOld, String altVariantOld,
                                                String newContent, String wholeFileContent,
                                                String scopeDesc, boolean isRange) {
        // 1. 第一阶段：精确匹配（优先使用与文件 EOL 归一化一致的 normalizedOld）
        int count = 0;
        int firstIdx = -1;
        int idx = 0;
        while ((idx = searchTarget.indexOf(normalizedOld, idx)) != -1) {
            count++;
            if (count == 1) {
                firstIdx = idx;
            }
            idx += normalizedOld.length();
        }

        if (count == 1) {
            return MatchLocateResult.success(firstIdx, firstIdx + normalizedOld.length());
        }
        if (count > 1) {
            String tip = isRange
                    ? "请缩窄行号范围或提供更多上下文以确保唯一性。"
                    : "为了安全起见，我们只能在唯一匹配时才能执行替换。请提供更多的上下文（如前后几行代码）或指定行号范围(startLine, endLine)来确保匹配的唯一性。";
            return MatchLocateResult.error("在" + scopeDesc + "内找到了多处 (" + count + " 处) 'oldContent' 的精确匹配。" + tip);
        }

        // 2. 第二阶段：EOL 变体精确重试（若原样或与主风格一致形态未命中，尝试互补变体精确匹配）
        if (!altVariantOld.equals(normalizedOld)) {
            int vCount = 0;
            int vFirstIdx = -1;
            int vIdx = 0;
            while ((vIdx = searchTarget.indexOf(altVariantOld, vIdx)) != -1) {
                vCount++;
                if (vCount == 1) {
                    vFirstIdx = vIdx;
                }
                vIdx += altVariantOld.length();
            }

            if (vCount == 1) {
                return MatchLocateResult.success(vFirstIdx, vFirstIdx + altVariantOld.length());
            }
            if (vCount > 1) {
                String tip = isRange
                        ? "请缩窄行号范围或提供更多上下文以确保唯一性。"
                        : "为了安全起见，我们只能在唯一匹配时才能执行替换。请提供更多的上下文（如前后几行代码）或指定行号范围(startLine, endLine)来确保匹配的唯一性。";
                return MatchLocateResult.error("在" + scopeDesc + "内找到了多处 (" + vCount + " 处) 'oldContent' 的精确匹配（EOL 换行变体）。" + tip);
            }
        }

        // 3. 第三阶段：空白不敏感模糊匹配 Fallback（保持换行结构不变，容忍水平缩进差异）
        Pattern pattern = buildWhitespaceInsensitivePattern(normalizedOld);
        Matcher matcher = pattern.matcher(searchTarget);
        int matchCount = 0;
        int subStart = -1;
        int subEnd = -1;
        while (matcher.find()) {
            matchCount++;
            if (matchCount == 1) {
                subStart = matcher.start();
                subEnd = matcher.end();
            }
        }

        if (matchCount == 1) {
            return MatchLocateResult.success(subStart, subEnd);
        }
        if (matchCount > 1) {
            String tip = isRange
                    ? "请缩窄行号范围或提供更多上下文以确保唯一性。"
                    : "为了安全起见，只能在唯一匹配时执行替换。请提供更多的上下文（如前后几行代码）或指定行号范围(startLine, endLine)来确保匹配的唯一性。";
            return MatchLocateResult.error("在" + scopeDesc + "内找到了多处 (" + matchCount + " 处) 'oldContent' 的空白不敏感匹配。" + tip);
        }

        // 4. 未命中防御：检查是否属于 oldContent 与 newContent 参数颠倒
        if (!newContent.isEmpty() && wholeFileContent.contains(newContent)) {
            return MatchLocateResult.error("疑似参数顺序写反：oldContent（待替换原文）在"
                    + (isRange ? "指定范围内" : "文件") + "未找到，而 newContent 反而存在于文件中。请检查：oldContent 应为文件中现有内容，newContent 为替换后的新内容。");
        }

        if (isRange) {
            return MatchLocateResult.error("在" + scopeDesc + "内未找到 'oldContent' 的匹配。\n"
                    + "该范围内的实际内容为:\n" + searchTarget + "\n\n"
                    + "你期望的 'oldContent' 为:\n" + originalExpectedOld);
        }

        return MatchLocateResult.error("在" + scopeDesc + "中未找到要替换的 'oldContent' 匹配片段（精确匹配与空白不敏感匹配均失败）。请检查空格、缩进或换行是否与文件实际内容一致。");
    }

    /**
     * 计算并获取目标行号范围的字符级起始和截止偏移量（包含结束行的换行符）。
     */
    public static LineRangeOffsets getLineRangeOffsets(String content, int startLine, int endLine) {
        int len = content.length();
        int currentLine = 1;
        int pos = 0;
        int startOffset = 0;

        // 寻找起始偏移量
        while (pos < len && currentLine < startLine) {
            char c = content.charAt(pos);
            if (c == '\r') {
                if (pos + 1 < len && content.charAt(pos + 1) == '\n') {
                    pos++;
                }
                currentLine++;
            } else if (c == '\n') {
                currentLine++;
            }
            pos++;
        }
        startOffset = pos;

        // 寻找截止偏移量（包含目标结束行及其行结束符）
        while (pos < len && currentLine <= endLine) {
            char c = content.charAt(pos);
            if (c == '\r' || c == '\n') {
                if (c == '\r' && pos + 1 < len && content.charAt(pos + 1) == '\n') {
                    pos++;
                }
                currentLine++;
            }
            pos++;
        }
        int endOffset = pos;

        return new LineRangeOffsets(startOffset, endOffset);
    }

    /**
     * 将包含空白字符的待替换原文转换为具有空白折叠兼容性的正则表达式
     */
    public static Pattern buildWhitespaceInsensitivePattern(String oldContent) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        int len = oldContent.length();
        while (i < len) {
            char c = oldContent.charAt(i);
            if (Character.isWhitespace(c)) {
                int start = i;
                int newlineCount = 0;
                while (i < len && Character.isWhitespace(oldContent.charAt(i))) {
                    char ws = oldContent.charAt(i);
                    if (ws == '\n') {
                        newlineCount++;
                    } else if (ws == '\r') {
                        if (i + 1 < len && oldContent.charAt(i + 1) == '\n') {
                            i++;
                        }
                        newlineCount++;
                    }
                    i++;
                }
                int end = i;

                boolean isLeading = (start == 0);
                boolean isTrailing = (end == len);

                if (newlineCount == 0) {
                    // 纯行内水平空白（空格/Tab）：容忍缩进长度差异
                    if (isLeading || isTrailing) {
                        regex.append("[ \\t]*");
                    } else {
                        regex.append("[ \\t]+");
                    }
                } else {
                    // 包含跨行空白：保持换行结构不变，只容忍换行前后每行行内的水平缩进，
                    // 消除相邻换行符之间重复拼接 [ \t]* 带来的量词重叠与回溯放大风险
                    if (isLeading) {
                        regex.append("[ \\t]*");
                    }
                    for (int n = 0; n < newlineCount; n++) {
                        if (n == 0 && !isLeading) {
                            regex.append("[ \\t]*");
                        }
                        regex.append("\\r?\\n[ \\t]*");
                    }
                }
            } else {
                StringBuilder token = new StringBuilder();
                while (i < len && !Character.isWhitespace(oldContent.charAt(i))) {
                    token.append(oldContent.charAt(i));
                    i++;
                }
                regex.append(Pattern.quote(token.toString()));
            }
        }
        return Pattern.compile(regex.toString());
    }

    /**
     * 将字符偏移量转换为行号 (1-indexed)，算法与 countLines 对齐。
     * offset 等于内容长度时返回最后一行行号，收敛至总行数上限。
     */
    public static int offsetToLineNumber(String content, int offset) {
        if (content == null || content.isEmpty()) {
            return 1;
        }
        int totalLines = countLines(content);
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\r') {
                line++;
                if (i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
            } else if (c == '\n') {
                line++;
            }
        }
        return Math.min(line, Math.max(1, totalLines));
    }

    /**
     * 流式统计文件中的总行数（算法对齐 BufferedReader.readLine）
     */
    public static int countLines(String content) {
        if (content.isEmpty()) {
            return 0;
        }
        int count = 0;
        int pos = 0;
        int len = content.length();
        while (pos < len) {
            char c = content.charAt(pos);
            if (c == '\r') {
                if (pos + 1 < len && content.charAt(pos + 1) == '\n') {
                    pos++;
                }
                count++;
            } else if (c == '\n') {
                count++;
            }
            pos++;
        }
        char lastChar = content.charAt(len - 1);
        if (lastChar != '\r' && lastChar != '\n') {
            count++;
        }
        return count;
    }

    /**
     * 截取修改位置前后若干行的上下文文本片段。
     *
     * <p>向前回溯时先遇到 '\n'，再检查其前一位是否为 '\r'（CRLF 整体跳过），
     * 向后推进时先遇到 '\r'，再检查其后一位是否为 '\n'（同理）。</p>
     *
     * @param content      修改后的完整文件内容
     * @param startPos     替换片段在 content 中的起始偏移（含）
     * @param endPos       替换片段在 content 中的结束偏移（不含）
     * @param contextLines 前后各保留的行数
     */
    public static String getContextSnippet(String content, int startPos, int endPos, int contextLines) {
        int len = content.length();

        // 向前回溯：找到 startPos 往前第 contextLines 条行边界
        int start = startPos;
        for (int lines = 0; lines < contextLines && start > 0; ) {
            char c = content.charAt(start - 1);
            start--;
            if (c == '\n') {
                if (start > 0 && content.charAt(start - 1) == '\r') {
                    start--;
                }
                lines++;
            } else if (c == '\r') {
                lines++;
            }
        }

        // 向后推进：找到 endPos 往后第 contextLines 条行边界
        int end = endPos;
        for (int lines = 0; lines < contextLines && end < len; ) {
            char c = content.charAt(end);
            end++;
            if (c == '\r') {
                if (end < len && content.charAt(end) == '\n') {
                    end++;
                }
                lines++;
            } else if (c == '\n') {
                lines++;
            }
        }

        return content.substring(start, end);
    }
}
