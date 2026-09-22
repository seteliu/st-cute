package com.stioc.cute.tool.commandtool;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolResult;
import com.stioc.cute.git.GitBashLocator;
import com.stioc.cute.project.ProjectService;
import com.stioc.cute.tool.ToolNames;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * 在项目/工作区安全运行 shell 指令的本地核心命令工具
 */
@Slf4j
@Component
public class ExecuteCommandTool implements CuteTool {

    @Resource
    private ProjectService projectService;

    @Resource
    private GitBashLocator gitBashLocator;

    @Override
    public String getRawName() {
        return ToolNames.EXECUTE_COMMAND;
    }

    @Override
    public String getDescription() {
        return "在指定的运行目录下执行终端命令。读取、查找或搜索文件内容时应优先使用 read_file / find_files / grep_search 专用工具，它们更快且带安全防护；仅当专用工具无法解决（如工具报错、glob 语义不满足需求、需管道组合处理）时，才使用命令兜底。"
                + "验证类命令（编译/构建/测试等需依据退出码判断成败）必须单命令执行，禁止用 &、&&、|| 同行拼接多条命令（返回的 exitCode 仅代表最后一条命令，前序失败会被掩盖）。"
                + "重要语义：命令因超时被强制中止时，返回 timeout=true 不代表命令未产生副作用——落盘类操作（如 sed -i、重定向写文件）可能已部分生效，"
                + "收到超时结果后必须先用 read_file 验证目标文件的实际状态，再决定重试或修正，禁止基于\"超时=未执行\"的假设直接重发命令。";
    }

    @Override
    public String getArgumentSchema() {
        // shell 参数动态放出：仅 Windows 且已探测到 Git Bash 的机器呈现（isBashSupported 判定）。
        // Linux/mac 默认 shell 即 sh/bash 系无需该参数；不支持时不放出，避免模型调用到必然报错的参数
        String shellBlock = ProcessLauncher.isBashSupported(gitBashLocator) ? ProcessLauncher.SHELL_ARG_JSON : "";
        return """
        {
          "type": "object",
          "properties": {
            "command": {
              "type": "string",
              "description": "待运行的 shell 命令行语句。需按单条命令组织的场景请勿用 && / || 拼接（见工具描述）；命令中路径含空格或特殊字符时请用引号包裹"
            },
            "cwd": {
              "type": "string",
              "description": "命令运行的工作目录（可选，默认为当前项目根目录）。支持项目相对路径（以项目根目录为基准）或绝对路径。实际生效的工作目录会在返回结果的 cwd 字段中回显"
            },
            "encoding": {
              "type": "string",
              "description": "解码子进程输出所用的字符集（默认 auto 自动探测）。中文乱码或编码混排时可显式指定，如 utf-8、gbk。仅影响命令输出的解码，不改变任何文件落盘编码",
              "default": "auto"
            }%s,
            "idleTimeoutMs": {
              "type": "integer",
              "description": "空闲超时（毫秒）。命令持续无新输出超过该时长即判定卡死并强制中止，默认 30000；若首 token 为 mvn/gradle/npm/pnpm 等构建类命令且未显式指定，自动放宽至 90000",
              "default": 30000
            },
            "maxTimeoutMs": {
              "type": "integer",
              "description": "总运行时长上限（毫秒，默认 600000）。命令累计运行超过该时长即强制中止；如 mvn install 等长命令可适当加大",
              "default": 600000
            },
            "runInBackground": {
              "type": "boolean",
              "description": "是否在后台持续运行。当需要拉起前端服务器、后端微服务等不会主动退出的持久后台进程时，必须传入 true 以防卡死智能体工具调用流（默认 false）",
              "default": false
            }
          },
          "required": ["command"]
        }
        """.formatted(shellBlock);
    }

    @Override
    public ToolAccessLevel getAccessLevel() {
        // 敏感级：执行终端命令为高危操作，智能审批模式同样需要人工确认
        return ToolAccessLevel.SENSITIVE;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        ExecuteCommandArgs cmdArgs = ExecuteCommandArgs.from(arguments);
        String command = cmdArgs.command();
        if (command == null || command.isBlank()) {
            return ToolResult.error("参数 'command' 不能为空。");
        }

        // 1. 硬拦截：禁止使用 PowerShell cmdlet 写入文件（仅 Windows 启用）
        String psWriteReject = CommandPrechecker.checkPowerShellWrite(command);
        if (psWriteReject != null) {
            return ToolResult.error(psWriteReject);
        }

        // 2. 解析编码参数：auto = 自动探测（默认），其他值按 Java 字符集名强制指定
        String encodingVal = cmdArgs.encoding();
        Charset forcedCharset = null;
        if (!cmdArgs.isAutoEncoding()) {
            try {
                forcedCharset = Charset.forName(encodingVal);
            } catch (Exception e) {
                return ToolResult.error("参数 'encoding' 的值 '" + encodingVal + "' 不是合法字符集名 (Exception: " + e.getMessage() + ")。"
                        + "常用取值：auto（自动探测）、utf-8、gbk。");
            }
        }

        long idleTimeoutMs = CommandPrechecker.resolveIdleTimeout(command, cmdArgs.rawIdleTimeoutMs());
        long maxTimeoutMs = cmdArgs.maxTimeoutMs();
        boolean runInBackground = cmdArgs.runInBackground();

        // 3. git 命令自动增强与工作目录多级兜底物理定位
        String finalCommand = ProcessLauncher.enhanceGitCommand(command);
        if (!finalCommand.equals(command)) {
            log.debug("execute_command git 命令增强注入，实际执行: {}", finalCommand);
        }

        File dir = ProcessLauncher.resolveCwd(projectService, agentContext, cmdArgs.cwd());

        log.info("execute_command 尝试运行命令: {}, Cwd: {}, Encoding: {}, IdleTimeout: {}ms, MaxTimeout: {}ms, RunInBackground: {}",
                finalCommand, dir.getAbsolutePath(), forcedCharset != null ? forcedCharset.name() : "auto",
                idleTimeoutMs, maxTimeoutMs, runInBackground);

        // 4. 熔断保险丝：同一命令 + 同一实际工作目录 + 短时间内输出完全相同的无效重复调用检测
        String repeatFingerprint = finalCommand + " @cwd:" + dir.getAbsolutePath();
        String rejectReason = CommandPrechecker.checkRepeatBreak(agentContext, repeatFingerprint);
        if (rejectReason != null) {
            return ToolResult.error(rejectReason);
        }

        // 5. 跨平台 Shell 解析与进程启动规格构建
        ProcessLaunchSpec launchSpec;
        try {
            launchSpec = ProcessLauncher.prepareProcess(finalCommand, cmdArgs.shell(), dir, gitBashLocator);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }

        String toolCallId = context.toolCallId();
        // 工具消息 ID：控制台日志流按消息 ID 归属（与助手流统一模型）
        Long messageId = context.messageId();
        Process process = null;

        try {
            process = launchSpec.builder().start();

            // 6. 注册物理子进程至上下文并启动增量生命周期追踪（瞬间快照 + 3秒动态补录 + MSYS族徽）
            ProcessTracker.registerAndTrack(
                    process, toolCallId, agentContext, finalCommand, dir,
                    launchSpec.bashEntry(), launchSpec.bashPathForRegistry()
            );

            // 7. 分流前后台执行模式
            if (runInBackground) {
                return CommandExecutionHandler.executeBackground(
                        process, toolCallId, messageId, agentContext, finalCommand, dir, forcedCharset
                );
            }

            return CommandExecutionHandler.executeForeground(
                    process, toolCallId, messageId, agentContext, finalCommand, dir, forcedCharset,
                    idleTimeoutMs, maxTimeoutMs, launchSpec.bashEntry(), repeatFingerprint
            );

        } catch (Throwable t) {
            // 刻意接 Throwable 而非 Exception：本工具常被用于在服务自身项目上跑构建（如 mvn compile），
            // 构建会重写运行中服务的 target/classes，执行链路上任何类（如 CommandResultBuilder）的
            // 首次懒加载撞上重写窗口都会抛 NoClassDefFoundError 等 Error——若放任穿透，工具调用
            // 永久挂死无结果且进程树无人清扫。此处兜住：强杀进程树 + 返回错误让模型可感知重试
            log.error("execute_command 执行异常", t);
            if (process != null) {
                try {
                    // 异常兜底同样级联清扫整棵进程树，防止半启动状态的孙进程脱钩残留
                    ProcessTracker.cascadeKillProcessTree(agentContext, toolCallId, process);
                } catch (Exception ex) {
                    log.error("级联强杀进程树异常", ex);
                }
            }
            return ToolResult.error("命令执行发生异常: " + t.getMessage());
        } finally {
            ProcessTracker.cleanupOnFinish(agentContext, toolCallId, runInBackground);
        }
    }
}
