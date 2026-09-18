package com.stioc.cute.git;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Git Bash 定位器：探测本机 Git Bash（bash.exe）绝对路径，供 execute_command 的 shell=bash 参数使用。
 * <p>
 * 探测逻辑自 EnvironmentContributor 迁移（行为等价）。工具 schema 动态放出 shell 参数
 * 与运行时取路径执行共用本组件，JVM 级缓存避免重复的外部进程探测开销；
 * 类注释与实际调用方保持一致：提示词层已不再注入 Git Bash 指引（能力信息随工具 schema 就近呈现）。
 * </p>
 * <p>
 * 探测优先级：GitForWindows 注册表安装路径 → where git 推导 → 常见安装位置兜底，
 * 覆盖官方安装器、绿色版与自定义安装目录（如 D:\Programming\Git）等各种形态。
 * 仅 Windows 环境探测，非 Windows 返回 null（Unix 原生 Shell 即 bash，无需定位）。
 * </p>
 */
@Slf4j
@Component
public class GitBashLocator {

    /** 探测结果缓存（null=未初始化，空串=探测失败已确认不存在，正常值=bash.exe 绝对路径） */
    private volatile String bashPathCache = null;

    /**
     * 探测 Git Bash 的 bash.exe 绝对路径（带缓存，JVM 内仅探测一次，失败也会缓存避免重复开销）。
     *
     * @return bash.exe 绝对路径（正斜杠形式）；未安装或非 Windows 返回 null
     */
    public String detectPath() {
        // 缓存命中：非空串直接返回（空串代表此前已探测且确认不存在）
        String cached = bashPathCache;
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        // 双重检查锁：探测涉及外部进程调用，仅首个并发请求执行
        synchronized (this) {
            if (bashPathCache != null) {
                return bashPathCache.isEmpty() ? null : bashPathCache;
            }
            String result = doDetect();
            // 缓存三态化：null（不存在）以空串占位，避免重复探测开销
            bashPathCache = result != null ? result : "";
            return result;
        }
    }

    /**
     * 执行实际的 Git Bash 探测：注册表 → where git 推导 → 常见位置三级策略
     */
    private String doDetect() {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return null;
        }
        // 1. GitForWindows 注册表安装路径（官方安装器写入，最权威）
        String registryPath = readRegistryGitInstallPath();
        if (registryPath != null) {
            String bash = new File(registryPath, "bin/bash.exe").getAbsolutePath();
            if (new File(bash).isFile()) {
                return bash.replace("\\", "/");
            }
        }
        // 2. where git 推导：.../cmd/git.exe → .../bin/bash.exe（Git 官方目录布局固定）
        String fromGit = probeBashBesideGitExecutable();
        if (fromGit != null) {
            return fromGit;
        }
        // 3. 常见安装位置兜底
        for (String candidate : new String[]{
                "C:/Program Files/Git", "C:/Program Files (x86)/Git", "D:/Program Files/Git"}) {
            String bash = candidate + "/bin/bash.exe";
            if (new File(bash).isFile()) {
                return bash;
            }
        }
        return null;
    }

    /**
     * 读取注册表中 GitForWindows 的安装路径（查询失败或无安装信息时返回 null）
     */
    private String readRegistryGitInstallPath() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                    "reg", "query", "HKLM\\SOFTWARE\\GitForWindows", "/ve"});
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // reg query 输出形如: 安装路径    REG_SZ    D:\Programming\Git
                    // 默认值未设置时值为"(值未设置)"（且 reg 输出为 GBK、UTF-8 读取时中文乱码），一律视为无效
                    int idx = line.indexOf("REG_SZ");
                    if (idx >= 0) {
                        String value = line.substring(idx + "REG_SZ".length()).trim();
                        if (!value.isEmpty() && !value.startsWith("(")) {
                            return value;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Git Bash 注册表探测失败，降级下一策略: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 通过 where git 定位 git.exe 并推导同目录布局下的 bash.exe（
     * 如 .../Git/cmd/git.exe → .../Git/bin/bash.exe）
     */
    private String probeBashBesideGitExecutable() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", "where git"});
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String gitExe = line.trim();
                    // 命中 .../Git/cmd/git.exe 形态才推导（.../bin/git.exe 本身已在 bin 目录，直接取同级 bash）
                    File gitFile = new File(gitExe);
                    File parent = gitFile.getParentFile();
                    if (parent == null) {
                        continue;
                    }
                    File bashFile = new File(parent, "bash.exe");
                    if (!bashFile.isFile()) {
                        bashFile = new File(parent.getParentFile(), "bin/bash.exe");
                    }
                    if (bashFile.isFile()) {
                        return bashFile.getAbsolutePath().replace("\\", "/");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Git Bash 经 where git 推导失败，降级下一策略: {}", e.getMessage());
        }
        return null;
    }
}
