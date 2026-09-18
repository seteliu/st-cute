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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Coding 环境贡献者：操作系统、工作目录、Git 分支、模型名、权限模式等环境信息段。
 * 排序在最后，后置可最大化稳定前缀的缓存命中范围。
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
        return 300;
    }

    @Override
    public String contribute(AgentContext context) {
        StringBuilder sb = new StringBuilder();

        // 段落标题：环境信息统一收敛于本段（引擎已零默认内容）
        sb.append("【环境信息】\n");

        // 条目排序原则：越容易因会话变化的越靠后（机器级 → 配置级 → 会话级 → 日变级 → 必变 cid）。
        // 提示词前缀缓存按 token 严格匹配，分叉点越靠后，跨会话可命中的稳定前缀越长。
        // 注释刻意不带序号：条目会随版本增删，编号是纯维护负担，靠上述原则表达次序语义即可

        // 操作系统平台（机器级，跨会话不变；自原引擎 DefaultEnvPromptContributor 并入）
        sb.append("- 操作系统平台: ").append(System.getProperty("os.name"))
                .append(" (").append(System.getProperty("os.arch")).append(")\n");

        // 用户级目录（用户机器级，跨会话不变；供大模型定位附件、全局配置与日志）
        String globalDirPath = ContractFile.getGlobalDir().getAbsolutePath().replace("\\", "/");
        sb.append("- 本工具用户级目录: ").append(globalDirPath)
                .append("（附件存储、全局配置、日志等均在此目录下）\n");

        // 运行大模型（配置级，用户切换模型才变）
        try {
            Provider config = providerResolver.getProviderConfigForContext(context);
            sb.append("- 运行大模型: ").append(config != null ? config.getModelName() : "unknown").append("\n");
        } catch (Exception e) {
            sb.append("- 运行大模型: unknown\n");
        }

        // 工作目录（项目根目录；会话绑定 workspace，换项目才变）
        String basePath = resolveProjectBasePath(context);
        sb.append("- 当前工作目录: ").append(basePath.replace("\\", "/")).append("\n");

        // Git 仓库与分支（跟随工作目录；分支在目录内仍可被切换，比目录本身更易变）
        boolean inGit = new File(basePath, ".git").exists();
        sb.append("- 是否处于 Git 仓库内: ").append(inGit ? "是" : "否").append("\n");
        if (inGit) {
            String branch = getGitBranch(basePath);
            sb.append("- 当前 Git 分支: ").append(branch != null ? branch : "unknown").append("\n");
        }

        // 权限模式说明（会话级设置，不同会话可能不同）
        sb.append("- 当前安全与权限模式: ").append(describePermissionMode(context.getPermissionMode())).append("\n");

        // 会话 ID（每个新会话必然变化——跨会话提示词前缀缓存的分叉点）
        sb.append("- 当前会话 ID (cid): ").append(context.getCid()).append("\n");

        // 临时目录约定（含 cid，必然变化，置于最末）
        sb.append("- 临时目录约定: 需要临时文件/目录时，若用户未明确指定位置，请统一在 ")
                .append(globalDirPath).append("/tmp/cid_").append(context.getCid()).append("/ 下创建\n");

        // 时间信息
        sb.append("- 每条用户消息末尾都会带创建时的[消息时间]，由Agent自动生成，非用户原始信息");

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
                    【只读模式】你具有只读查看权限，可以自由读取文件或调用 invoke_subagent 派发子智能体（均直接放行）。但任何尝试修改文件（如 write_file、edit_file 等）或在终端执行命令的动作都将被安全拦截并挂起，进入人在回路确认（ASK）流程，需由开发者批准方可真正执行。请你在可能触发 ASK 前预先向开发者简要说明你需要执行的改动或命令以期获得授权。""";
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
