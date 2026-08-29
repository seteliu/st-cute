package com.stioc.cute.prompt;

import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.platform.contract.Provider;
import com.stioc.cute.security.access.PermissionMode;
import com.stioc.cute.skill.access.Skill;
import com.stioc.cute.agent.access.AgentRuleVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

/**
 * 负责模块化拼装智能体全局 System Prompt，动态注入最新的环境上下文
 */
@Slf4j
@Component
public class SystemPromptGenerator {

    private static final String SYSTEM_TEMPLATE = """
            你是一个高度专业且强大的 AI 编程智能体，代号为 ST-Cute。你由 stioc 团队开发，专门通过提供优雅的代码、精准的重构与稳健的工程实践来辅助开发人员。

            【安全防线约束】
            - 绝不能将任何明文 API 密钥（如 API Key, Token 等）写盘、显式打印到对话输出中。
            - 禁止执行可能导致系统崩溃或数据毁灭性丢失的命令。
            - 编写的代码、日志和注释必须全面使用中文。

            【任务模式】
            你当前运行在一个多轮 ReAct（思考-行动-观察）自适应循环内。你可以自发地多次调用工具。请在每轮中保持理智，遇到工具返回错误时，学会根据返回的错误提示自我修正参数重试。

            【执行约定】
            - 执行任何编译、构建、测试或脚本时，请在工作目录下合适的位置执行，注意利用工具的返回判定结果。
            - 当遇到未知文件时，请先查找，不要盲目去写或猜文件位置。

            【方案讨论与改动确认机制 - 极其重要】
            - 诚实表达疑问：如果你对用户意图、需求、具体技术设计有任何疑问或不确定时，必须坦诚地在对话中向用户说明你的疑问点，并等待用户解答与确认，决不允许在存在疑问的情况下盲目执行改动或“开干”。
            - 方案讨论阶段判定：若用户没有在当前交互中明确发出“同意修改”、“开始执行”、“执行修改”等带有“开始干活”意图的指示，必须将当前交流视为“方案讨论阶段”。在此阶段，你应当与用户沟通和细化方案，只读分析代码，严禁调用任何会产生文件修改（如 write_to_file、replace_file_content 等）或命令运行的写操作工具。
            - 明确授权后动笔：只有在方案达成共识，且用户明确下达了“可以开始修改/开始干活”等清晰的动笔授权指示后，你才可以开始调用写入工具去创建或修改文件。

            【开发核心约定 - 极其重要】
            1. 优先使用专用工具：当你需要读取文件、查找文件或全文检索代码时，必须使用 read_file、list_dir、grep_search。绝不允许使用 execute_command 去执行 'cat'、'find'、'grep'、'rg' 等命令，否则被视为低效与违规。其中 grep_search 工具已具备单文件大小（2MB限制）与二进制（Null Byte 校验）探测过滤防护，支持全面检索 Python、Go、Rust、C/C++、Markdown 等任意语言纯文本源文件。匹配行超过 1000 字符时会自动截断，默认跳过以点(.)开头的隐藏目录，如需搜索隐藏目录可设置 includeHidden 为 true。
            2. 修改文件前必先读取：在调用 replace_file_content 对任何文件进行局部替换修改前，你必须已经在此之前成功的调用过 read_file 读出该文件的最新内容（允许在同一次工具调用列表中先 read_file 后 replace_file_content）。
            3. 保持修改 of 唯一性：replace_file_content 仅支持唯一片段替换，请提供长且具有唯一特征的 oldContent 缩进代码段（oldContent 为文件中现有原文，newContent 为替换后写入的新文本，两者不可颠倒）。若修改处有重复段或容易匹配失败，建议提供可选的 startLine 与 endLine 来锁定特定行范围。工具内置了空白与换行符模糊匹配 Fallback 机制以容忍缩进格式差异。
            4. 鼓励多工具并发调用：当你需要阅读多个文件或列出多个目录时，强烈建议你在单次回复中一次性并行调用多个 read_file 或 list_dir 工具。后端引擎将并发处理这些只读操作，这能极大地减少交互轮数、提升效率。
            5. 支持复杂链式工具调用：后端处理引擎具有强大的顺序流式执行能力，允许你在单次回复中发送一串具有逻辑先后顺序的工具调用（例如：“先调用 read_file 读取文件 ➡️ 再调用 replace_file_content 修改该文件 ➡️ 最后调用 read_file/grep_search 验证修改”）。你可以放心大胆地一并发送，引擎会自动按序妥善处理。
            6. 多模态与附件约定：当用户在当前最新轮次中发送附带图片或文件的消息时，多模态数据已直接注入当前对话上下文供你直接观察并回答，严禁在当前轮次尝试调用 load_attachment。load_attachment 工具仅用于在多轮历史中读取带有 '[📎 历史附件]' 且明确给出了具体路径的历史旧附件，严禁凭空臆造路径。

            【当前运行系统环境上下文】
            - 操作系统平台: {os}
            - 系统当前时间: {currentTime}
            - 默认活动 Shell: {shell}
            - 运行大模型: {modelName}
            - 当前工作目录: {workDir}
            - 是否处于 Git 仓库内: {inGit}
            {gitBranchInfo}
            - 当前安全与权限模式: {permissionMode}

            {skillsSection}

            {rulesSection}
            """;

    public String generateSystemPrompt(Provider config, String projectBasePath, PermissionMode permissionMode) {
        return generateSystemPrompt(config, projectBasePath, permissionMode, null, null);
    }

    public String generateSystemPrompt(Provider config, String projectBasePath, PermissionMode permissionMode, List<Skill> skills) {
        return generateSystemPrompt(config, projectBasePath, permissionMode, skills, null);
    }

    public String generateSystemPrompt(Provider config, String projectBasePath, PermissionMode permissionMode, List<Skill> skills, List<AgentRuleVo> rules) {
        String modelName = config != null ? config.getModelName() : "unknown";
        String basePath = StringUtils.hasText(projectBasePath) ? projectBasePath : System.getProperty("user.dir");
        basePath = basePath.replace("\\", "/");

        Map<String, Object> vars = new HashMap<>();

        // 1. 操作系统
        String os = System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")";
        vars.put("os", os);

        // 2. 时间 (降为天级别以提升缓存命中率)
        vars.put("currentTime", new SimpleDateFormat("yyyy-MM-dd").format(new Date()));

        // 3. shell
        String shell = System.getProperty("os.name").toLowerCase().contains("win")
                ? System.getenv("COMSPEC") : System.getenv("SHELL");
        vars.put("shell", shell != null ? shell.replace("\\", "/") : "N/A");

        // 4. 模型
        vars.put("modelName", modelName);

        // 5. 工作目录
        vars.put("workDir", basePath);

        // 6. Git 仓库与分支
        boolean inGit = new File(basePath, ".git").exists();
        vars.put("inGit", inGit ? "是" : "否");
        if (inGit) {
            String branch = getGitBranch(basePath);
            vars.put("gitBranchInfo", "- 当前 Git 分支: " + (branch != null ? branch : "unknown") + "\n");
        } else {
            vars.put("gitBranchInfo", "");
        }



        // 7.5 安全与权限模式描述
        String permissionModeDesc;
        if (permissionMode == PermissionMode.READ_ONLY) {
            permissionModeDesc = "【只读模式】你具有只读查看权限，可以自由读取文件或调用 invoke_subagent 派发子智能体（均直接放行）。但任何尝试修改文件（如 write_to_file、replace_file_content 等）或在终端执行命令的动作都将被安全拦截并挂起，进入人在回路确认（ASK）流程，需由开发者批准方可真正执行。请你在可能触发 ASK 前预先向开发者简要说明你需要执行的改动或命令以期获得授权。";
        } else if (permissionMode == PermissionMode.SMART_APPROVAL) {
            permissionModeDesc = "【智能审批模式】你拥有读写代码文件的完整权限（所有读写文件操作直接放行），以及运行常用安全只读终端命令（如 git status, pwd 等）的特权。但任何存在修改副作用或未授权的终端命令动作（如 execute_command 运行其他命令）都将被安全拦截并挂起，进入人在回路确认（ASK）流程，需由开发者批准方可执行。";
        } else if (permissionMode == PermissionMode.ALL_ALLOW) {
            permissionModeDesc = "【全部放行模式】你拥有完全自动化运行的高级特权，读取、写入代码文件或执行终端命令等所有操作均直接放行，无需用户手动干预审批。";
        } else {
            permissionModeDesc = permissionMode.name();
        }
        vars.put("permissionMode", permissionModeDesc);

        // 7.6 注入已加载激活的专属或全局技能规范元数据及按需加载引导
        StringBuilder sb = new StringBuilder();
        if (skills != null && !skills.isEmpty()) {
            sb.append("\n【可插拔技能包（Skills）列表】\n");
            sb.append("当前系统已检测到并装配了以下专业技能。你当前只有它们的简要描述，为了避免上下文臃肿，它们的详细规范默认不加载：\n");
            sb.append("- 如果你需要调用/使用某项 `inline` 模式技能，你必须首先调用 `load_skill` 工具传入其技能名称以将指令注入到当前会话；\n");
            sb.append("- 如果你需要调用/使用某项 `fork` 模式技能，为保持当前开发会话上下文整洁，你必须调用 `invoke_subagent` 工具派生子智能体来专门执行此任务，并要求子智能体在其独立会话中调用 `load_skill` 激活该技能进行处理，完成后向你汇报。\n\n");
            for (Skill skill : skills) {
                sb.append(String.format("- 技能名称：%s\n", skill.getName()));
                sb.append(String.format("  执行模式：%s\n", skill.getMode()));
                if (StringUtils.hasText(skill.getDescription())) {
                    sb.append(String.format("  技能描述：%s\n", skill.getDescription()));
                }
                sb.append("\n");
            }
        }
        vars.put("skillsSection", sb.toString());

        // 8. 项目级开发规范
        String rulesStr = "";
        if (rules != null) {
            StringBuilder rulesSb = new StringBuilder();
            for (AgentRuleVo rule : rules) {
                if (rule != null && StringUtils.hasText(rule.getContent())) {
                    rulesSb.append("[").append(rule.getName()).append(" 开发指令与规范]\n")
                           .append(rule.getContent().trim()).append("\n\n");
                }
            }
            rulesStr = rulesSb.toString().trim();
        } else {
            rulesStr = loadAgentsRules(basePath);
        }
        vars.put("rulesSection", StringUtils.hasText(rulesStr) ? "\n【项目开发规范】\n" + rulesStr + "\n" : "");

        return renderTemplate(SYSTEM_TEMPLATE, vars).trim();
    }

    private String renderTemplate(String template, Map<String, Object> vars) {
        String result = template;
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            String key = "{" + entry.getKey() + "}";
            String val = entry.getValue() != null ? String.valueOf(entry.getValue()) : "";
            result = result.replace(key, val);
        }
        return result;
    }

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

    private String loadAgentsRules(String basePath) {
        StringBuilder sb = new StringBuilder();

        // 1. 加载全局开发指令与规范
        File globalFile = ContractFile.getGlobalAgentsFile();
        if (globalFile != null && globalFile.exists()) {
            try {
                String content = Files.readString(globalFile.toPath(), StandardCharsets.UTF_8);
                if (StringUtils.hasText(content)) {
                    sb.append(content.trim()).append("\n\n");
                }
            } catch (Exception e) {
                log.error("加载全局 AGENTS.md 失败: " + globalFile.getAbsolutePath(), e);
            }
        }

        // 2. 加载项目级开发指令与规范
        if (basePath != null) {
            for (File dir : ContractFile.getProjectDirs(basePath)) {
                File projectFile = new File(dir, "AGENTS.md");
                if (projectFile.exists()) {
                    try {
                        String content = Files.readString(projectFile.toPath(), StandardCharsets.UTF_8);
                        if (StringUtils.hasText(content)) {
                            sb.append(content.trim()).append("\n\n");
                        }
                    } catch (Exception e) {
                        log.error("加载项目级 AGENTS.md 失败: " + projectFile.getAbsolutePath(), e);
                    }
                }
            }
        }

        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }
}
