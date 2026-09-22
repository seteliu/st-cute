package com.stioc.cute.tool.commandtool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.event.AgentEventFactory;
import com.stioc.cute.engine.loop.core.AgentContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 命令结果封装与输出展示压缩器。
 * <p>
 * 职责收敛：
 * <ul>
 *   <li>超长输出压缩（头尾保留 + 中间省略摘要）</li>
 *   <li>失败误判模式提示段附加（针对管道过滤与增量编译缓存错误）</li>
 *   <li>统一 JSON 返回体契约装配</li>
 *   <li>控制台流式增量事件推送</li>
 * </ul>
 * </p>
 */
@Slf4j
public final class CommandResultBuilder {

    /**
     * 输出保护上限（字符）：超过判定为失控刷屏，强制中止进程，防内存无限膨胀。
     * 正常超限输出不再截断中止，改为返回时头尾保留压缩（见 {@link #compactOutputIfNeeded}）
     */
    public static final int OUTPUT_PROTECT_LIMIT = 2_000_000;

    /**
     * 单次返回给模型的输出展示上限（字符）：超过则压缩为头尾保留 + 中间省略摘要
     */
    public static final int OUTPUT_DISPLAY_LIMIT = 100_000;

    /**
     * 单次返回给模型的输出行数上限：超过则压缩为头尾保留 + 中间省略摘要。
     * <p>
     * 与字符上限互补：字符闸门挡不住「换行密集但总量未超」的输出（如 5 万行短日志仅几十万字符，
     * 未触发字符压缩却足以淹没上下文的行号坐标感）。行数闸门以更贴近"可阅读量"的维度兜底。
     * </p>
     */
    public static final int OUTPUT_DISPLAY_LINE_LIMIT = 10_000;

    /**
     * 输出压缩时保留的开头字符数
     */
    public static final int OUTPUT_HEAD_KEEP = 32_000;

    /**
     * 输出压缩时保留的结尾字符数
     */
    public static final int OUTPUT_TAIL_KEEP = 32_000;

    /**
     * 输出压缩时保留的开头行数（行数超限场景）
     */
    public static final int OUTPUT_HEAD_LINE_KEEP = 5_000;

    /**
     * 输出压缩时保留的结尾行数（行数超限场景）
     */
    public static final int OUTPUT_TAIL_LINE_KEEP = 5_000;

    private CommandResultBuilder() {
    }

    /**
     * 统一构建命令执行结果 JSON（exitCode/timeout/idleTimeout/cwd/output 五字段契约收口）。
     *
     * @param exitCode 进程退出码（异常兜底用 -1）
     * @param timeout  是否因超时被强制中止
     * @param cwd      实际生效的工作目录
     * @param output   终端输出（或面向模型的系统提示文案）
     */
    public static String buildCommandResult(int exitCode, boolean timeout, String cwd, String output) {
        return buildCommandResult(exitCode, timeout, cwd, output, false);
    }

    /**
     * 统一构建命令执行结果 JSON（含空闲超时细分标记的重载）
     */
    public static String buildCommandResult(int exitCode, boolean timeout, String cwd, String output, boolean idleTimeout) {
        JSONObject result = new JSONObject()
                .fluentPut("exitCode", exitCode)
                .fluentPut("timeout", timeout)
                .fluentPut("cwd", cwd)
                .fluentPut("output", output);
        if (timeout) {
            result.fluentPut("idleTimeout", idleTimeout);
        }
        return result.toJSONString();
    }

    /**
     * 超长输出压缩：超过展示上限时保留头尾各 {@value #OUTPUT_HEAD_KEEP} 字符，
     * 中间替换为省略说明（含总长度与省略长度），让模型既能看到开头（命令与错误模式）
     * 也能看到结尾（结论与退出码上下文），中间重复内容不占上下文
     */
    public static String compactOutputIfNeeded(String output) {
        if (output == null) {
            return null;
        }
        int originalLines = countLines(output);
        // 先按行数闸门压缩（更贴近"可阅读量"的维度），再按字符闸门兜底
        String lineCompacted = compactByLineLimit(output);
        if (lineCompacted.length() <= OUTPUT_DISPLAY_LIMIT) {
            return lineCompacted;
        }
        String head = lineCompacted.substring(0, OUTPUT_HEAD_KEEP);
        String tail = lineCompacted.substring(lineCompacted.length() - OUTPUT_TAIL_KEEP);
        // 摘要以「原始输出的行数与字符数」为口径：两处压缩可能叠加，
        // 若只报当前字符数，行数信息会被行数闸门写入的中段省略提示一并切掉，模型无从判断输出规模。
        // 省略量不单列：行压缩后的省略字符数与原始总量对不上账（两数并排易被误读），
        // 由「原始总量 - 首尾保留量」即可推断省略规模
        return head + "\n... [输出过长已压缩：原输出共 " + originalLines + " 行、" + output.length()
                + " 字符，本次保留首尾各 " + OUTPUT_HEAD_KEEP + " 字符。"
                + "如需完整内容可重定向到文件后用 read_file 分段读取] ...\n" + tail;
    }

    /**
     * 按展示口径统计行数：末尾换行符视为最后一行的终止符、不额外计行。
     * <p>与 read_file 的计数口径保持一致（同一份内容在不同工具中标注的行数应相同）。</p>
     */
    private static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        // 末尾无换行符时，最后一行尚未计入
        return text.charAt(text.length() - 1) == '\n' ? lines : lines + 1;
    }

    /**
     * 行数超限压缩：超过 {@link #OUTPUT_DISPLAY_LINE_LIMIT} 行时保留头尾各
     * {@link #OUTPUT_HEAD_LINE_KEEP} / {@link #OUTPUT_TAIL_LINE_KEEP} 行，中间以省略摘要替代。
     * <p>
     * 未超限时原样返回，不做任何复制。单遍扫描定位边界，不按行切分为数组
     * （输出上限 200 万字符，切分可能产生百万级数组元素，徒增内存峰值）。
     * </p>
     */
    private static String compactByLineLimit(String output) {
        // 第一遍：统计总行数（口径与展示一致——末尾换行符属最后一行的终止符，不额外计行）
        int totalLines = countLines(output);
        if (totalLines <= OUTPUT_DISPLAY_LINE_LIMIT) {
            return output;
        }

        // 第二遍：定位头部保留边界（前 HEAD_LINE_KEEP 行的最后一行行尾换行符之后）
        int headEnd = headEndOffset(output, OUTPUT_HEAD_LINE_KEEP);

        // 第三遍：定位尾部保留边界（倒数第 TAIL_LINE_KEEP 行的行首偏移）
        int tailStart = tailStartOffset(output, OUTPUT_TAIL_LINE_KEEP, headEnd);

        // 保留区间重叠说明文件总行数不足两段之和（理论上不会发生，防线兜底）
        if (tailStart <= headEnd) {
            return output;
        }

        int omittedLines = totalLines - OUTPUT_HEAD_LINE_KEEP - OUTPUT_TAIL_LINE_KEEP;
        return output.substring(0, headEnd)
                + "... [输出行数过多已压缩：共 " + totalLines + " 行，中间省略 " + omittedLines
                + " 行。如需完整输出可重定向到文件后用 read_file 分段读取] ...\n"
                + output.substring(tailStart);
    }

    /**
     * 计算前 N 行内容结束后的偏移（即第 N 行行尾换行符之后的位置）。
     * <p>不足 N 行时返回内容长度。</p>
     */
    private static int headEndOffset(String output, int lines) {
        int counted = 0;
        for (int i = 0; i < output.length(); i++) {
            if (output.charAt(i) == '\n') {
                counted++;
                if (counted == lines) {
                    return i + 1;
                }
            }
        }
        return output.length();
    }

    /**
     * 计算最后 N 行的起始偏移（即倒数第 N 行的行首）。
     * <p>
     * 末尾换行符先跳过——它只是最后一行的终止符，不构成新行；随后从后向前数满
     * N 个换行符，其后即为倒数第 N 行的行首。不足 N 行时返回下界 {@code lowerBound}。
     * </p>
     */
    private static int tailStartOffset(String output, int lines, int lowerBound) {
        int scanEnd = output.length();
        if (scanEnd > lowerBound && output.charAt(scanEnd - 1) == '\n') {
            scanEnd--;
        }
        int newlines = 0;
        for (int i = scanEnd - 1; i >= lowerBound; i--) {
            if (output.charAt(i) == '\n') {
                newlines++;
                if (newlines == lines) {
                    return i + 1;
                }
            }
        }
        return lowerBound;
    }

    /**
     * 失败场景附加已知误判模式提示段（方案3/5）。
     * 仅在 exitCode != 0 时调用，提示内容只在真的失败时出现，不占常态上下文
     *
     * @param output    命令原始输出
     * @param bashEntry 本次实际生效的 shell 是否为 bash 系入口
     */
    public static String appendFailureHints(String output, boolean bashEntry) {
        String hints = "";
        String trimmed = output != null ? output.trim() : "";

        // 模式1：管道末端命令无匹配导致退出码非 0 且无/极少输出。
        // 前置命令可能并未失败，模型易误判为命令失败而反复重试
        // （示例命令按实际生效的 shell 入口选取：bash 入口给 grep，cmd 入口给 findstr）
        if (trimmed.isEmpty() || trimmed.length() < 50) {
            String filterExample = bashEntry ? "grep \"关键字\"" : "findstr \"关键字\"";
            hints += "\n\n[提示] 命令退出码非 0 且几乎无输出。若命令使用了管道（如 mvn xxx | " + filterExample + "），"
                    + "末端过滤命令无匹配时退出码即为 1，这并不代表前置命令失败。"
                    + "建议：去掉管道过滤直接执行查看完整输出，或将输出重定向到文件（command > out.txt）后用 read_file 查看。";
        }

        // 模式2：增量编译缓存过期误判（git mv/文件移动后未改动代码却报"找不到符号"）
        boolean hasSymbolError = trimmed.contains("找不到符号") || trimmed.contains("cannot find symbol");
        boolean hasPackageError = trimmed.contains("不存在") || trimmed.contains("does not exist");
        if (hasSymbolError && hasPackageError) {
            hints += "\n\n[提示] 若此前刚执行过 git mv / 文件移动，或本次未修改任何代码却出现此编译错误，"
                    + "很可能是增量编译缓存过期。建议先执行 mvn clean compile 重试，再判定为代码问题。";
        }

        return hints.isEmpty() ? output : output + hints;
    }

    /**
     * 发送控制台增量日志事件
     *
     * @param messageId    工具消息 ID（日志流按消息 ID 归属；为 null 时不推送）
     * @param text         增量日志文本
     * @param agentContext 会话上下文
     */
    public static void sendIncrementalLog(Long messageId, String text, AgentContext agentContext) {
        if (messageId != null && agentContext != null) {
            try {
                agentContext.publishEvent(AgentEventFactory.createToolLogStream(agentContext, messageId, text));
            } catch (Exception e) {
                log.error("发送控制台流式日志出错", e);
            }
        }
    }
}
