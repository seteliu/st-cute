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
        sb.append("- 当前安全与权限模式: ").append(describePermissionMode(context.getPermissionMode()));

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
