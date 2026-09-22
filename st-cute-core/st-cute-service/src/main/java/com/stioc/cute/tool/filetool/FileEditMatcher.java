package com.stioc.cute.tool.filetool;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
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
     * @param normalizedOld       按文件主导风格归一化后的 oldContent
     * @param altVariantOld       互补 EOL 变体形态的 oldContent
     * @param newContent          替换后的新内容（用于防参数颠倒预检）
     * @param wholeFileContent    完整文件内容（用于防参数颠倒预检）
     * @param scopeDesc           作用域描述文案（如 "指定的行号范围 [1, 10]" 或 "文件 [App.java]"）
     * @param isRange             是否为行号范围局部检索
     * @return 匹配定位结果
     */
    public static MatchLocateResult locateMatch(String searchTarget,
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
                    + buildRangeMismatchEcho(searchTarget));
        }

        return MatchLocateResult.error("在" + scopeDesc + "中未找到要替换的 'oldContent' 匹配片段（精确匹配与空白不敏感匹配均失败）。请检查空格、缩进或换行是否与文件实际内容一致。");
    }

    /**
     * 构造范围分支未命中时的窗口回显（带回显加帽）。
     * <p>
     * 窗口行数超过 10 行时不再整窗倒出（宽窗口一次失败可灌数百行进上下文），
     * 改为提示窗口规模并建议缩窄范围或重新 read_file；10 行以内维持原全量对照，
     * 保留小窗口下"一眼看出差异"的调试价值。
     * 刻意不回显期望 oldContent：它是模型自己刚提交的参数、就在其上下文里，
     * 回显零信息量纯浪费 token（历史版本曾因此把 58 行 oldContent 倾倒回错误信息）。
     * </p>
     */
    private static String buildRangeMismatchEcho(String searchTarget) {
        int windowLines = countLines(searchTarget);
        if (windowLines > 10) {
            return "该范围共 " + windowLines + " 行，内容过长不再全量回显。"
                    + "建议：重新 read_file 定位目标片段后缩窄行号范围重试，或省略行号范围（oldContent 唯一命中即可）。";
        }
        return "该范围内的实际内容为:\n" + searchTarget;
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
                        // 首个换行前的水平空白：仅非行首的空白段需要（行首空白已由上方 [ \t]* 覆盖）
                        if (n == 0 && !isLeading) {
                            regex.append("[ \\t]*");
                        }
                        // 换行后的水平缩进：仅当该换行之后仍有待匹配内容时才追加。
                        // 若空白段位于 oldContent 末尾（isTrailing），此处的 [ \t]* 会贪婪吞掉
                        // 目标文本中「下一行」的前导缩进并纳入替换区间，导致下一行缩进被静默删除
                        // （在 Python/YAML 等缩进敏感文件中会改变语义），故末尾换行后不再追加
                        boolean lastNewline = (n == newlineCount - 1);
                        regex.append(lastNewline && isTrailing ? "\\r?\\n" : "\\r?\\n[ \\t]*");
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
     * 在全文中定位与指定片段相同内容的全部出现位置（换算为 1-indexed 行号），排除本次已替换的区间。
     * <p>
     * 供"窗口外孪生提示"使用：行号范围分支命中后，检查文件其余部分是否还有相同片段，
     * 有则向模型附注行号，防止窗口漂移导致的静默改错。达到 {@code maxResults} 即提前停搜
     * （高频短片段如 "}" 只需知道"还有多处"，无需穷举完整清单）。
     * </p>
     *
     * @param content            完整文件内容（替换前）
     * @param snippet            本次替换的真实落点文本（按文件字节检索，非 oldContent 字面）
     * @param maxResults         最多列举的行号数，达到即停
     * @param replacedStart      本次被替换区间的起始偏移（含），该区间内的命中视为本次修改自身
     * @param replacedEnd        本次被替换区间的结束偏移（不含）
     * @return 窗口外孪生片段所在行号列表（升序）；无孪生时返回空列表
     */
    public static List<Integer> locateAllLineNumbers(String content, String snippet, int maxResults,
                                                     int replacedStart, int replacedEnd) {
        List<Integer> lines = new ArrayList<>();
        if (content == null || snippet == null || snippet.isEmpty() || maxResults <= 0) {
            return lines;
        }
        int idx = 0;
        while ((idx = content.indexOf(snippet, idx)) != -1 && lines.size() < maxResults) {
            int end = idx + snippet.length();
            // 跳过与本次替换区间重叠的命中（那是被替换的内容本身，不是孪生）
            if (end <= replacedStart || idx >= replacedEnd) {
                lines.add(offsetToLineNumber(content, idx));
            }
            idx = end;
        }
        return lines;
    }

    /**
     * 截取修改位置前后若干行的上下文文本片段（带回显加帽，对外默认入口）。
     * <p>
     * newContent 行数超过 4 行时，对改动区回显加帽省 token：头 2 行 + 省略提示 + 尾 2 行
     * （完整内容模型刚亲手写过，落点行号由 matchedLines 提供，全量回显属重复税）。
     * 4 行以内（含）退化为 {@link #getContextSnippet(String, int, int, int)} 全量回显，
     * 小改动保留完整闭环反馈。
     * </p>
     *
     * @param content        修改后的完整文件内容
     * @param startPos       替换片段在 content 中的起始偏移（含）
     * @param endPos         替换片段在 content 中的结束偏移（不含）
     * @param contextLines   前后各保留的行数
     * @param newContent     本次替换写入的新内容（用于行数判定与省略行数计算）
     */
    public static String getContextSnippetCapped(String content, int startPos, int endPos, int contextLines, String newContent) {
        int newLineCount = countLines(stripTrailingNewline(newContent));
        if (newLineCount > 4) {
            // 前 contextLines 行 + newContent 头 2 行 + 省略中间 (newLineCount - 4) 行 + newContent 尾 2 行 + 后 contextLines 行
            int headStart = backtrackLineStart(content, startPos, contextLines);
            String head = content.substring(headStart, skipLines(content, startPos, 2));
            int tailStart = backtrackLines(content, endPos, 2);
            String tail = content.substring(tailStart, endPos);
            String after = content.substring(endPos, skipLines(content, endPos, contextLines));
            return head + "... [newContent 头 2 行与尾 2 行之间省略 " + (newLineCount - 4) + " 行] ...\n" + tail + after;
        }
        return getContextSnippet(content, startPos, endPos, contextLines);
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

    /**
     * 从指定偏移向前回溯 N 个行边界，返回该 N 行起始处的前一个字符偏移（含换行符本身）。
     * <p>供回显加帽拼接使用：head 片段需完整携带所跨行的换行符，保证与后续片段无缝衔接。</p>
     */
    private static int backtrackLineStart(String content, int pos, int lines) {
        int start = pos;
        for (int n = 0; n < lines && start > 0; ) {
            char c = content.charAt(start - 1);
            start--;
            if (c == '\n') {
                if (start > 0 && content.charAt(start - 1) == '\r') {
                    start--;
                }
                n++;
            } else if (c == '\r') {
                n++;
            }
        }
        return start;
    }

    /**
     * 从指定偏移向前回溯 N 个行边界，返回 N 行中第一行的起始偏移（不含换行符）。
     * <p>供回显加帽的 tail 片段使用：取 newContent 尾部 N 行的文本本体。</p>
     */
    private static int backtrackLines(String content, int pos, int lines) {
        int start = backtrackLineStart(content, pos, lines);
        // 越过所跨行的换行符，落在第一行内容起点
        while (start < pos && (content.charAt(start) == '\r' || content.charAt(start) == '\n')) {
            start++;
        }
        return start;
    }

    /**
     * 从指定偏移向后推进 N 个行边界，返回推进后的偏移（含第 N 行的换行符）。
     * <p>供回显加帽的 head/after 片段使用：推进量不足 N 行时停在文件末尾。</p>
     */
    private static int skipLines(String content, int pos, int lines) {
        int end = pos;
        int len = content.length();
        for (int n = 0; n < lines && end < len; ) {
            char c = content.charAt(end);
            end++;
            if (c == '\r') {
                if (end < len && content.charAt(end) == '\n') {
                    end++;
                }
                n++;
            } else if (c == '\n') {
                n++;
            }
        }
        return end;
    }

    /**
     * 去除字符串末尾的换行符（\r\n / \n / \r），供行数统计对齐展示口径（末尾空行不计）。
     */
    private static String stripTrailingNewline(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }
}
