package com.stioc.cute.tool.commandtool;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.runtime.loop.RuntimeContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * 命令行执行预检与熔断防线。
 * <p>
 * 职责收敛：
 * <ul>
 *   <li>Windows 下 PowerShell 写文件硬拦截（防止 UTF-8 BOM 或 GBK 污染代码库）</li>
 *   <li>同一命令在同一工作目录短时间内重复执行的熔断检测（防止模型陷入死循环）</li>
 *   <li>命令执行输出摘要与频次登记</li>
 *   <li>构建类命令空闲超时自动放宽判定</li>
 * </ul>
 * </p>
 */
@Slf4j
public final class CommandPrechecker {

    /**
     * 同一命令输出完全相同的容忍次数：连续第 N 次相同即判定为无效死循环重复。
     * 阈值放宽至 10 次：正常研发场景存在「命令执行成功但结果回传偶发丢失」时的合理重试验证，
     * 误杀有效重试的代价高于多放行几次的代价。
     */
    public static final int REPEAT_BREAK_THRESHOLD = 10;

    /**
     * 重复判定的时间窗口：超过该时长未重复执行，历史计数视为失效（毫秒）
     */
    public static final long REPEAT_WINDOW_MS = 3 * 60 * 1000L;

    /**
     * 构建类命令识别名单：首 token 命中且用户未显式指定 idleTimeoutMs 时，
     * 空闲超时自动放宽至 {@link #BUILD_IDLE_TIMEOUT_MS}（Maven/Gradle 等依赖解析静默期轻松超过 30 秒）。
     * 刻意排除 python/node 等宽泛入口：交互式脚本卡死仍需快速兜底
     */
    private static final Set<String> BUILD_COMMANDS = Set.of(
            "mvn", "mvnw", "gradle", "gradlew", "npm", "pnpm", "yarn", "bun",
            "pip", "pip3", "cargo", "docker", "dotnet", "make", "cmake");

    /**
     * 构建类命令的空闲超时基线（毫秒）
     */
    public static final long BUILD_IDLE_TIMEOUT_MS = 90_000L;

    /**
     * 普通命令的默认空闲超时基线（毫秒）
     */
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 30_000L;

    private CommandPrechecker() {
    }

    /**
     * 硬拦截检测：命令使用 PowerShell cmdlet 写入文件。
     * 匹配规则（忽略大小写）：命令含 powershell/pwsh 且含 Set-Content/Out-File/Add-Content。
     * 防呆不防恶：不检测 base64 绕过，目的是拦误用而非对抗。
     *
     * @return 拒绝理由文案；不命中返回 null
     */
    public static String checkPowerShellWrite(String command) {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return null;
        }
        String lower = command.toLowerCase();
        boolean isPowerShell = lower.contains("powershell") || lower.contains("pwsh ");
        if (!isPowerShell) {
            return null;
        }
        boolean hasWriteCmdlet = lower.contains("set-content") || lower.contains("out-file") || lower.contains("add-content");
        if (!hasWriteCmdlet) {
            return null;
        }
        log.warn("[PS写文件拦截] 命令使用 PowerShell cmdlet 写文件，已拒绝: {}", command);
        return "拒绝执行。检测到使用 PowerShell 写入文件（Set-Content/Out-File/Add-Content）。"
                + "Windows 下这些 cmdlet 极易产生编码问题（UTF-8 BOM 会导致 javac 报\"非法字符 \\ufeff\"，中文内容可能被写成 GBK 乱码）。"
                + "请改用 write_file（整文件写入）或 edit_file（局部替换）完成文件写入；"
                + "批量替换场景可分多次调用替换工具，或改用命令原生的重定向（>）配合 read_file 读取。";
    }

    /**
     * 同一命令无效重复执行检测（熔断保险丝）。
     * <p>
     * 判定条件（全部满足才拒绝）：同一指纹（命令 + 实际工作目录）+ 3 分钟时间窗口内 + 已连续 10 次产生完全相同的输出。
     * 输出一旦变化立即重置计数。检测到重复时返回拒绝理由文案，判定放行时返回 null。
     * 指纹纳入工作目录的原因：同一命令在不同仓库/目录下执行语义完全不同，仅凭命令文本判定会造成跨目录误杀。
     * </p>
     */
    public static String checkRepeatBreak(AgentContext agentContext, String repeatFingerprint) {
        if (agentContext == null) {
            return null;
        }
        RuntimeContext runtimeCtx = agentContext.extra(RuntimeContext.class);
        if (runtimeCtx == null) {
            return null;
        }
        RepeatCommandTracker tracker = runtimeCtx.getRecentCommands().computeIfAbsent(repeatFingerprint, k -> new RepeatCommandTracker());
        long now = System.currentTimeMillis();

        // 超出时间窗口视为全新序列，重置追踪器后放行
        if (now - tracker.getLastExecuteAt() > REPEAT_WINDOW_MS) {
            tracker.setLastOutputDigest(null);
            tracker.getSameOutputCount().set(0);
            tracker.setLastExecuteAt(now);
            return null;
        }

        // 上一次已登记的输出摘要存在且连续相同次数达到阈值时熔断拒绝
        if (tracker.getLastOutputDigest() != null && tracker.getSameOutputCount().get() >= REPEAT_BREAK_THRESHOLD) {
            log.warn("[重复熔断] 检测到命令 '{}' 在 {} 分钟内输出连续 {} 次完全相同，判定为无效重复调用，拒绝执行",
                    repeatFingerprint, REPEAT_WINDOW_MS / 60000, tracker.getSameOutputCount().get());
            return "拒绝执行。检测到该命令在最近 " + (REPEAT_WINDOW_MS / 60000) + " 分钟内已连续 "
                    + tracker.getSameOutputCount().get()
                    + " 次产生完全相同的输出，判定为无效重复调用（疑似死循环）。请更换命令、调整参数或基于已有输出继续分析，"
                    + "不要重复执行完全相同的命令。若你确实需要重跑，请显式修改命令内容（如添加参数）后再试。";
        }
        return null;
    }

    /**
     * 命令执行完成后登记输出摘要与次数，供下次预检判定
     */
    public static void recordCommandOutput(AgentContext agentContext, String repeatFingerprint, String output) {
        if (agentContext == null) {
            return;
        }
        RuntimeContext runtimeCtx = agentContext.extra(RuntimeContext.class);
        if (runtimeCtx == null) {
            return;
        }
        RepeatCommandTracker tracker = runtimeCtx.getRecentCommands().computeIfAbsent(repeatFingerprint, k -> new RepeatCommandTracker());
        String digest = RepeatCommandTracker.digestOf(output);
        if (digest.equals(tracker.getLastOutputDigest())) {
            tracker.getSameOutputCount().incrementAndGet();
        } else {
            tracker.setLastOutputDigest(digest);
            tracker.getSameOutputCount().set(1);
        }
        tracker.setLastExecuteAt(System.currentTimeMillis());
    }

    /**
     * 构建类命令识别：取命令首 token（剥掉 ./ .\\ 前缀与 .exe/.cmd/.bat 后缀）小写后匹配名单
     */
    public static boolean isBuildCommand(String command) {
        String trimmed = command.trim();
        for (String prefix : new String[]{"./", ".\\"}) {
            if (trimmed.startsWith(prefix)) {
                trimmed = trimmed.substring(prefix.length());
                break;
            }
        }
        int end = trimmed.indexOf(' ');
        String token = (end < 0 ? trimmed : trimmed.substring(0, end)).toLowerCase();
        for (String suffix : new String[]{".exe", ".cmd", ".bat"}) {
            if (token.endsWith(suffix)) {
                token = token.substring(0, token.length() - suffix.length());
                break;
            }
        }
        return BUILD_COMMANDS.contains(token);
    }

    /**
     * 计算空闲超时（毫秒）
     *
     * @param command             待执行命令
     * @param explicitTimeoutVal  显式传入的空闲超时参数
     * @return 实际生效的空闲超时毫秒数
     */
    public static long resolveIdleTimeout(String command, Long explicitTimeoutVal) {
        if (explicitTimeoutVal != null) {
            return explicitTimeoutVal;
        }
        if (isBuildCommand(command)) {
            log.debug("execute_command 构建类命令识别命中，空闲超时自动放宽至 {}ms", BUILD_IDLE_TIMEOUT_MS);
            return BUILD_IDLE_TIMEOUT_MS;
        }
        return DEFAULT_IDLE_TIMEOUT_MS;
    }
}
