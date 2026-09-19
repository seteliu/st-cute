package com.stioc.cute.tool.commandtool;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.git.GitBashLocator;
import com.stioc.cute.project.ProjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.File;

/**
 * 物理子进程启动器与环境适配器。
 * <p>
 * 职责收敛：
 * <ul>
 *   <li>Git 命令参数增强（解决 Windows 中文乱码与八进制路径）</li>
 *   <li>跨平台 Shell 入口选型（Git Bash / cmd.exe / sh）与 ProcessBuilder 配置</li>
 *   <li>多级工作目录解析（显式 cwd > 项目根目录 > JVM 工作目录）</li>
 *   <li>动态检测本机 Git Bash 支持状态以支持参数 Schema 组装</li>
 * </ul>
 * </p>
 */
@Slf4j
public final class ProcessLauncher {

    public static final String SHELL_ARG_JSON = ",\n            \"shell\": {\n"
            + "              \"type\": \"string\",\n"
            + "              \"description\": \"可选，接受 \\\"bash\\\"（默认）或 \\\"cmd\\\"。默认经本机 Git Bash（bash.exe）以纯 bash 语法执行：Unix 风格工具链与管道、统一 UTF-8 输出，路径建议用正斜杠；仅 Windows 特定操作（dir/where/reg query 等 cmd 内建命令与语法、执行 .bat/.cmd 脚本）传 \\\"cmd\\\" 经 cmd.exe 执行\",\n"
            + "              \"default\": \"bash\"\n"
            + "            }";

    private ProcessLauncher() {
    }

    /**
     * 本机是否支持 shell=bash 参数：Windows 且已探测到 Git Bash。
     */
    public static boolean isBashSupported(GitBashLocator gitBashLocator) {
        return System.getProperty("os.name").toLowerCase().contains("win")
                && gitBashLocator != null
                && gitBashLocator.detectPath() != null;
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
    public static String enhanceGitCommand(String command) {
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
     * 工作目录多级兜底解析：显式 cwd > 项目路径 > JVM 工作目录。
     */
    public static File resolveCwd(ProjectService projectService, AgentContext agentContext, String customCwd) {
        File dir = null;
        if (StringUtils.hasText(customCwd) && projectService != null) {
            dir = projectService.resolvePath(customCwd, agentContext).toFile();
        } else if (projectService != null) {
            String basePath = projectService.getProjectBasePath(agentContext);
            if (StringUtils.hasText(basePath)) {
                dir = new File(basePath).getAbsoluteFile();
            }
        }
        if (dir == null) {
            dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        }
        return dir;
    }

    /**
     * 准备进程启动规格。
     *
     * @param finalCommand   经增强后的命令行
     * @param shellVal       模型传入的 shell 参数
     * @param dir            已解析的有效工作目录
     * @param gitBashLocator Git Bash 探测器
     * @return 启动规格
     * @throws IllegalArgumentException 当 shell 参数非法或环境缺失时抛出友好提示
     */
    public static ProcessLaunchSpec prepareProcess(String finalCommand, String shellVal, File dir, GitBashLocator gitBashLocator) {
        String os = System.getProperty("os.name").toLowerCase();
        ProcessBuilder pb;
        boolean bashEntry;
        String winDefaultBash = (os.contains("win") && gitBashLocator != null) ? gitBashLocator.detectPath() : null;

        if (StringUtils.hasText(shellVal) && "bash".equalsIgnoreCase(shellVal)) {
            String bashPath = os.contains("win") ? winDefaultBash : "bash";
            if (bashPath == null) {
                throw new IllegalArgumentException("参数 'shell=\"bash\"' 不可用：未在本机探测到 Git Bash（bash.exe）。"
                        + "请安装 Git for Windows 后重试，或改传 shell=\"cmd\" 经 cmd.exe 执行。");
            }
            log.info("execute_command 指定 shell=bash，经 Git Bash 执行: {}", bashPath);
            pb = new ProcessBuilder(bashPath, "-c", finalCommand);
            bashEntry = true;
        } else if (StringUtils.hasText(shellVal) && "cmd".equalsIgnoreCase(shellVal)) {
            log.info("execute_command 指定 shell=cmd，经 cmd.exe 执行");
            pb = new ProcessBuilder("cmd.exe", "/c", finalCommand);
            bashEntry = false;
        } else if (StringUtils.hasText(shellVal)) {
            throw new IllegalArgumentException("参数 'shell' 的值 '" + shellVal + "' 不受支持，当前仅支持 shell=\"bash\""
                    + "（经 Git Bash 执行）与 shell=\"cmd\"（经 cmd.exe 执行）。不传该参数时优先经 Git Bash 执行，未探测到时使用 cmd.exe。");
        } else if (winDefaultBash != null) {
            log.info("execute_command 未指定 shell，默认经 Git Bash 执行: {}", winDefaultBash);
            pb = new ProcessBuilder(winDefaultBash, "-c", finalCommand);
            bashEntry = true;
        } else if (os.contains("win")) {
            pb = new ProcessBuilder("cmd.exe", "/c", finalCommand);
            bashEntry = false;
        } else {
            pb = new ProcessBuilder("sh", "-c", finalCommand);
            bashEntry = true;
        }

        pb.directory(dir);
        log.info("execute_command 命令子进程 Cwd 物理重定向至路径: {}", dir.getAbsolutePath());
        pb.redirectErrorStream(true);

        String bashPathForRegistry = bashEntry && os.contains("win") ? winDefaultBash : null;
        return new ProcessLaunchSpec(pb, bashEntry, bashPathForRegistry, dir);
    }
}
