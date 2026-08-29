package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolExecutionContext;
import com.stioc.cute.tool.access.ToolNames;
import com.stioc.cute.security.access.WorkspacePathResolver;
import com.stioc.cute.agent.access.AgentContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安全替换文件部分内容的本地核心修改工具
 */
@Slf4j
@Component
public class ModifyFileTool implements CuteTool {

    @Resource
    private WorkspacePathResolver workspacePathResolver;

    @Override
    public boolean isWriteTool() {
        return true;
    }

    @Override
    public String getName() {
        return ToolNames.REPLACE_FILE_CONTENT;
    }

    @Override
    public String getDescription() {
        return "【安全核心工具】精确替换指定文件的局部片段。替换必须在目标文件仅有一处唯一匹配时才生效。修改文件前，你必须在之前成功调用 read_file 读出最新内容，否则修改将被系统门禁物理拦截。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "目标文件路径，支持绝对路径或项目相对路径"
            },
            "oldContent": {
              "type": "string",
              "description": "文件中待被替换的现有原文片段（包含行首缩进与空格，必须在文件中唯一存在）。注意：是文件里当前已有的内容，不是新内容"
            },
            "newContent": {
              "type": "string",
              "description": "替换后最终写入文件的新文本。注意：替换完成后文件中呈现的就是这段内容"
            },
            "startLine": {
              "type": "integer",
              "description": "待替换代码段的起始行号 (1-indexed)，可选，配合 oldContent 进行精准范围锁定"
            },
            "endLine": {
              "type": "integer",
              "description": "待替换代码段的结束行号 (1-indexed)，可选，配合 oldContent 进行精准范围锁定"
            }
          },
          "required": ["path", "oldContent", "newContent"]
        }
        """;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        String pathVal = (String) arguments.get("path");
        String oldContent = (String) arguments.get("oldContent");
        String newContent = (String) arguments.get("newContent");

        if (pathVal == null || pathVal.isBlank()) {
            return new JSONObject().fluentPut("error", "参数 'path' 不能为空。").toJSONString();
        }
        if (oldContent == null || oldContent.isEmpty()) {
            return new JSONObject().fluentPut("error", "参数 'oldContent' 不能为空。").toJSONString();
        }
        if (newContent == null) {
            newContent = "";
        }

        try {
            File file = workspacePathResolver.resolvePath(pathVal, agentContext).toFile();

            if (!file.exists()) {
                return new JSONObject().fluentPut("error", "文件不存在: " + pathVal).toJSONString();
            }
            if (file.isDirectory()) {
                return new JSONObject().fluentPut("error", "目标路径是一个目录，无法执行代码修改: " + pathVal).toJSONString();
            }

            // 强制安全门禁：修改前必先读取最新内容以防止幻觉
            if (agentContext != null && !agentContext.getReadFiles().contains(file.getAbsolutePath())) {
                log.warn("ModifyFileTool 安全防御触发：未读先改拦截 - {}", file.getAbsolutePath());
                // 报错附带具体判定状态，帮助模型一次定位原因：
                // 1) 本会话从未读取过该文件；2) 读取记录被新一轮用户输入/清空/回退操作重置（每轮新输入会清空已读集合，防止基于过时上下文修改）
                String readFilesCount = agentContext.getReadFiles().isEmpty()
                        ? "当前会话本轮的已读文件集合为空（可能已被新一轮用户输入或清空/回退操作重置）"
                        : "当前会话本轮已读取过 " + agentContext.getReadFiles().size() + " 个其他文件，但不包含目标文件";
                return new JSONObject().fluentPut("error",
                        "拒绝执行代码修改。门禁判定规则：read_file 成功读取过的文件才允许修改，且每轮新用户消息会清空读取记录（防止基于过时上下文修改）。当前状态："
                                + readFilesCount + "。请先使用 read_file 读取目标文件 [" + file.getName() + "] 的最新内容，然后重试修改。"
                ).toJSONString();
            }

            String fileContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);

            // 空文件防御，引导使用 write_to_file
            if (fileContent.isEmpty()) {
                return new JSONObject().fluentPut("error", "文件内容为空，无法使用行号定位或局部内容替换。若要写入新内容，请直接使用 write_to_file。").toJSONString();
            }

            Integer startLine = null;
            Integer endLine = null;
            if (arguments.containsKey("startLine")) {
                startLine = ((Number) arguments.get("startLine")).intValue();
            }
            if (arguments.containsKey("endLine")) {
                endLine = ((Number) arguments.get("endLine")).intValue();
            }

            if ((startLine != null && endLine == null) || (startLine == null && endLine != null)) {
                return new JSONObject().fluentPut("error", "参数 'startLine' 和 'endLine' 必须同时指定或同时省略。").toJSONString();
            }

            String updatedContent;
            int matchStartOffset = -1;
            int matchEndOffset = -1;

            if (startLine != null && endLine != null) {
                int totalLines = countLines(fileContent);
                if (startLine < 1 || endLine < 1 || startLine > totalLines || endLine > totalLines || startLine > endLine) {
                    return new JSONObject().fluentPut("error",
                            "指定的行号范围 [" + startLine + ", " + endLine + "] 不合法。当前文件总行数为: " + totalLines
                    ).toJSONString();
                }

                // 包含结束行整行（含换行符）的偏移量
                int[] offsets = getLineRangeOffsets(fileContent, startLine, endLine);
                int startOffset = offsets[0];
                int endOffset = offsets[1];
                String rangeContent = fileContent.substring(startOffset, endOffset);

                // 在局部行范围内寻找子串匹配
                int subStart = -1;
                int subEnd = -1;

                // 1. 精确匹配子串
                int firstIdx = rangeContent.indexOf(oldContent);
                if (firstIdx != -1) {
                    int secondIdx = rangeContent.indexOf(oldContent, firstIdx + oldContent.length());
                    if (secondIdx == -1) {
                        subStart = firstIdx;
                        subEnd = firstIdx + oldContent.length();
                    } else {
                        return new JSONObject().fluentPut("error",
                                "在指定的行号范围 [" + startLine + ", " + endLine + "] 内找到了多处 (2 处或以上) 'oldContent' 的精确匹配。请缩窄行号范围或提供更多上下文以确保唯一性。"
                        ).toJSONString();
                    }
                } else {
                    // 2. 模糊匹配子串 Fallback
                    Pattern pattern = buildWhitespaceInsensitivePattern(oldContent);
                    Matcher matcher = pattern.matcher(rangeContent);
                    int matchCount = 0;
                    while (matcher.find()) {
                        matchCount++;
                        if (matchCount == 1) {
                            subStart = matcher.start();
                            subEnd = matcher.end();
                        }
                    }

                    if (matchCount > 1) {
                        return new JSONObject().fluentPut("error",
                                "在指定的行号范围 [" + startLine + ", " + endLine + "] 内找到了多处 (" + matchCount + " 处) 'oldContent' 的空白不敏感匹配。请缩窄行号范围或提供更多上下文以确保唯一性。"
                        ).toJSONString();
                    }
                }

                if (subStart == -1) {
                    // 防参数写反：oldContent 找不到，但 newContent 恰好能在文件中找到，大概率是两参数顺序颠倒了
                    if (!newContent.isEmpty() && fileContent.contains(newContent)) {
                        return new JSONObject().fluentPut("error",
                                "疑似参数顺序写反：oldContent（待替换原文）在指定范围内未找到，而 newContent 反而存在于文件中。请检查：oldContent 应为文件中现有内容，newContent 为替换后的新内容。"
                        ).toJSONString();
                    }
                    return new JSONObject().fluentPut("error",
                            "在指定的行号范围 [" + startLine + ", " + endLine + "] 内未找到 'oldContent' 的匹配。\n" +
                            "该范围内的实际内容为:\n" + rangeContent + "\n\n" +
                            "你期望的 'oldContent' 为:\n" + oldContent
                    ).toJSONString();
                }

                // 映射回全文偏移量
                matchStartOffset = startOffset + subStart;
                matchEndOffset = startOffset + subEnd;
                updatedContent = fileContent.substring(0, matchStartOffset) + newContent + fileContent.substring(matchEndOffset);
            } else {
                // 全局搜索匹配
                int count = 0;
                int idx = 0;
                while ((idx = fileContent.indexOf(oldContent, idx)) != -1) {
                    count++;
                    idx += oldContent.length();
                }

                if (count == 1) {
                    matchStartOffset = fileContent.indexOf(oldContent);
                    matchEndOffset = matchStartOffset + oldContent.length();
                    updatedContent = fileContent.substring(0, matchStartOffset) + newContent + fileContent.substring(matchEndOffset);
                } else if (count > 1) {
                    return new JSONObject().fluentPut("error",
                            "在文件 [" + file.getName() + "] 中找到了多处 (" + count + " 处) 'oldContent' 的精确匹配。为了安全起见，我们只能在唯一匹配时才能执行替换。请提供更多的上下文（如前后几行代码）或指定行号范围(startLine, endLine)来确保匹配的唯一性。"
                    ).toJSONString();
                } else {
                    // 全局模糊匹配 Fallback
                    Pattern pattern = buildWhitespaceInsensitivePattern(oldContent);
                    Matcher matcher = pattern.matcher(fileContent);
                    int matchCount = 0;
                    while (matcher.find()) {
                        matchCount++;
                        if (matchCount == 1) {
                            matchStartOffset = matcher.start();
                            matchEndOffset = matcher.end();
                        }
                    }

                    if (matchCount == 0) {
                        // 防参数写反：oldContent 找不到，但 newContent 恰好能在文件中找到，大概率是两参数顺序颠倒了
                        if (!newContent.isEmpty() && fileContent.contains(newContent)) {
                            return new JSONObject().fluentPut("error",
                                    "疑似参数顺序写反：oldContent（待替换原文）在文件中未找到，而 newContent 反而存在于文件中。请检查：oldContent 应为文件中现有内容，newContent 为替换后的新内容。"
                            ).toJSONString();
                        }
                        return new JSONObject().fluentPut("error",
                                "在文件 [" + file.getName() + "] 中未找到要替换的 'oldContent' 匹配片段（精确匹配与空白不敏感匹配均失败）。请检查空格、缩进或换行是否与文件实际内容一致。"
                        ).toJSONString();
                    } else if (matchCount > 1) {
                        return new JSONObject().fluentPut("error",
                                "在文件 [" + file.getName() + "] 中未找到 'oldContent' 的精确匹配，且找到了多处 (" + matchCount + " 处) 空白不敏感匹配。为了安全起见，只能在唯一匹配时执行替换。请提供更多的上下文（如前后几行代码）或指定行号范围(startLine, endLine)来确保匹配的唯一性。"
                        ).toJSONString();
                    } else {
                        updatedContent = fileContent.substring(0, matchStartOffset) + newContent + fileContent.substring(matchEndOffset);
                    }
                }
            }

            Files.writeString(file.toPath(), updatedContent, StandardCharsets.UTF_8);

            // 提取修改位置前后 3 行的上下文切片提供闭环反馈
            int endPos = matchStartOffset + newContent.length();
            String contextSnippet = getContextSnippet(updatedContent, matchStartOffset, endPos, 3);

            // 计算替换落点在最终文件中的行号范围 (1-indexed)，供调用方精确定位与校验
            int matchedStartLine = offsetToLineNumber(updatedContent, matchStartOffset);
            int matchedEndLine = offsetToLineNumber(updatedContent, endPos);

            log.info("ModifyFileTool 修改成功: {}", pathVal);
            return new JSONObject()
                    .fluentPut("success", true)
                    .fluentPut("message", "已成功修改文件 [" + file.getName() + "] 的指定片段。")
                    .fluentPut("matchedLines", new int[]{matchedStartLine, matchedEndLine})
                    .fluentPut("context", contextSnippet)
                    .toJSONString();

        } catch (IOException e) {
            log.error("ModifyFileTool 修改异常", e);
            return new JSONObject().fluentPut("error", "修改文件失败: " + e.getMessage()).toJSONString();
        }
    }

    /**
     * 将字符偏移量转换为行号 (1-indexed)，算法与 countLines 对齐。
     * offset 等于内容长度时返回最后一行行号。
     */
    private int offsetToLineNumber(String content, int offset) {
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
        return line;
    }

    /**
     * 流式统计文件中的总行数（算法对齐 BufferedReader.readLine）
     */
    private int countLines(String content) {
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
     * 计算并获取目标行号范围的字符级起始和截止偏移量（包含结束行的换行符）
     */
    private int[] getLineRangeOffsets(String content, int startLine, int endLine) {
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

        return new int[]{startOffset, endOffset};
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
    private String getContextSnippet(String content, int startPos, int endPos, int contextLines) {
        int len = content.length();

        // 向前回溯：找到 startPos 往前第 contextLines 条行边界
        int start = startPos;
        for (int lines = 0; lines < contextLines && start > 0; ) {
            // 向前扫描一个字符，遇到行结束符才计一行
            char c = content.charAt(start - 1);
            start--;
            if (c == '\n') {
                // CRLF：'\n' 前还有 '\r'，一起跳过，算作同一个行结束符
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
                // CRLF：'\r' 后还有 '\n'，一起推进，算作同一个行结束符
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
     * 将包含空白字符的待替换原文转换为具有空白折叠兼容性的正则表达式
     */
    private Pattern buildWhitespaceInsensitivePattern(String oldContent) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        int len = oldContent.length();
        while (i < len) {
            char c = oldContent.charAt(i);
            if (Character.isWhitespace(c)) {
                int start = i;
                boolean hasNewline = false;
                while (i < len && Character.isWhitespace(oldContent.charAt(i))) {
                    char ws = oldContent.charAt(i);
                    if (ws == '\r' || ws == '\n') {
                        hasNewline = true;
                    }
                    i++;
                }
                int end = i;

                boolean isLeading = (start == 0);
                boolean isTrailing = (end == len);

                if (!hasNewline) {
                    if (isLeading || isTrailing) {
                        regex.append("[ \\t]*");
                    } else {
                        regex.append("[ \\t]+");
                    }
                } else {
                    if (isLeading && isTrailing) {
                        regex.append("\\s*");
                    } else if (isLeading) {
                        regex.append("\\s*");
                    } else if (isTrailing) {
                        regex.append("[ \\t]*\\r?\\n");
                    } else {
                        regex.append("[ \\t]*\\r?\\n\\s*");
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
}
