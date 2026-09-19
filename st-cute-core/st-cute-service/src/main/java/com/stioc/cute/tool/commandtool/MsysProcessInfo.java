package com.stioc.cute.tool.commandtool;

/**
 * MSYS 进程表单行解析结构。
 *
 * @param msysPid  MSYS 进程 ID
 * @param msysPpid MSYS 父进程 ID
 * @param pgid     家族进程组 ID（族徽）
 * @param winPid   Windows 物理进程 ID
 */
public record MsysProcessInfo(long msysPid, long msysPpid, long pgid, long winPid) {
}
