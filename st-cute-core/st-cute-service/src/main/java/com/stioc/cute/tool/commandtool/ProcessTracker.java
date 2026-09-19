package com.stioc.cute.tool.commandtool;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.platform.common.VirtualThreads;
import com.stioc.cute.runtime.loop.RuntimeContext;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 物理子进程与后代生命周期追踪器。
 * <p>
 * 职责收敛：
 * <ul>
 *   <li>会话级别物理进程生命周期登记与注册</li>
 *   <li>启动首期瞬间快照捕获（100ms 窗口）</li>
 *   <li>异步增量安全追踪（前 3 秒动态补录）</li>
 *   <li>MSYS 族徽异步反查登记</li>
 *   <li>跨平台进程树级联强杀（含 PPID 链与 MSYS 族徽通道）</li>
 *   <li>命令执行收尾时的注销与孤儿存活保留</li>
 * </ul>
 * </p>
 */
@Slf4j
public final class ProcessTracker {

    private ProcessTracker() {
    }

    /**
     * 注册并启动对物理子进程及其后代的生命周期追踪。
     *
     * @param process              主进程实例
     * @param toolCallId           本次工具调用 ID
     * @param agentContext         智能体上下文
     * @param finalCommand         最终执行的命令
     * @param dir                  工作目录
     * @param bashEntry            是否为 bash 入口
     * @param bashPathForRegistry  用于 MSYS 族徽反查的 bash.exe 绝对路径
     * @return 注册的 ActiveProcess 实例（未注册返回 null）
     */
    public static ActiveProcess registerAndTrack(Process process, String toolCallId, AgentContext agentContext,
                                                String finalCommand, File dir, boolean bashEntry, String bashPathForRegistry) {
        if (toolCallId == null || agentContext == null) {
            return null;
        }

        List<Long> childPids = new CopyOnWriteArrayList<>();
        String os = System.getProperty("os.name").toLowerCase();
        boolean isWinBash = bashEntry && os.contains("win");

        ActiveProcess activeProcess = new ActiveProcess(
                childPids,
                agentContext.getCid(),
                toolCallId,
                process,
                finalCommand,
                dir.getAbsolutePath(),
                System.currentTimeMillis(),
                isWinBash,
                bashPathForRegistry
        );

        RuntimeContext runtimeCtx = agentContext.extra(RuntimeContext.class);
        if (runtimeCtx != null) {
            runtimeCtx.getActiveProcesses().put(toolCallId, activeProcess);
        }

        // 1. 首期瞬间捕获快照（最大 100ms 窗口，采用 20ms 短轮询：一旦捕获到首代子进程立即继续，兼顾快命令速度与追踪确定性）
        try {
            for (int poll = 0; poll < 5; poll++) {
                Thread.sleep(20);
                process.toHandle().descendants().forEach(h -> {
                    long pid = h.pid();
                    if (!childPids.contains(pid)) {
                        childPids.add(pid);
                    }
                });
                if (!childPids.isEmpty() || !process.isAlive()) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }

        // 1.1 首期快照捕获后立即尝试族徽登记（若首代子进程已出生，立即绑定，无需等 3 秒）
        if (bashPathForRegistry != null && !childPids.isEmpty()) {
            try {
                long pgid = MsysProcessSweeper.snapshotFamilyPgid(
                        bashPathForRegistry, Set.copyOf(childPids), process.pid());
                activeProcess.bindMsysPgid(pgid);
            } catch (Exception e) {
                log.debug("[MSYS反查] 启动期首发族徽登记异常（不影响命令执行）: {}", e.getMessage());
            }
        }

        log.info("[后台进程注册] 成功启动物理主进程, PID={}, bashEntry={}, 初始捕获后代数={}, PIDs={}",
                process.pid(), bashEntry, childPids.size(), childPids);

        // 2. 异步增量安全追踪：在服务启动前 3 秒内，每隔 500ms 动态补录新诞生的后代进程（例如 node.exe）
        VirtualThreads.run("cmd-track-" + toolCallId, () -> {
            for (int i = 0; i < 6; i++) {
                try {
                    Thread.sleep(500);
                    if (process.isAlive()) {
                        process.toHandle().descendants().forEach(h -> {
                            long pid = h.pid();
                            if (!childPids.contains(pid)) {
                                childPids.add(pid);
                                log.info("[后代进程动态捕获 - {}] 捕获到新生的后代工作进程 PID: {}", toolCallId, pid);
                            }
                        });
                        // 若族徽尚未登记成功，随新捕获后代立即补登
                        if (bashPathForRegistry != null && activeProcess.getMsysPgid() == 0 && !childPids.isEmpty()) {
                            try {
                                long pgid = MsysProcessSweeper.snapshotFamilyPgid(
                                        bashPathForRegistry, Set.copyOf(childPids), process.pid());
                                activeProcess.bindMsysPgid(pgid);
                            } catch (Exception ignored) {
                            }
                        }
                    } else {
                        break;
                    }
                } catch (Exception ignored) {
                }
            }

            // 3. 兜底终检 MSYS 族徽登记（若前序均未命中且仍有后代记录时最后尝试一次）：
            if (bashPathForRegistry != null && activeProcess.getMsysPgid() == 0 && !childPids.isEmpty()) {
                try {
                    long pgid = MsysProcessSweeper.snapshotFamilyPgid(
                            bashPathForRegistry, Set.copyOf(childPids), process.pid());
                    activeProcess.bindMsysPgid(pgid);
                } catch (Exception e) {
                    log.debug("[MSYS反查] 族徽登记异步执行异常（不影响命令执行）: {}", e.getMessage());
                }
            }
        });

        return activeProcess;
    }

    /**
     * 级联强杀整棵进程树：优先复用运行时上下文中已登记的 {@link ActiveProcess}，
     * 无登记时现场捕获后代 PID 构造临时实例兜底。
     */
    public static void cascadeKillProcessTree(AgentContext agentContext, String toolCallId, Process process) {
        ActiveProcess registered = null;
        RuntimeContext runtimeCtx = null;
        if (agentContext != null && toolCallId != null) {
            runtimeCtx = agentContext.extra(RuntimeContext.class);
            if (runtimeCtx != null) {
                registered = runtimeCtx.getActiveProcesses().get(toolCallId);
            }
        }
        if (registered != null) {
            registered.destroyForciblyAndVerify(runtimeCtx);
            return;
        }
        // 无登记兜底：现场捕获后代进程快照构造临时实例执行级联清扫
        try {
            List<Long> descendants = new CopyOnWriteArrayList<>();
            process.toHandle().descendants().forEach(h -> descendants.add(h.pid()));
            new ActiveProcess(descendants, null, toolCallId, process, "", "",
                    System.currentTimeMillis()).destroyForciblyAndVerify(runtimeCtx);
        } catch (Exception e) {
            // 兜底路径失败时退回仅杀主进程，至少保证主进程退出
            process.destroyForcibly();
        }
    }

    /**
     * 命令执行完毕后的资源与上下文收尾。
     */
    public static void cleanupOnFinish(AgentContext agentContext, String toolCallId, boolean runInBackground) {
        if (runInBackground || toolCallId == null || agentContext == null) {
            return;
        }
        RuntimeContext runtimeCtx = agentContext.extra(RuntimeContext.class);
        if (runtimeCtx == null) {
            return;
        }
        ActiveProcess finished = runtimeCtx.getActiveProcesses().remove(toolCallId);
        if (finished != null && finished.hasSurvivor()) {
            runtimeCtx.getActiveProcesses().put(toolCallId, finished);
            log.warn("[进程收尾] 命令结束但仍有存活进程，登记保留（面板可见，等待懒清理/手杀）, toolCallId={}, command={}",
                    toolCallId, finished.getCommand());
            finished.close();
        } else if (finished != null) {
            finished.close();
        }
    }
}
