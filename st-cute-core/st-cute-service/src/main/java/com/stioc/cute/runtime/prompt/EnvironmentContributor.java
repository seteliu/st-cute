package com.stioc.cute.runtime.prompt;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.project.ProjectService;
import com.stioc.cute.engine.llm.ProviderResolver;
import com.stioc.cute.engine.llm.types.Provider;
import com.stioc.cute.engine.prompt.SystemPromptContributor;
import com.stioc.cute.permission.types.PermissionMode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Coding 环境贡献者：工作目录、Git 分支、模型名、权限模式等平台环境段。
 * <p>（内容迁自原 SystemPromptGenerator 的环境上下文变量装配逻辑，行为等价；
 * 系统属性级环境已由引擎 DefaultEnvPromptContributor 提供）</p>
 */
@Slf4j
@Component
public class EnvironmentContributor implements SystemPromptContributor {

    @Resource
    private ProviderResolver providerResolver;
    @Resource
    private ProjectService projectService;

    @Override
    public int order() {
        return 60;
    }

    @Override
    public String contribute(AgentContext context) {
        StringBuilder sb = new StringBuilder();

        // 1. 默认活动 Shell
        String shell = System.getProperty("os.name").toLowerCase().contains("win")
                ? System.getenv("COMSPEC") : System.getenv("SHELL");
        sb.append("- 默认活动 Shell: ").append(shell != null ? shell.replace("\\", "/") : "N/A").append("\n");

        // 2. 运行大模型
        try {
            Provider config = providerResolver.getProviderConfigForContext(context);
            sb.append("- 运行大模型: ").append(config != null ? config.getModelName() : "unknown").append("\n");
        } catch (Exception e) {
            sb.append("- 运行大模型: unknown\n");
        }

        // 3. 工作目录（项目根 / worktree 优先）
        String basePath = resolveProjectBasePath(context);
        sb.append("- 当前工作目录: ").append(basePath.replace("\\", "/")).append("\n");

        // 4. Git 仓库与分支
        boolean inGit = new File(basePath, ".git").exists();
        sb.append("- 是否处于 Git 仓库内: ").append(inGit ? "是" : "否").append("\n");
        if (inGit) {
            String branch = getGitBranch(basePath);
            sb.append("- 当前 Git 分支: ").append(branch != null ? branch : "unknown").append("\n");
        }

        // 5. 权限模式说明
        sb.append("- 当前安全与权限模式: ").append(describePermissionMode(context.getPermissionMode())).append("\n");

        // 6. 会话 ID 与用户级目录（供大模型定位附件、全局配置，并约定临时目录落点）
        String globalDirPath = ContractFile.getGlobalDir().getAbsolutePath().replace("\\", "/");
        sb.append("- 当前会话 ID (cid): ").append(context.getCid()).append("\n");
        sb.append("- 本工具用户级目录: ").append(globalDirPath)
                .append("（附件存储、全局配置、日志等均在此目录下）\n");
        sb.append("- 临时目录约定: 需要临时文件/目录时，若用户未明确指定位置，请统一在 ")
                .append(globalDirPath).append("/tmp/cid_").append(context.getCid()).append("/ 下创建\n");

        // 7. Git Bash 可用性指引：仅 Windows 且实际探测到 bash.exe 时注入（Unix 环境原生 Shell 即是，无需此条）
        String gitBashPath = detectGitBashPathCached();
        if (gitBashPath != null) {
            sb.append("- Git Bash 可用: ").append(gitBashPath)
                    .append("（当需要 Unix 风格命令/管道，或默认 Shell 中文输出乱码且 encoding 调整无效时，")
                    .append("可显式调用该 bash 执行，如 \"").append(gitBashPath)
                    .append("\" -c \"命令\"，其工具链统一 UTF-8 输出）\n");
        }

        return sb.toString();
    }

    /**
     * 解析当前工作基准目录：经 ProjectService 获取，
     * 兜底全局工作目录
     */
    private String resolveProjectBasePath(AgentContext context) {
        if (projectService != null) {
            return projectService.getProjectBasePath(context);
        }
        return ContractFile.getGlobalDir().getAbsolutePath();
    }

    /** Git Bash 探测结果缓存（null=未初始化，空串=探测失败已确认不存在，正常值=bash.exe 绝对路径） */
    private volatile String gitBashPathCache = null;

    /**
     * 探测 Git Bash 的 bash.exe 绝对路径（带缓存，JVM 内仅探测一次，失败也会缓存避免重复开销）。
     * <p>
     * 仅 Windows 环境探测，非 Windows 直接返回 null（Unix 环境原生 Shell 即 bash，无需指引）。
     * 探测优先级：GitForWindows 注册表安装路径 → where git 推导 → 常见安装位置兜底，
     * 覆盖官方安装器、绿色版与自定义安装目录（如 D:\Programming\Git）等各种形态。
     * </p>
     *
     * @return bash.exe 绝对路径（正斜杠形式）；未安装或非 Windows 返回 null
     */
    private String detectGitBashPathCached() {
        // 缓存命中：非空串直接返回（空串代表此前已探测且确认不存在）
        String cached = gitBashPathCache;
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        // 双重检查锁：探测涉及外部进程调用，仅首个并发请求执行
        synchronized (this) {
            if (gitBashPathCache != null) {
                return gitBashPathCache.isEmpty() ? null : gitBashPathCache;
            }
            String result = detectGitBashPath();
            // 缓存三态化：null（不存在）以空串占位，避免每次装配提示词都重复探测
            gitBashPathCache = result != null ? result : "";
            return result;
        }
    }

    /**
     * 执行实际的 Git Bash 探测：注册表 → where git 推导 → 常见位置三级策略
     */
    private String detectGitBashPath() {
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

    /**
     * 权限模式的人类可读描述（内容迁自原 SystemPromptGenerator，行为等价）
     */
    private String describePermissionMode(String mode) {
        if (mode == null) {
            return "未知（默认只读保护）";
        }
        PermissionMode permissionMode = PermissionMode.fromName(mode);
        return switch (permissionMode) {
            case READ_ONLY -> """
                    【只读模式】你具有只读查看权限，可以自由读取文件或调用 invoke_subagent 派发子智能体（均直接放行）。但任何尝试修改文件（如 write_to_file、replace_file_content 等）或在终端执行命令的动作都将被安全拦截并挂起，进入人在回路确认（ASK）流程，需由开发者批准方可真正执行。请你在可能触发 ASK 前预先向开发者简要说明你需要执行的改动或命令以期获得授权。""";
            case SMART_APPROVAL -> """
                    【智能审批模式】你拥有读写代码文件的完整权限（所有读写文件操作直接放行），以及运行常用安全只读终端命令（如 git status, pwd 等）的特权。但任何存在修改副作用或未授权的终端命令动作（如 execute_command 运行其他命令）都将被安全拦截并挂起，进入人在回路确认（ASK）流程，需由开发者批准后方可执行。""";
            case ALL_ALLOW -> """
                    【全部放行模式】你拥有完全自动化运行的高级特权，读取、写入代码文件或执行终端命令等所有操作均直接放行，无需用户手动干预审批。""";
        };
    }

    /**
     * 读取指定目录的当前 Git 分支名（限时 500ms，失败返回 null）
     */
    private String getGitBranch(String basePath) {
        try {
            Process process = Runtime.getRuntime().exec(
                    new String[]{"git", "rev-parse", "--abbrev-ref", "HEAD"},
                    null,
                    new File(basePath)
            );
            if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
                if (process.exitValue() == 0) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line = reader.readLine();
                        if (line != null) {
                            return line.trim();
                        }
                    }
                }
            } else {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}
