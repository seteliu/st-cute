package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolExecutionContext;
import com.stioc.cute.tool.access.ToolNames;

import com.stioc.cute.security.access.WorkspacePathResolver;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.agent.access.ActiveProcess;
import com.stioc.cute.agent.access.RepeatCommandTracker;
import com.stioc.cute.agent.event.AgentEventFactory;
import com.stioc.cute.platform.common.NativeCharsetKit;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在项目/工作区安全运行 shell 指令的本地核心命令工具
 */
@Slf4j
@Component
public class RunCommandTool implements CuteTool {

    @Resource
    private WorkspacePathResolver workspacePathResolver;

    /**
     * 同一命令输出完全相同的容忍次数：连续第 N 次相同即判定为无效死循环重复。
     * 阈值放宽至 10 次：正常研发场景存在「命令执行成功但结果回传偶发丢失」时的合理重试验证，
     * 误杀有效重试的代价高于多放行几次的代价。
     */
    private static final int REPEAT_BREAK_THRESHOLD = 10;

    /**
     * 重复判定的时间窗口：超过该时长未重复执行，历史计数视为失效（毫秒）
     */
    private static final long REPEAT_WINDOW_MS = 3 * 60 * 1000L;

    @Override
    public String getName() {
        return ToolNames.EXECUTE_COMMAND;
    }

    @Override
    public String getDescription() {
        return "【安全通用工具】在指定的运行目录下执行终端命令。注意：严禁使用此工具拼凑执行 'cat'、'find'、'grep' 等专用命令，当需要读取、寻找或全文搜索文件时，必须使用对应的只读工具。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "command": {
              "type": "string",
              "description": "待运行的 shell 命令行语句"
            },
            "cwd": {
              "type": "string",
              "description": "命令运行的指定工作目录路径（可选，默认为当前项目根目录或 worktree 隔离路径）"
            },
            "encoding": {
              "type": "string",
              "description": "指定子进程输出的解码字符集，可选。支持 'auto'（默认，自动探测 UTF-8 优先回退系统原生编码）、'utf-8'、'gbk'，也可传 Java 合法字符集名。出现中文乱码或编码混排时可显式指定。"
            },
            "idleTimeoutMs": {
              "type": "integer",
              "description": "stdout 空闲超时时间，单位毫秒（可选，默认 15000ms）。超过此时间无任何新输出则视为命令卡在交互等待，强制中止。适合大多数场景。"
            },
            "maxTimeoutMs": {
              "type": "integer",
              "description": "命令总运行时间上限，单位毫秒（可选，默认 600000ms）。对于 mvn install、npm install 等本身耗时较长但会持续输出的命令，可适当加大此值。"
            },
            "runInBackground": {
              "type": "boolean",
              "description": "是否在后台持续运行。当需要拉起前端服务器、后端微服务等不会主动退出的持久后台进程时，必须传入 true 以防卡死智能体工具调用流（默认 false）"
            }
          },
          "required": ["command"]
        }
        """;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        String command = (String) arguments.get("command");
        if (command == null || command.isBlank()) {
            return new JSONObject().fluentPut("error", "参数 'command' 不能为空。").toJSONString();
        }

        // 解析编码参数：auto = 自动探测（默认），其他值按 Java 字符集名强制指定
        String encodingVal = arguments.get("encoding") != null ? String.valueOf(arguments.get("encoding")).trim() : "auto";
        Charset forcedCharset = null;
        if (!"auto".equalsIgnoreCase(encodingVal)) {
            try {
                forcedCharset = Charset.forName(encodingVal);
            } catch (Exception e) {
                return new JSONObject().fluentPut("error",
                        "参数 'encoding' 的值 '" + encodingVal + "' 不是合法字符集名 (Exception: " + e.getMessage() + ")。"
                                + "常用取值：auto（自动探测）、utf-8、gbk。").toJSONString();
            }
        }
        final Charset finalForcedCharset = forcedCharset;

        String customCwd = arguments.get("cwd") != null ? String.valueOf(arguments.get("cwd")) : null;
        long idleTimeoutMs = arguments.get("idleTimeoutMs") != null
                ? ((Number) arguments.get("idleTimeoutMs")).longValue() : 15_000L;
        long maxTimeoutMs = arguments.get("maxTimeoutMs") != null
                ? ((Number) arguments.get("maxTimeoutMs")).longValue() : 600_000L;
        boolean runInBackground = arguments.get("runInBackground") != null
                && Boolean.parseBoolean(String.valueOf(arguments.get("runInBackground")));

        log.info("execute_command 尝试运行命令: {}, Cwd: {}, Encoding: {}, IdleTimeout: {}ms, MaxTimeout: {}ms, RunInBackground: {}",
                command, customCwd, forcedCharset != null ? forcedCharset.name() : "auto", idleTimeoutMs, maxTimeoutMs, runInBackground);

        // 工作目录多级兜底：显式 cwd > worktreePath > 项目路径 > JVM 工作目录。
        // 解析必须先于熔断预检：重复熔断指纹需绑定实际工作目录，
        // 同一命令在不同仓库/目录下执行属于不同语义，严禁仅凭命令文本误判为重复
        String os = System.getProperty("os.name").toLowerCase();
        ProcessBuilder pb;
        if (os.contains("win")) {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            pb = new ProcessBuilder("sh", "-c", command);
        }
        File dir = null;
        if (StringUtils.hasText(customCwd)) {
            dir = workspacePathResolver.resolvePath(customCwd, agentContext).toFile();
        } else {
            String basePath = workspacePathResolver.getProjectBasePath(agentContext);
            if (StringUtils.hasText(basePath)) {
                dir = new File(basePath).getAbsoluteFile();
            }
        }
        if (dir == null) {
            dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        }
        pb.directory(dir);
        log.info("execute_command 命令子进程 Cwd 物理重定向至路径: {}", dir.getAbsolutePath());
        pb.redirectErrorStream(true);

        // 熔断保险丝：同一命令 + 同一实际工作目录 + 短时间内输出完全相同的无效重复调用检测，
        // 防止模型陷入死循环空耗轮次
        String repeatFingerprint = command + " @cwd:" + dir.getAbsolutePath();
        String rejectReason = checkRepeatBreak(agentContext, repeatFingerprint);
        if (rejectReason != null) {
            return new JSONObject().fluentPut("error", rejectReason).toJSONString();
        }

        int exitCode = -1;
        boolean idleTimeout = false;
        boolean maxTimeout = false;
        String outputResult = "";
        String toolCallId = context.toolCallId();
        Process process = null;

        try {
            process = pb.start();

            // 注册物理子进程至 Context 中
            if (toolCallId != null) {
                List<Long> childPids = new CopyOnWriteArrayList<>();
                // 1. 首期瞬间捕获快照
                try {
                    Thread.sleep(100);
                    process.toHandle().descendants().forEach(h -> childPids.add(h.pid()));
                } catch (Exception ignored) {}

                log.info("[后台进程注册] 成功启动物理主进程, PID={}, 初始捕获后代数={}, PIDs={}",
                        process.pid(), childPids.size(), childPids);

                ActiveProcess activeProcess = new ActiveProcess(
                        childPids,
                        agentContext.getCid(),
                        toolCallId,
                        process,
                        command,
                        dir.getAbsolutePath(),
                        System.currentTimeMillis()
                );
                agentContext.getActiveProcesses().put(toolCallId, activeProcess);

                // 2. 异步增量安全追踪：在服务启动前 3 秒内，每隔 500ms 动态补录新诞生的后代进程（例如 node.exe）
                Process finalProcess = process;
                Thread.startVirtualThread(() -> {
                    for (int i = 0; i < 6; i++) {
                        try {
                            Thread.sleep(500);
                            if (finalProcess.isAlive()) {
                                finalProcess.toHandle().descendants().forEach(h -> {
                                    long pid = h.pid();
                                    if (!childPids.contains(pid)) {
                                        childPids.add(pid);
                                        log.info("[后代进程动态捕获 - {}] 捕获到新生的后代工作进程 PID: {}", toolCallId, pid);
                                    }
                                });
                            } else {
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                });
            }

            // Windows 下子进程输出编码自动探测（UTF-8 优先，失败回退系统原生编码）；
            // 非 Windows 环境直接使用 UTF-8；显式 encoding 参数指定时强制使用指定编码跳过探测
            boolean needDetect = os.contains("win") && finalForcedCharset == null;

            if (runInBackground) {
                // 如果是后台挂起任务，把阻塞读取流的工作扔给异步的虚拟线程，避免卡死主线程执行流
                Process finalProcess = process;
                StringBuffer backgroundOutput = new StringBuffer();

                Thread.startVirtualThread(() -> {
                    log.info("[异步后台日志线程启动] toolCallId={}, Command={}", toolCallId, command);
                    try (BufferedReader reader = needDetect
                            ? createEncodingAwareReader(finalProcess.getInputStream())
                            : new BufferedReader(new InputStreamReader(finalProcess.getInputStream(),
                                    finalForcedCharset != null ? finalForcedCharset : StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sendIncrementalLog(toolCallId, line + "\n", agentContext);
                            log.info("[后台控制台输出 - {}] {}", toolCallId, line);

                            // 保留前期一部分报错日志提供给主线程失败返回
                            if (backgroundOutput.length() < 20000) {
                                backgroundOutput.append(line).append("\n");
                            }
                        }
                    } catch (Exception e) {
                        String msg = e.getMessage();
                        if (e instanceof java.io.IOException && msg != null &&
                                (msg.contains("closed") || msg.contains("管道已结束") || msg.contains("Pipe"))) {
                            log.info("[异步后台日志线程] 管道流已正常断开或关闭: toolCallId={}", toolCallId);
                        } else {
                            log.warn("[异步后台日志线程读取中断 - {}] 读取标准输出异常: {}", toolCallId, e.getMessage());
                        }
                    } finally {
                        // 级联判断当前进程树中是否有后代孙子进程（例如node.exe）依然处于存活运行状态
                        boolean anyChildAlive = false;
                        if (toolCallId != null) {
                            ActiveProcess active = agentContext.getActiveProcesses().get(toolCallId);
                            if (active != null && active.getChildPids() != null) {
                                for (Long childPid : active.getChildPids()) {
                                    if (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)) {
                                        anyChildAlive = true;
                                        break;
                                    }
                                }
                            }
                        }

                        boolean isAlive = finalProcess.isAlive() || anyChildAlive;

                        if (isAlive) {
                            log.info("[异步后台日志线程结束 - {}] 流读取关闭，但相关物理进程树仍在后台活跃运行, PID={}", toolCallId, finalProcess.pid());
                        } else {
                            log.info("[异步后台日志线程结束 - {}] 流读取关闭且物理进程树已完全退出, PID={}, ExitValue={}",
                                    toolCallId, finalProcess.pid(), finalProcess.isAlive() ? 0 : finalProcess.exitValue());
                            // 只有在确定主进程和后代进程全死时，才从 Context 活动列表中注销它
                            if (toolCallId != null) {
                                agentContext.getActiveProcesses().remove(toolCallId);
                            }
                        }
                    }
                });

                // 主线程挂起进行 3.0 秒的“初始化安全观察窗口”，给进程加载和失败退出留出充裕的操作系统调度时间
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {}

                // 观察窗口结束后，对主进程状态和退出码进行校验
                boolean isAlive = finalProcess.isAlive();
                int exitVal = isAlive ? 0 : finalProcess.exitValue();

                // 核心判定：主进程必须处于运行状态，或者主进程虽退役但退出码为0且后代业务进程依然健在（即toolCallId依然保留在活动映射中）
                boolean successfullyStarted = isAlive || (exitVal == 0 && toolCallId != null && agentContext.getActiveProcesses().containsKey(toolCallId));

                // 若检测到未正常启动运行，代表配置或路径出错，返回真实的退出码与报错输出
                if (!successfullyStarted) {
                    log.warn("[后台进程启动失败] 进程在拉起后 3s 内异常退出, toolCallId={}, exitValue={}", toolCallId, exitVal);

                    if (toolCallId != null) {
                        agentContext.getActiveProcesses().remove(toolCallId);
                    }

                    return new JSONObject()
                            .fluentPut("exitCode", exitVal == 0 ? -1 : exitVal)
                            .fluentPut("timeout", false)
                            .fluentPut("output", "[错误] 后台服务启动失败，进程在初始化时退出！\n\n终端异常输出如下：\n" + backgroundOutput.toString())
                            .toJSONString();
                }

                // 立即返回成功，告诉智能体该服务已成功在后台拉起
                return new JSONObject()
                        .fluentPut("exitCode", 0)
                        .fluentPut("timeout", false)
                        .fluentPut("output", "[系统提示] 持久后台服务已成功在后台拉起并运行，您可以继续执行其他操作。")
                        .toJSONString();
            }

            Process finalProcess = process;
            final long finalIdleTimeoutMs = idleTimeoutMs;
            final long finalMaxTimeoutMs = maxTimeoutMs;

            // 记录最近一次收到新输出的时间戳，用于空闲超时检测
            AtomicLong lastOutputTime = new AtomicLong(System.currentTimeMillis());
            // 用原子标志位记录是哪种超时触发的强杀，watchdog 直接写，主线程读，无竞态
            AtomicBoolean idleTimeoutFlag = new AtomicBoolean(false);
            AtomicBoolean maxTimeoutFlag = new AtomicBoolean(false);

            // 异步读取 stdout，通过 CompletableFuture 将结果安全传回主线程，彻底消除共享状态竞态
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = needDetect
                        ? createEncodingAwareReader(finalProcess.getInputStream())
                        : new BufferedReader(new InputStreamReader(finalProcess.getInputStream(),
                                finalForcedCharset != null ? finalForcedCharset : StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lastOutputTime.set(System.currentTimeMillis());
                        sb.append(line).append("\n");
                        sendIncrementalLog(toolCallId, line + "\n", agentContext);
                        if (sb.length() > 100_000) {
                            sb.append("... [此处由于输出量过大已截断] ...");
                            finalProcess.destroyForcibly();
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    // 管道正常关闭时会抛 IOException，忽略即可
                }
                return sb.toString();
            });

            // Watchdog：每 500ms 检查一次空闲时长和总运行时长，触发时设标志位并强杀进程
            Thread watchdog = Thread.startVirtualThread(() -> {
                long startTime = System.currentTimeMillis();
                while (finalProcess.isAlive()) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    long now = System.currentTimeMillis();
                    long idle = now - lastOutputTime.get();
                    long total = now - startTime;
                    if (idle >= finalIdleTimeoutMs) {
                        log.warn("execute_command 空闲超时触发 ({}ms 无新输出)，强杀进程: {}", idle, finalProcess.pid());
                        idleTimeoutFlag.set(true);
                        finalProcess.destroyForcibly();
                        break;
                    }
                    if (total >= finalMaxTimeoutMs) {
                        log.warn("execute_command 总运行时间超时触发 ({}ms)，强杀进程: {}", total, finalProcess.pid());
                        maxTimeoutFlag.set(true);
                        finalProcess.destroyForcibly();
                        break;
                    }
                }
            });

            try {
                // 无限期等待进程退出，超时控制完全交给 watchdog
                process.waitFor();
                exitCode = process.exitValue();
            } finally {
                // 正常退出时中断 watchdog，避免其继续空转
                watchdog.interrupt();
            }

            // 进程已退出，管道必然关闭，outputFuture 会自然完成，无限期 join 不会卡住
            outputResult = outputFuture.join();
            idleTimeout = idleTimeoutFlag.get();
            maxTimeout = maxTimeoutFlag.get();

        } catch (Exception e) {
            log.error("execute_command 执行异常", e);
            if (process != null) {
                try {
                    process.destroyForcibly();
                } catch (Exception ex) {
                    log.error("强杀进程异常", ex);
                }
            }
            return new JSONObject().fluentPut("error", "命令执行发生异常: " + e.getMessage()).toJSONString();
        } finally {
            if (!runInBackground && toolCallId != null) {
                agentContext.getActiveProcesses().remove(toolCallId);
            }
        }

        // 命令完成后登记输出摘要（指纹与预检一致：命令 + 实际工作目录），供下一次执行时做无效重复判定
        recordCommandOutput(agentContext, repeatFingerprint, outputResult);

        if (idleTimeout) {
            log.warn("execute_command 空闲超时中止: {}", command);
            return new JSONObject()
                    .fluentPut("exitCode", -1)
                    .fluentPut("timeout", true)
                    .fluentPut("idleTimeout", true)
                    .fluentPut("output", outputResult + "\n\n[错误] 命令长时间无输出，疑似卡在交互等待，已强行中止 (IdleLimit: " + idleTimeoutMs + "ms)")
                    .toJSONString();
        }

        if (maxTimeout) {
            log.warn("execute_command 总运行时间超时中止: {}", command);
            return new JSONObject()
                    .fluentPut("exitCode", -1)
                    .fluentPut("timeout", true)
                    .fluentPut("idleTimeout", false)
                    .fluentPut("output", outputResult + "\n\n[错误] 命令总运行时间超限，已强行中止 (MaxLimit: " + maxTimeoutMs + "ms)")
                    .toJSONString();
        }

        log.info("execute_command 命令执行完成, exitCode: {}", exitCode);
        return new JSONObject()
                .fluentPut("exitCode", exitCode)
                .fluentPut("timeout", false)
                .fluentPut("output", outputResult)
                .toJSONString();
    }

    /**
     * 同一命令无效重复执行检测（熔断保险丝）。
     * <p>
     * 判定条件（全部满足才拒绝）：同一指纹（命令 + 实际工作目录）+ 3 分钟时间窗口内 + 已连续 10 次产生完全相同的输出。
     * 输出一旦变化立即重置计数。检测到重复时返回拒绝理由文案，判定放行时返回 null。
     * 指纹纳入工作目录的原因：同一命令在不同仓库/目录下执行语义完全不同，仅凭命令文本判定会造成跨目录误杀。
     * </p>
     */
    private String checkRepeatBreak(AgentContext agentContext, String command) {
        if (agentContext == null) {
            return null;
        }
        RepeatCommandTracker tracker = agentContext.getRecentCommands().computeIfAbsent(command, k -> new RepeatCommandTracker());
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
                    command, REPEAT_WINDOW_MS / 60000, tracker.getSameOutputCount().get());
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
    private void recordCommandOutput(AgentContext agentContext, String command, String output) {
        if (agentContext == null) {
            return;
        }
        RepeatCommandTracker tracker = agentContext.getRecentCommands().computeIfAbsent(command, k -> new RepeatCommandTracker());
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
     * Windows 环境下的编码自动探测读取器。
     * <p>
     * 缓冲前 8KB 原始字节，先用 UTF-8 严格解码尝试：
     * - 成功 → 全程锁定 UTF-8
     * - 失败 → 全程回退系统原生编码
     * </p>
     * <p>
     * 探测完成后，将缓冲的首批字节与剩余流拼接为完整的 Reader，
     * 保证首批数据无丢失、后续数据编码锁定一致。</p>
     *
     * @param inputStream 子进程的原始输出流
     * @return 编码锁定后的 BufferedReader
     */
    private BufferedReader createEncodingAwareReader(InputStream inputStream) {
        byte[] probeBuffer = new byte[8192];
        int probeLen = 0;
        try {
            // 非阻塞式读取：尽可能多读，但不无限等待
            int available = inputStream.available();
            int toRead = Math.min(available > 0 ? available : 1024, probeBuffer.length);
            int totalRead = 0;
            // 给子进程一小段时间吐出前几行输出
            int attempts = 0;
            while (totalRead < toRead && attempts < 10) {
                int read = inputStream.read(probeBuffer, totalRead, toRead - totalRead);
                if (read == -1) {
                    break;
                }
                if (read == 0) {
                    // 暂无数据，短暂等待后重试
                    Thread.sleep(20);
                    attempts++;
                    int newAvail = inputStream.available();
                    if (newAvail > 0) {
                        toRead = Math.min(totalRead + newAvail, probeBuffer.length);
                    }
                    continue;
                }
                totalRead += read;
                // 读到换行符就够用，不必攒满 8KB
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

        // 将探测缓冲区与剩余流拼接
        ByteArrayInputStream probeStream = new ByteArrayInputStream(probeBuffer, 0, probeLen);
        SequenceInputStream combinedStream = new SequenceInputStream(probeStream, inputStream);
        return new BufferedReader(new InputStreamReader(combinedStream, detected));
    }

    private void sendIncrementalLog(String toolCallId, String text, AgentContext agentContext) {
        if (toolCallId != null && agentContext != null) {
            try {
                agentContext.publishEvent(AgentEventFactory.createToolLogStream(agentContext, toolCallId, text));
            } catch (Exception e) {
                log.error("发送控制台流式日志出错", e);
            }
        }
    }

}
