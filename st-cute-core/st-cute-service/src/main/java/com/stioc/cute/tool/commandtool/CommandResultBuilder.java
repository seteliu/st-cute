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
     * 输出压缩时保留的开头字符数
     */
    public static final int OUTPUT_HEAD_KEEP = 32_000;

    /**
     * 输出压缩时保留的结尾字符数
     */
    public static final int OUTPUT_TAIL_KEEP = 32_000;

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
        if (output == null || output.length() <= OUTPUT_DISPLAY_LIMIT) {
            return output;
        }
        String head = output.substring(0, OUTPUT_HEAD_KEEP);
        String tail = output.substring(output.length() - OUTPUT_TAIL_KEEP);
        int omitted = output.length() - OUTPUT_HEAD_KEEP - OUTPUT_TAIL_KEEP;
        return head + "\n... [输出过长已压缩：共 " + output.length() + " 字符，中间省略 " + omitted
                + " 字符。如需中段内容可重定向到文件后用 read_file 分段读取] ...\n" + tail;
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
