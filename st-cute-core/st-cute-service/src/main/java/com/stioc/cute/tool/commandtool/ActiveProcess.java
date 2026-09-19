package com.stioc.cute.tool.commandtool;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stioc.cute.platform.common.VirtualThreads;
import com.stioc.cute.runtime.loop.RuntimeContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 封装会话运行中拉起的活动外部物理进程元数据
 */
@Slf4j
@Data
public class ActiveProcess {

    /**
     * 绑定的后代物理子进程 PID 集合（用于防 wrapper 进程脱钩）
     */
    private final List<Long> childPids;

    /**
     * 所属会话 ID
     */
    private final Long cid;

    /**
     * 本次工具调用的唯一 ID
     */
    private final String toolCallId;

    /**
     * 外部物理子进程实例引用
     */
    @JSONField(serialize = false)
    @JsonIgnore
    private final Process process;

    /**
     * 执行的终端命令字符串
     */
    private final String command;

    /**
     * 命令执行的物理工作目录
     */
    private final String cwd;

    /**
     * 进程启动的时间戳（毫秒）
     */
    private final long startTime;

    /**
     * 命令入口是否为 bash 系（Windows 经 Git Bash 执行时为 true）。
     * MSYS 家族徽章（PGID）清扫通道仅对 bash 入口有意义：cmd/powershell 入口的
     * Windows PPID 链完好，现有清扫手段已可覆盖
     */
    private final boolean bashEntry;

    /**
     * Git Bash 的 bash.exe 绝对路径（bashEntry=true 时由工具入口传入，
     * 供 MSYS 反查清扫推导同根目录下的 ps.exe；否则为 null）
     */
    @JSONField(serialize = false)
    @JsonIgnore
    private final String bashPath;

    /**
     * 兼容构造器：与旧 @AllArgsConstructor 七参签名一致（族徽与收网名单经
     * {@link #bindMsysPgid} 后置绑定）。手写原因：新增的非构造字段会破坏
     * Lombok 全参构造器的既有签名
     */
    public ActiveProcess(List<Long> childPids, Long cid, String toolCallId, Process process,
                         String command, String cwd, long startTime) {
        this(childPids, cid, toolCallId, process, command, cwd, startTime, false, null);
    }

    /**
     * 全参构造器：bash 入口信息随进程登记一并传入（bashEntry + bashPath）
     */
    public ActiveProcess(List<Long> childPids, Long cid, String toolCallId, Process process,
                         String command, String cwd, long startTime,
                         boolean bashEntry, String bashPath) {
        this.childPids = childPids;
        this.cid = cid;
        this.toolCallId = toolCallId;
        this.process = process;
        this.command = command;
        this.cwd = cwd;
        this.startTime = startTime;
        this.bashEntry = bashEntry;
        this.bashPath = bashPath;
    }

    /**
     * MSYS 家族徽章（进程组 PGID）：命令启动期经 {@code ps -W} 反查登记，终生不变。
     * bash 入口的命令超时清扫时按此徽章撒网收网，可达 PPID 断链后的一切后台作业孤儿；
     * 0 表示未登记/不适用（非 bash 入口或登记失败），清扫跳过该通道
     */
    private transient volatile long msysPgid;

    /**
     * 最近一次族徽收网捕获的家族成员 Windows PID 名单：用于存活复查与二次补杀，
     * 让面板可见性与手杀通道覆盖 MSYS 孤儿(其不在 childPids 名单内)
     */
    @JSONField(serialize = false)
    @JsonIgnore
    private transient volatile List<Long> msysWinPids;

    /**
     * 登记族徽（命令启动期异步调用；非有效值时忽略）
     */
    public void bindMsysPgid(long msysPgid) {
        if (msysPgid > 0) {
            this.msysPgid = msysPgid;
            log.debug("[ActiveProcess] MSYS 族徽已登记, toolCallId={}, pgid={}", toolCallId, msysPgid);
        }
    }

    /**
     * 是否仍存在存活的本命令相关进程：主进程、启动追踪名单（childPids）、
     * 族徽收网名单（msysWinPids）三处任一存活即视为有活口。
     * <p>
     * 供工具 finally 收尾与活动子进程面板轮询共同使用：进程句柄级查询（不拉外部进程），
     * 秒级返回零开销；MSYS 孤儿不在 childPids 内，靠收网名单补盲。
     * </p>
     */
    public boolean hasSurvivor() {
        if (process != null && process.isAlive()) {
            return true;
        }
        if (isAnyAlive(childPids)) {
            return true;
        }
        return isAnyAlive(msysWinPids);
    }

    /**
     * PID 名单中是否存在存活进程（null 安全）
     */
    private boolean isAnyAlive(List<Long> pids) {
        if (pids == null) {
            return false;
        }
        for (Long pid : pids) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 强行杀死当前物理进程以及其名下的所有后代进程树（针对Windows平台下孤儿脱钩进程进行彻底清扫）
     */
    public void destroyForcibly() {
        // 0a. MSYS 族徽撒网（Windows + bash 入口 + 族徽有效时）：
        // 用 MSYS 自己的进程表（ps -W）按家族徽章 PGID 收网，逐个点名强杀——
        // 可达 fork 模拟导致的 PPID 断链孤儿（& 后台作业派生的任意深度孙进程），
        // 这是唯一能触达此类孤儿的通道（Job Object 对 MSYS fork 无效、PPID 链对其失明）
        if (bashEntry && msysPgid > 0) {
            try {
                List<Long> family = MsysProcessSweeper.collectFamilyWinPids(bashPath, msysPgid);
                if (!family.isEmpty()) {
                    msysWinPids = family;
                    log.debug("[ActiveProcess] MSYS 族徽收网: pgid={}, 捕获家族成员 {} 名: {}",
                            msysPgid, family.size(), family);
                    for (Long winPid : family) {
                        execTaskKillNoTree(winPid);
                    }
                }
            } catch (Exception e) {
                log.warn("[ActiveProcess] MSYS 族徽收网异常（降级 PPID 链路径）: {}", e.getMessage());
            }
        }

        // 0. 现场重采样后代进程：登记期的 childPids 仅覆盖启动后 3 秒追踪窗口内派生的进程，
        // 此后才 fork 的后代（如构建工具延迟拉起的 daemon）不在名单内；taskkill /T 依赖
        // 父子 PID 树遍历，对"父已死、子被系统重挂靠"的孤儿同样无法触达。
        // 因此清扫前必须重新枚举一遍存活进程树，与登记名单合并到局部集合统一强杀
        // （不回写原 List，避免对调用方传入容器产生并发写）
        Set<Long> targets = new LinkedHashSet<>();
        if (childPids != null) {
            targets.addAll(childPids);
        }
        // Process.pid() 返回原始类型 long，恒非 null，直接使用
        long mainPid = process.pid();
        if (process.isAlive()) {
            try {
                process.toHandle().descendants().forEach(h -> targets.add(h.pid()));
            } catch (Exception ignored) {
            }
        }

        String os = System.getProperty("os.name").toLowerCase();

        // 1. 在 Windows 平台上，优先使用系统自带的强制级联强杀命令 (taskkill /F /T)
        if (os.contains("win")) {
            for (Long pid : targets) {
                execTaskKill(pid);
            }
            execTaskKill(mainPid);
        }

        // 2. 级联使用 JVM 的 ProcessHandle 再次强杀进行跨平台兜底，确保物理进程消亡
        for (Long pid : targets) {
            ProcessHandle.of(pid).ifPresent(h -> {
                try {
                    if (h.isAlive()) {
                        h.destroyForcibly();
                    }
                } catch (Exception ignored) {
                }
            });
        }

        try {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 强杀 + 延迟复查补杀（超时清扫、失控刷屏、异常兜底与面板手杀统一入口）。
     * <p>
     * 多重兜底设计：
     * <ol>
     *   <li>第一轮 {@link #destroyForcibly()}：族徽撒网 + 名单点杀 + PPID 链清扫全火力</li>
     *   <li>延迟 5 秒后 {@link #hasSurvivor()} 复查（taskkill /F 为异步语义，留出消亡时间）</li>
     *   <li>仍有活口 → 二次 destroyForcibly() 补杀（等价于用户手动再杀一次）</li>
     *   <li>二次后仍存活 → 停止自动重杀，登记保留在活动映射中——面板持续可见，
     *       用户手杀走同一路径，仍具确定性触达能力</li>
     * </ol>
     * 复查在独立虚拟线程异步执行，不阻塞调用方（工具主流程 / HTTP 请求）。
     * </p>
     */
    public void destroyForciblyAndVerify() {
        destroyForciblyAndVerify(null);
    }

    /**
     * 强杀 + 延迟复查补杀，并在进程树完全消亡后从指定的运行时映射中安全移除
     *
     * @param runtimeCtx 所属运行时上下文（可为 null）
     */
    public void destroyForciblyAndVerify(RuntimeContext runtimeCtx) {
        destroyForcibly();
        VirtualThreads.run("cmd-verify-" + (toolCallId != null ? toolCallId : "anon"), () -> {
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!hasSurvivor()) {
                log.info("[ActiveProcess] 清扫后复查通过, 进程树已全部消亡, toolCallId={}", toolCallId);
                if (runtimeCtx != null && toolCallId != null) {
                    runtimeCtx.getActiveProcesses().remove(toolCallId);
                }
                return;
            }
            log.warn("[ActiveProcess] 清扫后 5s 复查仍有存活进程，执行二次补杀, toolCallId={}", toolCallId);
            try {
                destroyForcibly();
            } catch (Exception e) {
                log.error("[ActiveProcess] 二次补杀异常, toolCallId={}", toolCallId, e);
            }
            // 二次补杀后不再自动重试：若仍有活口，登记保留（面板可见，交由用户手杀兜底）
            if (runtimeCtx != null && toolCallId != null) {
                if (!hasSurvivor()) {
                    log.info("[ActiveProcess] 二次补杀后复查通过, toolCallId={}", toolCallId);
                    runtimeCtx.getActiveProcesses().remove(toolCallId);
                } else {
                    log.warn("[ActiveProcess] 二次补杀后仍有存活孤儿，登记保留在面板供手动查杀: toolCallId={}", toolCallId);
                    runtimeCtx.getActiveProcesses().put(toolCallId, this);
                }
            }
        });
    }

    /**
     * 执行 taskkill /F /T /PID 强杀单个进程及其子孙树。
     * <p>
     * 参数以数组形式传入（不经 shell 拼接，无注入面）；输出重定向 DISCARD 丢弃，
     * 防止 taskkill 输出填满管道缓冲导致其自身阻塞悬挂；限时等待防 taskkill 卡死成新僵尸。
     * </p>
     *
     * @param pid 目标进程 ID
     */
    private void execTaskKill(long pid) {
        try {
            Process taskkill = new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // taskkill 正常毫秒级返回，3 秒上限仅作悬挂兜底，超时不再等待（进程已交给 OS 清理）
            taskkill.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    /**
     * 执行 taskkill /F /PID 纯点名强杀（不带 /T）。
     * <p>
     * MSYS 族徽收网专用：名单来自 MSYS 进程表的全量家族成员（撒网已覆盖全家族），
     * 无需再沿 Windows PPID 链递归（MSYS fork 的断链结构走 /T 反而徒劳）；
     * 且避免误伤——个别收网成员若恰好是 MSYS 表内共享进程（如本 JVM 外的其他 bash 家族
     * 不会命中同族徽），点名杀即精准。输出 DISCARD + 限时等待语义同 {@link #execTaskKill}。
     * </p>
     *
     * @param pid 目标进程 ID
     */
    private void execTaskKillNoTree(long pid) {
        try {
            Process taskkill = new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(pid))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            taskkill.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    public void close() {
        // 正常完成路径的关闭语义：进程树已终结（前台命令走到这里时通常已退出），
        // 直接按清扫路径收尾做最后确认（无存活成员时各手段均为空操作，零副作用）
        destroyForcibly();
    }
}
