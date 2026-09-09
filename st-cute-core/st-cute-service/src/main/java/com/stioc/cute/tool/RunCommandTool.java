package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolResult;
import com.stioc.cute.project.ProjectService;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.runtime.loop.RuntimeContext;
import com.stioc.cute.tool.support.ActiveProcess;
import com.stioc.cute.tool.support.RepeatCommandTracker;
import com.stioc.cute.engine.event.AgentEventFactory;
import com.stioc.cute.platform.common.LineMixedCharsetReader;
import com.stioc.cute.platform.common.NativeCharsetKit;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private ProjectService projectService;

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
    private static final long BUILD_IDLE_TIMEOUT_MS = 90_000L;

    /**
     * 输出保护上限（字符）：超过判定为失控刷屏，强制中止进程，防内存无限膨胀。
     * 正常超限输出不再截断中止，改为返回时头尾保留压缩（见 {@link #compactOutputIfNeeded}）
     */
    private static final int OUTPUT_PROTECT_LIMIT = 2_000_000;

    /**
     * 单次返回给模型的输出展示上限（字符）：超过则压缩为头尾保留 + 中间省略摘要
     */
    private static final int OUTPUT_DISPLAY_LIMIT = 100_000;

    /**
     * 输出压缩时保留的开头字符数
     */
    private static final int OUTPUT_HEAD_KEEP = 32_000;

    /**
     * 输出压缩时保留的结尾字符数
     */
    private static final int OUTPUT_TAIL_KEEP = 32_000;

    @Override
    public String getRawName() {
        return ToolNames.EXECUTE_COMMAND;
    }

    @Override
    public String getDescription() {
        return "【安全通用工具】在指定的运行目录下执行终端命令。读取、查找或搜索文件内容时应优先使用 read_file / list_dir / grep_search 专用工具，它们更快且带安全防护；仅当专用工具无法解决（如工具报错、glob 语义不满足需求、需管道组合处理）时，才使用命令兜底。"
                + "验证类命令（编译/构建/测试等需依据退出码判断成败）必须单命令执行，禁止用 &、&&、|| 同行拼接多条命令（返回的 exitCode 仅代表最后一条命令，前序失败会被掩盖）。";
    }

    @Override
    public ToolAccessLevel getAccessLevel() {
        // 敏感级：执行终端命令为高危操作，智能审批模式同样需要人工确认
        return ToolAccessLevel.SENSITIVE;
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
              "description": "命令运行的指定工作目录路径（可选，默认为当前项目根目录或 worktree 隔离路径）。实际生效的工作目录会在返回结果的 cwd 字段中回显"
            },
            "encoding": {
              "type": "string",
              "description": "子进程输出解码字符集（默认 auto 自动探测）。中文乱码或编码混排时可显式指定，如 utf-8、gbk"
            },
            "idleTimeoutMs": {
              "type": "integer",
              "description": "无输出超时（毫秒，默认 30000）。命令持续无新输出超过此值即判卡死强制中止；构建类命令（mvn/gradle/npm 等）未指定时自动放宽至 90000。总时长上限另见 maxTimeoutMs"
            },
            "maxTimeoutMs": {
              "type": "integer",
              "description": "总运行时长上限（毫秒，默认 600000）。命令累计运行超过此值强制中止，如 mvn install 等长命令可加大。无输出超时另见 idleTimeoutMs"
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
        ToolArgs args = ToolArgs.of(arguments);
        String command = args.getString("command");
        if (command == null || command.isBlank()) {
            return ToolResult.error("参数 'command' 不能为空。");
        }

        // 硬拦截：禁止使用 PowerShell cmdlet 写入文件。
        // Windows 下 Set-Content/Out-File 极易产生编码问题（UTF-8 BOM 导致 javac 报"非法字符 \ufeff"、GBK 中文损坏），
        // 提示词属软约束无法根治，工具层物理拦截才是真防线（本项目已两次实证翻车）
        String psWriteReject = checkPowerShellWrite(command);
        if (psWriteReject != null) {
            return ToolResult.error(psWriteReject);
        }

        // 解析编码参数：auto = 自动探测（默认），其他值按 Java 字符集名强制指定
        String encodingVal = args.getStringTrimmed("encoding", "auto");
        Charset forcedCharset = null;
        if (!"auto".equalsIgnoreCase(encodingVal)) {
            try {
                forcedCharset = Charset.forName(encodingVal);
            } catch (Exception e) {
                return ToolResult.error("参数 'encoding' 的值 '" + encodingVal + "' 不是合法字符集名 (Exception: " + e.getMessage() + ")。"
                        + "常用取值：auto（自动探测）、utf-8、gbk。");
            }
        }
        final Charset finalForcedCharset = forcedCharset;

        String customCwd = args.getStringTrimmed("cwd");
        Long idleTimeoutVal = args.getLong("idleTimeoutMs");
        boolean idleTimeoutExplicit = idleTimeoutVal != null;
        long idleTimeoutMs;
        if (idleTimeoutExplicit) {
            // 显式传参优先：模型自己判断的场景（如已知的长静默命令）尊重其选择
            idleTimeoutMs = idleTimeoutVal;
        } else if (isBuildCommand(command)) {
            // 构建类命令自动放宽：依赖解析/镜像拉取静默期轻松超过通用默认值
            idleTimeoutMs = BUILD_IDLE_TIMEOUT_MS;
        } else {
            idleTimeoutMs = 30_000L;
        }
        if (!idleTimeoutExplicit && idleTimeoutMs != 30_000L) {
            log.info("execute_command 构建类命令识别命中，空闲超时自动放宽至 {}ms", idleTimeoutMs);
        }
        long maxTimeoutMs = args.getLong("maxTimeoutMs", 600_000L);
        boolean runInBackground = Boolean.TRUE.equals(args.getBoolean("runInBackground"));

        // git 命令自动增强：注入 i18n 输出编码与中文路径显示配置，根治 git log/diff 输出中文乱码
        String finalCommand = enhanceGitCommand(command);
        if (!finalCommand.equals(command)) {
            log.info("execute_command git 命令增强注入，实际执行: {}", finalCommand);
        }

        log.info("execute_command 尝试运行命令: {}, Cwd: {}, Encoding: {}, IdleTimeout: {}ms, MaxTimeout: {}ms, RunInBackground: {}",
                finalCommand, customCwd, forcedCharset != null ? forcedCharset.name() : "auto", idleTimeoutMs, maxTimeoutMs, runInBackground);

        // 工作目录多级兜底：显式 cwd > 项目路径 > JVM 工作目录。
        // 解析必须先于熔断预检：重复熔断指纹需绑定实际工作目录，
        // 同一命令在不同仓库/目录下执行属于不同语义，严禁仅凭命令文本误判为重复
        String os = System.getProperty("os.name").toLowerCase();
        ProcessBuilder pb;
        if (os.contains("win")) {
            pb = new ProcessBuilder("cmd.exe", "/c", finalCommand);
        } else {
            pb = new ProcessBuilder("sh", "-c", finalCommand);
        }
        File dir = null;
        if (StringUtils.hasText(customCwd)) {
            dir = projectService.resolvePath(customCwd, agentContext).toFile();
        } else {
            String basePath = projectService.getProjectBasePath(agentContext);
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
        String repeatFingerprint = finalCommand + " @cwd:" + dir.getAbsolutePath();
        String rejectReason = checkRepeatBreak(agentContext, repeatFingerprint);
        if (rejectReason != null) {
            return ToolResult.error(rejectReason);
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
                        finalCommand,
                        dir.getAbsolutePath(),
                        System.currentTimeMillis()
                );
                RuntimeContext runtimeCtx = agentContext.extra(RuntimeContext.class);
                if (runtimeCtx != null) {
                    runtimeCtx.getActiveProcesses().put(toolCallId, activeProcess);
                }

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

            // Windows 下子进程输出编码自动探测：无显式 encoding 参数时，前台任务使用行级混合编码读取
            // （UTF-8 行与 GBK 行并存可同时正确还原，git 提交说明 + javac 诊断混流场景），
            // 后台任务为避免探测开销使用整流探测（与既有行为一致）；
            // 非 Windows 环境直接使用 UTF-8；显式 encoding 参数指定时强制使用指定编码
            boolean needDetect = os.contains("win") && finalForcedCharset == null;
            boolean lineLevelDetect = needDetect && !runInBackground;

            if (runInBackground) {
                // 如果是后台挂起任务，把阻塞读取流的工作扔给异步的虚拟线程，避免卡死主线程执行流
                Process finalProcess = process;
                StringBuffer backgroundOutput = new StringBuffer();

                Thread.startVirtualThread(() -> {
                    log.info("[异步后台日志线程启动] toolCallId={}, Command={}", toolCallId, finalCommand);
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
                        if (e instanceof IOException && msg != null &&
                                (msg.contains("closed") || msg.contains("管道已结束") || msg.contains("Pipe"))) {
                            log.info("[异步后台日志线程] 管道流已正常断开或关闭: toolCallId={}", toolCallId);
                        } else {
                            log.warn("[异步后台日志线程读取中断 - {}] 读取标准输出异常: {}", toolCallId, e.getMessage());
                        }
                    } finally {
                        // 级联判断当前进程树中是否有后代孙子进程（例如node.exe）依然处于存活运行状态
                        boolean anyChildAlive = false;
                        if (toolCallId != null) {
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

                        boolean isAlive = finalProcess.isAlive() || anyChildAlive;

                        if (isAlive) {
                            log.info("[异步后台日志线程结束 - {}] 流读取关闭，但相关物理进程树仍在后台活跃运行, PID={}", toolCallId, finalProcess.pid());
                        } else {
                            log.info("[异步后台日志线程结束 - {}] 流读取关闭且物理进程树已完全退出, PID={}, ExitValue={}",
                                    toolCallId, finalProcess.pid(), finalProcess.isAlive() ? 0 : finalProcess.exitValue());
                            // 只有在确定主进程和后代进程全死时，才从 Context 活动列表中注销它
                            if (toolCallId != null) {
                                RuntimeContext bgRuntimeCtx = agentContext.extra(RuntimeContext.class);
                                if (bgRuntimeCtx != null) {
                                    bgRuntimeCtx.getActiveProcesses().remove(toolCallId);
                                }
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

                RuntimeContext checkRuntimeCtx = agentContext.extra(RuntimeContext.class);
                // 核心判定：主进程必须处于运行状态，或者主进程虽退役但退出码为0且后代业务进程依然健在（即toolCallId依然保留在活动映射中）
                boolean successfullyStarted = isAlive || (exitVal == 0 && toolCallId != null && checkRuntimeCtx != null && checkRuntimeCtx.getActiveProcesses().containsKey(toolCallId));

                // 若检测到未正常启动运行，代表配置或路径出错，返回真实的退出码与报错输出
                if (!successfullyStarted) {
                    log.warn("[后台进程启动失败] 进程在拉起后 3s 内异常退出, toolCallId={}, exitValue={}", toolCallId, exitVal);

                    if (toolCallId != null) {
                        if (checkRuntimeCtx != null) {
                            checkRuntimeCtx.getActiveProcesses().remove(toolCallId);
                        }
                    }

                    return buildCommandResult(exitVal == 0 ? -1 : exitVal, false, dir.getAbsolutePath(),
                            "[错误] 后台服务启动失败，进程在初始化时退出！\n\n终端异常输出如下：\n" + backgroundOutput.toString());
                }

                // 立即返回成功，告诉智能体该服务已成功在后台拉起
                return buildCommandResult(0, false, dir.getAbsolutePath(),
                        "[系统提示] 持久后台服务已成功在后台拉起并运行，您可以继续执行其他操作。");
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
                try (BufferedReader reader = lineLevelDetect
                        ? new BufferedReader(new LineMixedCharsetReader(finalProcess.getInputStream()))
                        : new BufferedReader(new InputStreamReader(finalProcess.getInputStream(),
                                finalForcedCharset != null ? finalForcedCharset : StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lastOutputTime.set(System.currentTimeMillis());
                        sb.append(line).append("\n");
                        sendIncrementalLog(toolCallId, line + "\n", agentContext);
                        if (sb.length() > OUTPUT_PROTECT_LIMIT) {
                            // 失控刷屏保护：输出超保护上限判定为命令异常（如死循环 cat），中止进程。
                            // 正常命令的超长输出不在此中止，读取完整结束后返回时压缩为头尾保留
                            log.warn("execute_command 输出超过保护上限 {} 字符，判定失控刷屏，强杀进程", OUTPUT_PROTECT_LIMIT);
                            sb.append("\n... [输出已超过保护上限 ").append(OUTPUT_PROTECT_LIMIT)
                                    .append(" 字符，判定为失控刷屏（疑似死循环），已强制中止进程] ...\n");
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
            return ToolResult.error("命令执行发生异常: " + e.getMessage());
        } finally {
            if (!runInBackground && toolCallId != null) {
                RuntimeContext runtimeCtx = agentContext.extra(RuntimeContext.class);
                if (runtimeCtx != null) {
                    runtimeCtx.getActiveProcesses().remove(toolCallId);
                }
            }
        }

        // 命令完成后登记输出摘要（指纹与预检一致：命令 + 实际工作目录），供下一次执行时做无效重复判定
        recordCommandOutput(agentContext, repeatFingerprint, outputResult);

        if (idleTimeout) {
            log.warn("execute_command 空闲超时中止: {}", finalCommand);
            return buildCommandResult(-1, true, dir.getAbsolutePath(),
                    outputResult + "\n\n[错误] 命令长时间无输出，疑似卡在交互等待，已强行中止 (IdleLimit: " + idleTimeoutMs + "ms)",
                    true);
        }

        if (maxTimeout) {
            log.warn("execute_command 总运行时间超时中止: {}", finalCommand);
            return buildCommandResult(-1, true, dir.getAbsolutePath(),
                    outputResult + "\n\n[错误] 命令总运行时间超限，已强行中止 (MaxLimit: " + maxTimeoutMs + "ms)",
                    false);
        }

        log.info("execute_command 命令执行完成, exitCode: {}", exitCode);

        // 空结果自描述契约：静默成功（exitCode=0 且无输出）时补充说明，避免空 output 落库后被回填层替换为通用占位
        String finalOutput = outputResult;
        if (exitCode == 0 && !StringUtils.hasText(finalOutput)) {
            finalOutput = "[命令执行成功（exitCode=0），但无任何标准输出。属正常现象：如静默成功类命令、或输出被重定向到了文件]";
        } else {
            // 超长输出压缩为头尾保留 + 中间省略摘要（失控刷屏在读取层已被中止并带着完整保护说明）
            finalOutput = compactOutputIfNeeded(finalOutput);
            if (exitCode != 0) {
                // 失败场景附加已知误判模式提示（方案3/5）：帮助模型当场纠偏，而非反复盲试
                finalOutput = appendFailureHints(finalOutput);
            }
        }

        return buildCommandResult(exitCode, false, dir.getAbsolutePath(), finalOutput);
    }

    /**
     * 统一构建命令执行结果 JSON（exitCode/timeout/idleTimeout/cwd/output 五字段契约收口）。
     *
     * @param exitCode     进程退出码（异常兜底用 -1）
     * @param timeout      是否因超时被强制中止
     * @param cwd          实际生效的工作目录
     * @param output       终端输出（或面向模型的系统提示文案）
     * @param idleTimeout  是否空闲超时（非超时场景传 false 即可省略字段）
     */
    private String buildCommandResult(int exitCode, boolean timeout, String cwd, String output) {
        return buildCommandResult(exitCode, timeout, cwd, output, false);
    }

    /**
     * 统一构建命令执行结果 JSON（含空闲超时细分标记的重载）
     */
    private String buildCommandResult(int exitCode, boolean timeout, String cwd, String output, boolean idleTimeout) {
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
     * 构建类命令识别：取命令首 token（剥掉 ./ .\\ 前缀与 .exe/.cmd/.bat 后缀）小写后匹配名单
     */
    private boolean isBuildCommand(String command) {
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
     * git 命令自动增强注入。
     * <p>
     * 注入 {@code -c core.quotepath=false -c i18n.logOutputEncoding=utf-8}：
     * 中文路径不再输出八进制转义（\350\265\204...），log/diff 提交信息编码统一为 UTF-8，
     * 根治 Windows 下 git 输出中文乱码。仅对 Windows 环境且首 token 为 git 的命令生效；
     * 命令已自带 {@code -c} 配置前缀时不重复注入（尊重用户显式指定的配置）。
     * </p>
     *
     * @return 增强后的命令；非 git 命令或无需注入时原样返回
     */
    private String enhanceGitCommand(String command) {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return command;
        }
        String trimmed = command.trim();
        if (!trimmed.toLowerCase().startsWith("git ")) {
            return command;
        }
        String rest = trimmed.substring("git ".length()).trim();
        if (rest.startsWith("-c")) {
            return command;
        }
        return "git -c core.quotepath=false -c i18n.logOutputEncoding=utf-8 " + rest;
    }

    /**
     * 超长输出压缩：超过展示上限时保留头尾各 {@value #OUTPUT_HEAD_KEEP} 字符，
     * 中间替换为省略说明（含总长度与省略长度），让模型既能看到开头（命令与错误模式）
     * 也能看到结尾（结论与退出码上下文），中间重复内容不占上下文
     */
    private String compactOutputIfNeeded(String output) {
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
     */
    private String appendFailureHints(String output) {
        String hints = "";
        String trimmed = output != null ? output.trim() : "";

        // 模式1：管道末端命令无匹配导致退出码非 0 且无/极少输出（Windows findstr 典型场景）。
        // 前置命令可能并未失败，模型易误判为命令失败而反复重试
        if (trimmed.isEmpty() || trimmed.length() < 50) {
            hints += "\n\n[提示] 命令退出码非 0 且几乎无输出。若命令使用了管道（如 mvn xxx | findstr \"关键字\"），"
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
     * 硬拦截检测：命令使用 PowerShell cmdlet 写入文件。
     * 匹配规则（忽略大小写）：命令含 powershell/pwsh 且含 Set-Content/Out-File/Add-Content。
     * 防呆不防恶：不检测 base64 绕过，目的是拦误用而非对抗。
     *
     * @return 拒绝理由文案；不命中返回 null
     */
    private String checkPowerShellWrite(String command) {
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
                + "请改用 write_to_file（整文件写入）或 replace_file_content（局部替换）完成文件写入；"
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
    private String checkRepeatBreak(AgentContext agentContext, String command) {
        if (agentContext == null) {
            return null;
        }
        RuntimeContext runtimeCtx = agentContext.extra(RuntimeContext.class);
        if (runtimeCtx == null) {
            return null;
        }
        RepeatCommandTracker tracker = runtimeCtx.getRecentCommands().computeIfAbsent(command, k -> new RepeatCommandTracker());
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
        RuntimeContext runtimeCtx = agentContext.extra(RuntimeContext.class);
        if (runtimeCtx == null) {
            return;
        }
        RepeatCommandTracker tracker = runtimeCtx.getRecentCommands().computeIfAbsent(command, k -> new RepeatCommandTracker());
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
     * Windows 环境下的整流编码自动探测读取器（后台任务专用）。
     * <p>
     * 前台任务已改用 {@link LineMixedCharsetReader} 行级探测（UTF-8 行与 GBK 行混合输出两侧均正确）；
     * 后台长驻任务保持整流探测的既有行为：一次判定全程锁定，避免逐行判定的持续开销。
     * </p>
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
