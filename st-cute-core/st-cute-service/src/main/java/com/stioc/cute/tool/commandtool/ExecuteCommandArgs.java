package com.stioc.cute.tool.commandtool;

import com.stioc.cute.engine.tool.types.ToolArgs;

import java.util.Map;

/**
 * 命令执行工具输入参数强类型绑定对象
 */
public record ExecuteCommandArgs(
        String command,
        String cwd,
        String shell,
        String encoding,
        Long rawIdleTimeoutMs,
        long maxTimeoutMs,
        boolean runInBackground
) {
    public static final long DEFAULT_MAX_TIMEOUT_MS = 600_000L;
    public static final long MIN_TIMEOUT_MS = 1_000L;
    public static final String DEFAULT_ENCODING = "auto";

    public static ExecuteCommandArgs from(Map<String, Object> arguments) {
        ToolArgs args = ToolArgs.of(arguments);
        String command = args.getString("command");
        String cwd = args.getStringTrimmed("cwd");
        String shell = args.getStringTrimmed("shell");
        String encoding = args.getStringTrimmed("encoding", DEFAULT_ENCODING);
        Long rawIdleTimeoutMs = args.getLong("idleTimeoutMs");
        if (rawIdleTimeoutMs != null && rawIdleTimeoutMs <= 0) {
            // 非正数空闲超时非法，回退为 null 走预检基准值自动决策
            rawIdleTimeoutMs = null;
        } else if (rawIdleTimeoutMs != null) {
            // 下界钳制（至少 1000ms），防止模型传入 0 或极小值导致命令启动即被秒杀
            rawIdleTimeoutMs = Math.max(MIN_TIMEOUT_MS, rawIdleTimeoutMs);
        }
        long rawMaxTimeout = args.getLong("maxTimeoutMs", DEFAULT_MAX_TIMEOUT_MS);
        long maxTimeoutMs = rawMaxTimeout <= 0 ? DEFAULT_MAX_TIMEOUT_MS : Math.max(MIN_TIMEOUT_MS, rawMaxTimeout);
        boolean runInBackground = Boolean.TRUE.equals(args.getBoolean("runInBackground"));

        return new ExecuteCommandArgs(command, cwd, shell, encoding, rawIdleTimeoutMs, maxTimeoutMs, runInBackground);
    }

    public boolean isAutoEncoding() {
        return DEFAULT_ENCODING.equalsIgnoreCase(encoding);
    }
}
