package com.stioc.cute.tool.commandtool;

import java.io.File;

/**
 * 进程拉起规格定义。
 *
 * @param builder             配置就绪的 {@link ProcessBuilder} 实例
 * @param bashEntry           实际生效的 shell 是否为 bash 系入口
 * @param bashPathForRegistry 供 MSYS 族徽登记与反查使用的 bash.exe 绝对路径（非 Windows 或非 bash 为 null）
 * @param workingDir          最终生效的工作目录
 */
public record ProcessLaunchSpec(
        ProcessBuilder builder,
        boolean bashEntry,
        String bashPathForRegistry,
        File workingDir
) {
}
