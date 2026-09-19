package com.stioc.cute.tool.commandtool;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * MSYS 进程表反查清扫器：用 MSYS 自己的账本（{@code ps -W} 进程表）攻它自己的盾。
 * <p>
 * 解决的困局：Git Bash（MSYS2）以 fork/exec 模拟实现子进程派生，桩进程退场导致
 * Windows 原生 PPID 链断链——{@code ProcessHandle.descendants()} 与
 * {@code taskkill /T} 均无法触达 {@code &} 后台作业派生的孙进程（如
 * {@code sed ... & sleep 1500} 中的 sleep），主进程死后其脱钩成孤儿，常规手段全数失明。
 * </p>
 * <p>
 * 关键机理（本机端到端实测裁定）：
 * <ul>
 *   <li>MSYS 家族在进程表中以 <b>PGID（进程组 ID）为家族徽章</b>：执行命令的 usr/bin/bash
 *   是组首（PGID=自身 PID），其 fork 的全部后代（含后台作业）同 PGID，任意深度全覆盖</li>
 *   <li>家族孤儿化（组首死亡、后代被 MSYS init 接管 PPID=1）后 <b>PGID 依然保留</b>——
 *   族徽比 PPID 链坚挺，是唯一跨孤儿化存活的家族标记</li>
 *   <li>主进程 bash.exe 是 MSYS 命名空间外的"外来者"（表内行 PID/PPID/PGID 全 0），
 *   不能作族徽锚点；真正的组首须经主进程的 Windows PID 反查表行间接定位</li>
 * </ul>
 * </p>
 * <p>
 * 使用方式（两阶段）：
 * <ol>
 *   <li><b>族徽登记</b>（命令启动期异步调用）：{@link #snapshotFamilyPgid(String, Set, long)}——
 *   以主进程已知后代 Windows PID 为锚，在进程表中定位家族成员行，读取其 PGID 存入
 *   {@link ActiveProcess}。族徽终生不变，后续任意时刻 fork 的后代自动同组，登记一次即够</li>
 *   <li><b>族徽收网</b>（超时清扫时调用）：{@link #collectFamilyWinPids(String, long)}——
 *   重拉进程表，收集全部 PGID 等于族徽的行的 WINPID，交由调用方逐个
 *   {@code taskkill /F /PID} 点名强杀（不依赖任何进程链）</li>
 * </ol>
 * </p>
 * <p>
 * ps 子进程由 JVM 经 ProcessBuilder 数组传参直启（CreateProcess，不经任何 shell 转手），
 * 限时 2 秒防挂；仅 Windows 且已探测到 Git Bash 时有效，其余环境一律空结果降级。
 * </p>
 */
@Slf4j
public final class MsysProcessSweeper {

    /**
     * ps 子进程的执行与输出读取总时限（毫秒）：正常百毫秒级完成，超时 destroyForcibly 兜底
     */
    private static final long PS_TIMEOUT_MS = 2_000L;

    private MsysProcessSweeper() {
    }

    /**
     * 推导 ps.exe 绝对路径：Git Bash 官方目录布局固定（{@code <Git>/bin/bash.exe} 与
     * {@code <Git>/usr/bin/ps.exe} 同根），由已探测的 bash 路径反推
     *
     * @param bashPath Git Bash 的 bash.exe 绝对路径（正斜杠形式）
     * @return ps.exe 绝对路径；推导失败或文件不存在返回 null
     */
    static String derivePsPath(String bashPath) {
        if (bashPath == null || bashPath.isBlank()) {
            return null;
        }
        // D:/x/Git/bin/bash.exe → D:/x/Git → D:/x/Git/usr/bin/ps.exe
        File binDir = new File(bashPath).getParentFile();
        if (binDir == null) {
            return null;
        }
        File psFile = new File(binDir.getParentFile(), "usr/bin/ps.exe");
        return psFile.isFile() ? psFile.getAbsolutePath() : null;
    }

    /**
     * 执行 {@code ps -W} 并解析为结构化行列表。
     * <p>
     * 输出格式（Git Bash ps 实测）：表头 {@code PID PPID PGID WINPID TTY UID STIME COMMAND}，
     * 列间多空格分隔；COMMAND 含空格，仅前 7 列参与解析。行内某列缺失或非数字时防御性跳过。
     * </p>
     * <p>
     * 解析陷阱说明：MSYS 命名空间外的 Windows 进程行（绝大多数系统进程）PID/PPID/PGID 显示
     * 为 0，WINPID 列才是真实 Windows PID，不影响按 PGID 过滤（0 不会等于有效族徽）。
     * </p>
     *
     * @param bashPath Git Bash 路径（推导 ps.exe 位置用）
     * @return 解析后的进程行列表；ps 不可用 / 执行失败 / 超时返回空列表（调用方降级）
     */
    private static List<MsysProcessInfo> runPs(String bashPath) {
        String psPath = derivePsPath(bashPath);
        if (psPath == null) {
            return Collections.emptyList();
        }
        try {
            Process ps = new ProcessBuilder(psPath, "-W")
                    .redirectErrorStream(true)
                    .start();
            List<MsysProcessInfo> rows = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(ps.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    MsysProcessInfo row = parseRow(line);
                    if (row != null) {
                        rows.add(row);
                    }
                }
            }
            // 限时兜底：读取完毕（EOF）后进程通常已退出；未退出即强杀防挂（输出已被读完，无损失）
            if (!ps.waitFor(PS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                ps.destroyForcibly();
            }
            return rows;
        } catch (Exception e) {
            log.warn("[MSYS反查] ps -W 执行失败，本次清扫降级: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 解析单行 ps 输出为 {@link MsysProcessInfo} 强类型记录
     *
     * @return 解析失败（表头/空行/列数不足/数值非法）返回 null
     */
    private static MsysProcessInfo parseRow(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("PID")) {
            return null;
        }
        String[] cols = trimmed.split("\\s+");
        if (cols.length < 7) {
            return null;
        }
        try {
            return new MsysProcessInfo(
                    Long.parseLong(cols[0]),   // MSYS PID
                    Long.parseLong(cols[1]),   // MSYS PPID
                    Long.parseLong(cols[2]),   // PGID（家族徽章）
                    Long.parseLong(cols[3])    // WINPID（Windows 真实 PID）
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 阶段一：族徽登记。以主进程已知后代 Windows PID 为锚，在 MSYS 进程表中定位
     * 家族成员行并读取其 PGID。
     * <p>
     * 锚点即"启动追踪名单"（首期快照 + 动态补录）中属于 MSYS 家族的成员：MSYS 家族的
     * usr/bin/bash 组首与直接子进程在启动瞬间往往已出生并被 descendants 捕获；
     * MSYS fork 派生是慢路径（毫秒级），快照锚点天然先于多数孙进程存在。锚点中混入的
     * 非 MSYS 进程（如 conhost）在表中 PGID=0，不影响族徽提取。
     * </p>
     *
     * @param bashPath      Git Bash 路径
     * @param anchorWinPids 主进程后代 Windows PID 名单（启动追踪名单）
     * @param mainWinPid    主进程（bash.exe）Windows PID，同样作为锚点（见表时兜底）
     * @return 家族 PGID（族徽）；锚点无 MSYS 家族命中或 ps 失败返回 0（表示无族徽，调用方不撒网）
     */
    public static long snapshotFamilyPgid(String bashPath, Set<Long> anchorWinPids, long mainWinPid) {
        if (bashPath == null || anchorWinPids == null || anchorWinPids.isEmpty()) {
            return 0L;
        }
        List<MsysProcessInfo> rows = runPs(bashPath);
        // 锚点 PID 集合：后代名单 + 主进程自身（见表时兜底直取其 PGID）
        Map<Long, Boolean> anchors = new HashMap<>();
        for (Long pid : anchorWinPids) {
            anchors.put(pid, Boolean.TRUE);
        }
        anchors.put(mainWinPid, Boolean.TRUE);
        for (MsysProcessInfo row : rows) {
            long pgid = row.pgid();
            // 有效族徽非 0 且锚点命中：即找到家族（组首或家族任一成员均可提供族徽）
            if (pgid != 0 && anchors.containsKey(row.winPid())) {
                log.debug("[MSYS反查] 族徽登记成功: pgid={}, 锚点winpid={}, 家族当前表内规模={}",
                        pgid, row.winPid(), rows.size());
                return pgid;
            }
        }
        log.debug("[MSYS反查] 族徽登记未命中（锚点可能尚未入表或非 MSYS 家族），锚点数={}", anchors.size());
        return 0L;
    }

    /**
     * 阶段二：族徽收网。重拉 MSYS 进程表，收集全部 PGID 等于族徽的进程的 Windows PID。
     * <p>
     * 族徽覆盖该家族全部存活成员——含组首、前台子进程、任意深度的后台作业孤儿，
     * 与各成员当前的 PPID 状态无关（孤儿化不影响 PGID）。
     * </p>
     *
     * @param bashPath Git Bash 路径
     * @param pgid     族徽（必须为登记得到的有效值，0 直接空结果）
     * @return 收网名单（WINPID 列表）；无成员 / ps 失败返回空列表
     */
    public static List<Long> collectFamilyWinPids(String bashPath, long pgid) {
        if (bashPath == null || pgid <= 0) {
            return Collections.emptyList();
        }
        List<MsysProcessInfo> rows = runPs(bashPath);
        List<Long> winPids = new ArrayList<>();
        for (MsysProcessInfo row : rows) {
            if (row.pgid() == pgid) {
                winPids.add(row.winPid());
            }
        }
        return winPids;
    }
}
