package com.stioc.cute.tool.commandtool;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.types.ToolResult;
import com.stioc.cute.platform.common.LineMixedCharsetReader;
import com.stioc.cute.platform.common.NativeCharsetKit;
import com.stioc.cute.platform.common.VirtualThreads;
import com.stioc.cute.runtime.loop.RuntimeContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 命令执行控制流处理器（前台同步等待 vs 后台异步守候）。
 * <p>
 * 职责收敛：
 * <ul>
 *   <li>前台命令执行：输出流异步缓冲、双超时 Watchdog 监控、进程强杀与超时排空</li>
 *   <li>后台挂起任务：异步日志流持续推送、3.0 秒初始化观察短轮询、存活与失败诊断</li>
 *   <li>跨平台流式编码自动探测与混排还原</li>
 * </ul>
 * </p>
 */
@Slf4j
public final class CommandExecutionHandler {

    /**
     * 进程退出后等待输出流读尽的兜底上限（毫秒）：
     * 正常退出时管道随进程关闭秒级返回；超时强杀分支存在孤儿孙进程持有管道写端的窗口，
     * 无限期等待曾实证挂死 25 分钟，故设硬上限（8 秒）放弃残余输出
     */
    public static final long OUTPUT_DRAIN_TIMEOUT_MS = 8_000L;

    /**
     * 当前操作系统是否为 Windows 平台静态缓存，避免每次命令执行重复查询系统属性
     */
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private CommandExecutionHandler() {
    }

    /**
     * 执行后台持久服务命令。
     */
    public static String executeBackground(Process process, String toolCallId, AgentContext agentContext,
                                           String finalCommand, File dir, Charset forcedCharset) {
        boolean needDetect = IS_WINDOWS && forcedCharset == null;
        StringBuffer backgroundOutput = new StringBuffer();

        VirtualThreads.run("cmd-bg-log-" + (toolCallId != null ? toolCallId : "anon"), () -> {
            log.info("[异步后台日志线程启动] toolCallId={}, Command={}", toolCallId, finalCommand);
            try (BufferedReader reader = needDetect
                    ? createEncodingAwareReader(process.getInputStream())
                    : new BufferedReader(new InputStreamReader(process.getInputStream(),
                            forcedCharset != null ? forcedCharset : StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    CommandResultBuilder.sendIncrementalLog(toolCallId, line + "\n", agentContext);
                    log.info("[后台控制台输出 - {}] {}", toolCallId, line);

                    // 保留前期一部分报错日志提供给主线程失败返回
                    if (backgroundOutput.length() < 20000) {
                        backgroundOutput.append(line).append("\n");
                    }
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                if (e instanceof IOException && msg != null &&
                        (msg.contains("closed") || msg.contains("管道已结束") || msg.contains("Pipe"))) {
                    log.info("[异步后台日志线程] 管道流已正常断开或关闭: toolCallId={}", toolCallId);
                } else {
                    log.warn("[异步后台日志线程读取中断 - {}] 读取标准输出异常: {}", toolCallId, e.getMessage());
                }
            } finally {
                // 级联判断当前进程树中是否有后代孙子进程（例如node.exe）依然处于存活运行状态
                boolean anyChildAlive = false;
                if (toolCallId != null && agentContext != null) {
                    RuntimeContext bgRuntimeCtx = agentContext.extra(RuntimeContext.class);
                    ActiveProcess active = bgRuntimeCtx != null ? bgRuntimeCtx.getActiveProcesses().get(toolCallId) : null;
                    if (active != null && active.getChildPids() != null) {
                        for (Long childPid : active.getChildPids()) {
                            if (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)) {
                                anyChildAlive = true;
                                break;
                            }
                        }
                    }
                }

                boolean isAlive = process.isAlive() || anyChildAlive;

                if (isAlive) {
                    log.info("[异步后台日志线程结束 - {}] 流读取关闭，但相关物理进程树仍在后台活跃运行, PID={}", toolCallId, process.pid());
                } else {
                    log.info("[异步后台日志线程结束 - {}] 流读取关闭且物理进程树已完全退出, PID={}, ExitValue={}",
                            toolCallId, process.pid(), process.isAlive() ? 0 : process.exitValue());
                    if (toolCallId != null && agentContext != null) {
                        RuntimeContext bgRuntimeCtx = agentContext.extra(RuntimeContext.class);
                        if (bgRuntimeCtx != null) {
                            bgRuntimeCtx.getActiveProcesses().remove(toolCallId);
                        }
                    }
                }
            }
        });

        // 主线程进行至多 3.0 秒的“初始化安全观察窗口”（采用 200ms 短轮询）：
        try {
            for (int i = 0; i < 15; i++) {
                Thread.sleep(200);
                if (!process.isAlive()) {
                    break;
                }
            }
        } catch (InterruptedException ignored) {
        }

        boolean isAlive = process.isAlive();
        int exitVal = isAlive ? 0 : process.exitValue();

        RuntimeContext checkRuntimeCtx = agentContext != null ? agentContext.extra(RuntimeContext.class) : null;
        boolean successfullyStarted = isAlive || exitVal == 0;

        if (!successfullyStarted) {
            log.warn("[后台进程启动失败] 进程在拉起后 3s 内异常退出, toolCallId={}, exitValue={}", toolCallId, exitVal);
            if (toolCallId != null && checkRuntimeCtx != null) {
                checkRuntimeCtx.getActiveProcesses().remove(toolCallId);
            }
            return CommandResultBuilder.buildCommandResult(exitVal, false, dir.getAbsolutePath(),
                    "[错误] 后台服务启动失败，进程在初始化时退出！\n\n终端异常输出如下：\n" + backgroundOutput.toString());
        }

        if (isAlive) {
            return CommandResultBuilder.buildCommandResult(0, false, dir.getAbsolutePath(),
                    "[系统提示] 持久后台服务已成功在后台拉起并运行，您可以继续执行其他操作。");
        }

        return CommandResultBuilder.buildCommandResult(0, false, dir.getAbsolutePath(),
                "[系统提示] 命令已在后台执行完成并正常退出 (exitCode=0)。执行输出如下：\n"
                        + (backgroundOutput.isEmpty() ? "（无输出）" : backgroundOutput.toString()));
    }

    /**
     * 执行前台同步命令。
     */
    public static String executeForeground(Process process, String toolCallId, AgentContext agentContext,
                                           String finalCommand, File dir, Charset forcedCharset,
                                           long idleTimeoutMs, long maxTimeoutMs, boolean bashEntry,
                                           String repeatFingerprint) {
        boolean lineLevelDetect = IS_WINDOWS && forcedCharset == null;

        AtomicLong lastOutputTime = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean idleTimeoutFlag = new AtomicBoolean(false);
        AtomicBoolean maxTimeoutFlag = new AtomicBoolean(false);

        // 异步读取 stdout，通过 CompletableFuture 将结果安全传回主线程
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = lineLevelDetect
                    ? new BufferedReader(new LineMixedCharsetReader(process.getInputStream()))
                    : new BufferedReader(new InputStreamReader(process.getInputStream(),
                            forcedCharset != null ? forcedCharset : StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastOutputTime.set(System.currentTimeMillis());
                    sb.append(line).append("\n");
                    CommandResultBuilder.sendIncrementalLog(toolCallId, line + "\n", agentContext);
                    if (sb.length() > CommandResultBuilder.OUTPUT_PROTECT_LIMIT) {
                        log.warn("execute_command 输出超过保护上限 {} 字符，判定失控刷屏，级联强杀整个进程树", CommandResultBuilder.OUTPUT_PROTECT_LIMIT);
                        sb.append("\n... [输出已超过保护上限 ").append(CommandResultBuilder.OUTPUT_PROTECT_LIMIT)
                                .append(" 字符，判定为失控刷屏（疑似死循环），已强制中止进程] ...\n");
                        ProcessTracker.cascadeKillProcessTree(agentContext, toolCallId, process);
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
            return sb.toString();
        }, task -> VirtualThreads.run("cmd-stdout-" + (toolCallId != null ? toolCallId : "anon"), task));

        // Watchdog：每 500ms 检查一次空闲时长和总运行时长
        Thread watchdog = VirtualThreads.run("cmd-watchdog-" + (toolCallId != null ? toolCallId : "anon"), () -> {
            long startTime = System.currentTimeMillis();
            while (process.isAlive()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                long now = System.currentTimeMillis();
                long idle = now - lastOutputTime.get();
                long total = now - startTime;
                if (idle >= idleTimeoutMs) {
                    log.warn("execute_command 空闲超时触发 ({}ms 无新输出)，级联强杀整个进程树: {}", idle, process.pid());
                    idleTimeoutFlag.set(true);
                    ProcessTracker.cascadeKillProcessTree(agentContext, toolCallId, process);
                    break;
                }
                if (total >= maxTimeoutMs) {
                    log.warn("execute_command 总运行时间超时触发 ({}ms)，级联强杀整个进程树: {}", total, process.pid());
                    maxTimeoutFlag.set(true);
                    ProcessTracker.cascadeKillProcessTree(agentContext, toolCallId, process);
                    break;
                }
            }
        });

        int exitCode;
        try {
            process.waitFor();
            exitCode = process.exitValue();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            ProcessTracker.cascadeKillProcessTree(agentContext, toolCallId, process);
            return ToolResult.error(buildInterruptMessage(ie));
        } finally {
            watchdog.interrupt();
        }

        boolean idleTimeout = idleTimeoutFlag.get();
        boolean maxTimeout = maxTimeoutFlag.get();
        boolean isKilledByTimeout = idleTimeout || maxTimeout;
        long drainTimeoutMs = isKilledByTimeout ? 1_000L : OUTPUT_DRAIN_TIMEOUT_MS;

        String outputResult;
        try {
            outputResult = outputFuture.get(drainTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("execute_command 输出流在 {}ms 内未随进程退出而关闭，强制断开管道并放弃等待残余输出", drainTimeoutMs);
            outputFuture.cancel(true);
            closeStreamQuietly(process.getInputStream());
            closeStreamQuietly(process.getErrorStream());
            outputResult = "[警告] 命令输出流未随进程终止正常关闭，已放弃等待残余输出，以上内容可能不完整。";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            outputFuture.cancel(true);
            closeStreamQuietly(process.getInputStream());
            closeStreamQuietly(process.getErrorStream());
            ProcessTracker.cascadeKillProcessTree(agentContext, toolCallId, process);
            return ToolResult.error(buildInterruptMessage(ie));
        } catch (ExecutionException ee) {
            log.warn("execute_command 读取输出流线程异常: {}", ee.getMessage());
            outputResult = "";
        }

        // 命令完成后登记输出摘要（指纹与预检一致）
        CommandPrechecker.recordCommandOutput(agentContext, repeatFingerprint, outputResult);

        if (idleTimeout) {
            log.warn("execute_command 空闲超时中止: {}", finalCommand);
            return CommandResultBuilder.buildCommandResult(-1, true, dir.getAbsolutePath(),
                    outputResult + "\n\n[错误] 命令长时间无输出，疑似卡在交互等待，已强行中止 (IdleLimit: " + idleTimeoutMs + "ms)。\n"
                            + "[说明] 超时后已对整个进程树执行级联强杀（含子进程与孙进程）；若命令有落盘类副作用，可能已部分生效，"
                            + "判定结果前请先用 read_file 验证目标文件的实际状态。",
                    true);
        }

        if (maxTimeout) {
            log.warn("execute_command 总运行时间超时中止: {}", finalCommand);
            return CommandResultBuilder.buildCommandResult(-1, true, dir.getAbsolutePath(),
                    outputResult + "\n\n[错误] 命令总运行时间超限，已强行中止 (MaxLimit: " + maxTimeoutMs + "ms)。\n"
                            + "[说明] 超时后已对整个进程树执行级联强杀（含子进程与孙进程）；若命令有落盘类副作用，可能已部分生效，"
                            + "判定结果前请先用 read_file 验证目标文件的实际状态。",
                    false);
        }

        log.info("execute_command 命令执行完成, exitCode: {}", exitCode);

        String finalOutput = outputResult;
        if (exitCode == 0 && !StringUtils.hasText(finalOutput)) {
            finalOutput = "[命令执行成功（exitCode=0），但无任何标准输出。属正常现象：如静默成功类命令、或输出被重定向到了文件]";
        } else {
            finalOutput = CommandResultBuilder.compactOutputIfNeeded(finalOutput);
            if (exitCode != 0) {
                finalOutput = CommandResultBuilder.appendFailureHints(finalOutput, bashEntry);
            }
        }

        return CommandResultBuilder.buildCommandResult(exitCode, false, dir.getAbsolutePath(), finalOutput);
    }

    /**
     * Windows 环境下的整流编码自动探测读取器（后台任务专用）。
     */
    private static BufferedReader createEncodingAwareReader(InputStream inputStream) {
        byte[] probeBuffer = new byte[8192];
        int probeLen = 0;
        try {
            int available = inputStream.available();
            int toRead = Math.min(available > 0 ? available : 1024, probeBuffer.length);
            int totalRead = 0;
            int attempts = 0;
            while (totalRead < toRead && attempts < 10) {
                int read = inputStream.read(probeBuffer, totalRead, toRead - totalRead);
                if (read == -1) {
                    break;
                }
                if (read == 0) {
                    Thread.sleep(20);
                    attempts++;
                    int newAvail = inputStream.available();
                    if (newAvail > 0) {
                        toRead = Math.min(totalRead + newAvail, probeBuffer.length);
                    }
                    continue;
                }
                totalRead += read;
                if (probeBuffer[totalRead - 1] == '\n' || probeBuffer[totalRead - 1] == '\r') {
                    break;
                }
            }
            probeLen = totalRead;
        } catch (Exception e) {
            log.warn("[编码探测] 读取首批字节异常，降级为系统默认编码: {}", Charset.defaultCharset().name(), e);
            probeLen = 0;
        }

        Charset detected = NativeCharsetKit.detectCharset(probeBuffer, probeLen);
        log.info("[编码探测] 探测结果: {}, 探测样本大小: {} bytes", detected.name(), probeLen);

        ByteArrayInputStream probeStream = new ByteArrayInputStream(probeBuffer, 0, probeLen);
        SequenceInputStream combinedStream = new SequenceInputStream(probeStream, inputStream);
        return new BufferedReader(new InputStreamReader(combinedStream, detected));
    }

    /**
     * 构建中断异常提示信息，避免在 ie.getMessage() 为 null 时输出 ": null"
     */
    private static String buildInterruptMessage(InterruptedException ie) {
        String msg = ie.getMessage();
        return (msg != null && !msg.isBlank()) ? "命令执行被中断: " + msg : "命令执行被中断";
    }

    /**
     * 安全静默关闭流对象，忽略任何 IO 异常
     */
    private static void closeStreamQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception ignored) {
            }
        }
    }
}
