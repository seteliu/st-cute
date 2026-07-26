package com.stioc.cute.agent.access;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

/**
 * 封装会话运行中拉起的活动外部物理进程元数据
 */
@Data
@AllArgsConstructor
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
     * 强行杀死当前物理进程以及其名下的所有后代进程树（针对Windows平台下孤儿脱钩进程进行彻底清扫）
     */
    public void destroyForcibly() {
        Long mainPid = process.pid();
        String os = System.getProperty("os.name").toLowerCase();

        // 1. 在 Windows 平台上，优先使用系统自带的强制级联强杀命令 (taskkill /F /T)
        if (os.contains("win")) {
            if (childPids != null) {
                childPids.forEach(childPid -> {
                    try {
                        Runtime.getRuntime().exec("taskkill /F /T /PID " + childPid);
                    } catch (Exception ignored) {}
                });
            }
            if (mainPid != null) {
                try {
                    Runtime.getRuntime().exec("taskkill /F /T /PID " + mainPid);
                } catch (Exception ignored) {}
            }
        }

        // 2. 级联使用 JVM 的 ProcessHandle 再次强杀进行跨平台兜底，确保物理进程消亡
        if (childPids != null) {
            childPids.forEach(childPid -> {
                ProcessHandle.of(childPid).ifPresent(h -> {
                    try {
                        if (h.isAlive()) {
                            h.destroyForcibly();
                        }
                    } catch (Exception ignored) {}
                });
            });
        }

        try {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {}
    }
}
