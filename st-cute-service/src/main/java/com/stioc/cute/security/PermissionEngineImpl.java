package com.stioc.cute.security;

import com.stioc.cute.security.access.PermissionRule;
import com.stioc.cute.security.access.PermissionMode;
import com.stioc.cute.security.access.*;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolNames;
import com.stioc.cute.tool.access.ToolRegistry;

import com.alibaba.fastjson2.JSON;
import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.platform.contract.ContractProperty;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.stioc.cute.agent.access.AgentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.stioc.cute.conversation.access.ConversationService;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import jakarta.annotation.Resource;

/**
 * 权限引擎：执行多层级安全规则流水线，作出 Allow / Deny / Ask 裁决
 */
@Slf4j
@Component
public class PermissionEngineImpl implements PermissionEngine {

    @Resource
    private ConversationService conversationService;
    @Resource
    private ToolRegistry toolRegistry;
    @Resource
    private WorkspacePathResolver workspacePathResolver;
    @Resource
    private ContractProperty contractProperty;

    /**
     * 危险命令黑名单正则表达式匹配模式列表（绝不允许执行）
     */
    private static final List<Pattern> BLACKLIST_PATTERNS = List.of(
            Pattern.compile("rm\\s+-rf\\s+.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("mkfs(\\..*)?\\s+.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("dd\\s+if=.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("chmod\\s+.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("chown\\s+.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("curl\\s+.*\\|\\s*(bash|sh)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("wget\\s+.*\\|\\s*(bash|sh)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+push\\s+.*(-f|--force)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+reset\\s+--hard.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+clean\\s+-fd.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(":.*\\|.*:&", Pattern.CASE_INSENSITIVE) // fork bomb
    );

    /**
     * 安全无副作用命令的前缀放行白名单集合
     */
    private static final Set<String> SAFE_COMMAND_PREFIXES = Set.of(
            "ls", "pwd", "git status", "git log", "git diff", "mvn -v", "node -v", "pnpm -v", "npm -v", "echo"
    );

    /**
     * 评估单次工具调用的安全性，返回 "ALLOW"、"DENY:<原因>" 或 "ASK"
     */
    public String evaluate(String toolName, Map<String, Object> arguments, AgentContext context) {
        log.debug("权限评估开始: toolName={}", toolName);

        // 物理强拦截：禁止子智能体继续派发子智能体（防无限递归和嵌套）
        boolean isSubAgent = context != null && context.getParentCid() != null && context.getParentCid() != 0L;
        if (isSubAgent && ToolNames.INVOKE_SUBAGENT.equalsIgnoreCase(toolName)) {
            log.warn("权限裁决: DENY [子智能体禁止二次派生] - toolName={}", toolName);
            return "DENY:禁止子智能体继续派发子智能体，防止无限递归和嵌套。";
        }

        // 豁免：主会话调用 invoke_subagent 直接放行，不进入人在回路审批。
        // 理由：该工具仅是「拉起子 Agent」的调度动作，子会话创建时完整继承父会话的
        // 权限模式/供应商/Worktree 隔离/已解锁工具，真正需要审批的是子 Agent 内部产生的
        // 写文件/执行命令等副作用（由子会话自身的权限模式把守）。若对 invoke_subagent 本身
        // 挂起 ASK，会导致子会话未创建、waitingSubCids 未写入，屏障在审批期间出现空窗口。
        if (!isSubAgent && ToolNames.INVOKE_SUBAGENT.equalsIgnoreCase(toolName)) {
            log.debug("权限裁决: ALLOW [invoke_subagent 调度豁免] - toolName={}", toolName);
            return "ALLOW";
        }

        String pathVal = (String) arguments.get("path");
        if (pathVal == null) {
            pathVal = (String) arguments.get("filepath");
        }
        if (pathVal == null) {
            pathVal = (String) arguments.get("file");
        }
        String commandVal = (String) arguments.get("command");
        String patternVal = (String) arguments.get("pattern");
        String queryVal = (String) arguments.get("query");

        // 提取主要特征内容
        String targetContent = "";
        if (StringUtils.hasText(pathVal)) {
            targetContent = pathVal;
        } else if (StringUtils.hasText(commandVal)) {
            targetContent = commandVal;
        } else if (StringUtils.hasText(patternVal)) {
            targetContent = patternVal;
        } else if (StringUtils.hasText(queryVal)) {
            targetContent = queryVal;
        }

        // 层级 1: 计划模式已移除，豁免检查跳过

        // 层级 2: 安全只读命令快速放行（防元字符旁路）
        if ((ToolNames.EXECUTE_COMMAND.equalsIgnoreCase(toolName)) && StringUtils.hasText(commandVal)) {
            if (!containsShellMetaCharacters(commandVal)) {
                String cmdTrim = commandVal.trim();
                boolean isSafePrefix = false;
                for (String prefix : SAFE_COMMAND_PREFIXES) {
                    if (cmdTrim.equals(prefix) || cmdTrim.startsWith(prefix + " ") || cmdTrim.startsWith(prefix + "\t")) {
                        isSafePrefix = true;
                        break;
                    }
                }
                if (isSafePrefix) {
                    log.debug("权限裁决: ALLOW [安全只读命令快速放行]");
                    return "ALLOW";
                }
            }
        }

        // 层级 3: 危险命令黑名单硬拦截（DENY，高优先级，ALL_ALLOW 模式也得拦）
        if ((ToolNames.EXECUTE_COMMAND.equalsIgnoreCase(toolName)) && StringUtils.hasText(commandVal)) {
            List<String> cmdParts = splitCommand(commandVal);
            if (cmdParts.isEmpty()) {
                return "DENY:命令内容为空。";
            }
            String program = cmdParts.get(0).toLowerCase();
            List<String> args = cmdParts.subList(1, cmdParts.size());

            // a. 拦截高危的 rm -rf 行为
            if (program.contains("rm")) {
                boolean hasR = false;
                boolean hasF = false;
                for (String arg : args) {
                    if (arg.startsWith("-")) {
                        if (arg.contains("r") || arg.contains("R")) hasR = true;
                        if (arg.contains("f") || arg.contains("F")) hasF = true;
                    }
                }
                if (hasR && hasF) {
                    for (String arg : args) {
                        if (!arg.startsWith("-")) {
                            String trimmed = arg.trim();
                            if ("/".equals(trimmed) || "/*".equals(trimmed)
                                    || "~".equals(trimmed) || "~/*".equals(trimmed)
                                     || ".".equals(trimmed) || "./*".equals(trimmed)) {
                                log.warn("权限裁决: DENY [高危 rm -rf 拦截] - command={}", commandVal);
                                return "DENY:运行的命令尝试强制删除重要系统或工作目录。操作被安全硬拦截。";
                            }
                        }
                    }
                }
            }

            // b. 匹配其等的危险正则黑名单
            for (Pattern pattern : BLACKLIST_PATTERNS) {
                if (pattern.matcher(commandVal).matches()) {
                    log.warn("权限裁决: DENY [危险命令黑名单拦截] - command={}", commandVal);
                    return "DENY:运行的命令匹配高危黑名单特征(" + pattern.pattern() + ")。操作被安全硬拦截。";
                }
            }
        }

        // 层级 4: 路径沙箱强拦截（只限文件读写类工具及终端命令参数中的物理路径访问）
        CuteTool toolInstance = resolveTool(toolName, context);
        boolean isFileTool = toolInstance != null && (toolInstance.isReadOnly() || toolInstance.isWriteTool());
        boolean isWriteOrModify = toolInstance != null && toolInstance.isWriteTool();
        boolean isExecuteCommand = ToolNames.EXECUTE_COMMAND.equalsIgnoreCase(toolName);

        boolean pathSandboxEnabled = contractProperty == null || contractProperty.isPathSandboxEnabled();

        if (pathSandboxEnabled) {
            List<String> pathsToCheck = new ArrayList<>();

            // a. 文件工具的路径安检
            if (isFileTool && StringUtils.hasText(pathVal)) {
                pathsToCheck.add(pathVal);
            }

            // b. 命令行工具的参数路径提取与安检
            if (isExecuteCommand && StringUtils.hasText(commandVal)) {
                List<String> cmdParts = splitCommand(commandVal);
                for (int i = 1; i < cmdParts.size(); i++) {
                    String part = cmdParts.get(i);
                    if (looksLikePath(part)) {
                        pathsToCheck.add(part);
                    }
                }
            }

            for (String pVal : pathsToCheck) {
                try {
                    Path targetPath = workspacePathResolver.resolvePath(pVal, context);
                    Path projectRoot = null;
                    if (context != null) {
                        String projectPath = conversationService.getProjectPath(context.getCid());
                        if (StringUtils.hasText(projectPath)) {
                            projectRoot = Paths.get(projectPath).toAbsolutePath().normalize();
                        }
                    }
                    if (projectRoot == null) {
                        projectRoot = Paths.get(".").toAbsolutePath().normalize();
                    }

                    // 如果当前 Session 绑定了物理隔离的 Worktree 路径
                    if (context != null && StringUtils.hasText(context.getWorktreePath())) {
                        Path worktreeRoot = Paths.get(context.getWorktreePath()).toAbsolutePath().normalize();

                        // 写操作必须局限在 Worktree 内，绝对禁止写入主仓库目录
                        if (isWriteOrModify) {
                            if (!targetPath.startsWith(worktreeRoot)) {
                                log.warn("权限裁决: DENY [Worktree物理隔离外写越界] - path={}, worktree={}", pVal, context.getWorktreePath());
                                return "DENY:当前会话处于 Worktree 物理隔离中，写操作仅限工作副本路径: " + context.getWorktreePath();
                            }
                        } else {
                            // 只读文件工具允许读取主项目根目录或 worktree，但不能越界到系统其他位置
                            boolean inWorktreeOrProject = targetPath.startsWith(worktreeRoot) || targetPath.startsWith(projectRoot);
                            if (!inWorktreeOrProject) {
                                log.warn("权限裁决: DENY [Worktree物理隔离外读越界] - path={}", pVal);
                                return "DENY:文件路径越界。隔离模式下仅限访问项目库或隔离区路径: " + pVal;
                            }
                        }
                    } else {
                        // 普通无隔离模式下的沙箱校验
                        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
                        Path userHomeConfig = Paths.get(System.getProperty("user.home"), ".st-cute").toAbsolutePath().normalize();

                        boolean inSandbox = targetPath.startsWith(projectRoot)
                                || targetPath.startsWith(tempDir)
                                || targetPath.startsWith(userHomeConfig);

                        if (!inSandbox) {
                            log.warn("权限裁决: DENY [路径沙箱外越界] - path={}", pVal);
                            return "DENY:文件路径越界。禁止访问项目根目录或临时目录外的系统敏感路径: " + pVal;
                        }
                    }
                } catch (Exception e) {
                    log.error("沙箱绝对路径转化异常, path={}", pVal, e);
                    return "DENY:解析文件路径失败: " + pVal + ", 原因: " + e.getMessage();
                }
            }
        }

        // 层级 4.5: 已读文件白名单强化 (ReadFile 读过的文件，其 Write/Modify 直接 ALLOW 放行)
        if (isWriteOrModify && StringUtils.hasText(pathVal)) {
            try {
                // 与 ReadFileTool/ModifyFileTool 统一走 WorkspacePathResolver 解析，
                // 保证相对路径以项目根/worktree 为基准，而非 JVM 工作目录，避免白名单路径基准不一致
                String absPath = workspacePathResolver.resolvePath(pathVal, context).toAbsolutePath().normalize().toString();
                if (context.getReadFiles().contains(absPath)) {
                    log.debug("权限裁决: ALLOW [已读文件白名单强化放行] - path={}", pathVal);
                    return "ALLOW";
                }
            } catch (Exception e) {
                // ignore
            }
        }

        // 层级 5: 评估三级本地配置文件加白规则（末条优先）
        String projectBasePath = context != null ? context.getWorktreePath() : null;
        if (!StringUtils.hasText(projectBasePath) && context != null) {
            projectBasePath = conversationService.getProjectPath(context.getCid());
        }
        List<PermissionRule> rules = loadAllRules(projectBasePath);
        String ruleDecision = evaluateRules(rules, toolName, targetContent);
        if (ruleDecision != null) {
            log.debug("权限裁决: {} [三级配置规则命中]", ruleDecision);
            return ruleDecision;
        }

        // 层级 6: 矩阵四档权限兜底决策 (只产 ALLOW 或 ASK，不产 DENY)
        PermissionMode mode = context.getPermissionMode();
        String category = getToolCategory(toolInstance);

        if ("readonly".equalsIgnoreCase(category)) {
            log.debug("权限裁决: ALLOW [矩阵兜底-只读工具]");
            return "ALLOW";
        }

        if (mode == PermissionMode.ALL_ALLOW) {
            log.debug("权限裁决: ALLOW [矩阵兜底-全部放行模式]");
            return "ALLOW";
        }

        if (mode == PermissionMode.SMART_APPROVAL && "filewrite".equalsIgnoreCase(category)) {
            log.debug("权限裁决: ALLOW [矩阵兜底-智能审批下的文件写入直接放行]");
            return "ALLOW";
        }

        // 只读模式下的写与命令，以及智能审批下的终端命令，均进入人在回路 Ask
        log.info("权限裁决: ASK [需人在回路二次授权] - toolName={}, mode={}", toolName, mode);
        return "ASK";
    }

    /**
     * 通过 ToolRegistry 查找工具实例，用于基于接口方法判断工具类型，避免字符串猜测。
     * 若 context 为 null 或 registry 不可用，则退化到 null（调用方做兜底判断）。
     */
    private CuteTool resolveTool(String toolName, AgentContext context) {
        if (toolRegistry == null || toolName == null) {
            return null;
        }
        return toolRegistry.getTool(toolName, context);
    }

    /**
     * 按照工具接口声明（isReadOnly / isWriteTool）返回工具分类字符串。
     * null 工具（未知工具）兜底为 "command" 类别，触发命令审批。
     */
    private String getToolCategory(CuteTool tool) {
        if (tool == null) {
            return "command";
        }
        if (tool.isReadOnly()) {
            return "readonly";
        }
        if (tool.isWriteTool()) {
            return "filewrite";
        }
        return "command";
    }

    private List<String> splitCommand(String command) {
        List<String> list = new ArrayList<>();
        if (command == null) return list;
        StringBuilder sb = new StringBuilder();
        boolean inDoubleQuotes = false;
        boolean inSingleQuotes = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (Character.isWhitespace(c) && !inDoubleQuotes && !inSingleQuotes) {
                if (sb.length() > 0) {
                    list.add(sb.toString());
                    sb.setLength(0);
                }
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            list.add(sb.toString());
        }
        return list;
    }

    private boolean containsShellMetaCharacters(String cmd) {
        // 包含分号、管道、后台运行、重定向、子命令执行等元字符
        return cmd.contains(";") || cmd.contains("|") || cmd.contains("&")
                || cmd.contains(">") || cmd.contains("<") || cmd.contains("$(")
                || cmd.contains("`") || cmd.contains("\n") || cmd.contains("\r");
    }

    /**
     * 评估白名单规则：末条优先，支持 Glob
     */
    private String evaluateRules(List<PermissionRule> rules, String toolName, String targetContent) {
        for (int i = rules.size() - 1; i >= 0; i--) { // 倒序扫描（末条优先）
            PermissionRule rule = rules.get(i);
            if (rule.getToolName().equalsIgnoreCase(toolName)) {
                if (matchGlob(rule.getContentPattern(), targetContent, toolName)) {
                    return rule.getEffect().toUpperCase();
                }
            }
        }
        return null;
    }

    private boolean matchGlob(String pattern, String content, String toolName) {
        if (pattern == null || content == null) return false;
        if ("*".equals(pattern)) return true;

        try {
            // 如果是命令执行工具，星号通配符应该支持跨目录匹配（即匹配命令中的任意字符，包括斜杠）
            String starReplacement = ToolNames.EXECUTE_COMMAND.equalsIgnoreCase(toolName) ? ".*" : "[^/]*";
            String regex = pattern
                    .replace(".", "\\.")
                    .replace("**", ".*")
                    .replace("*", starReplacement)
                    .replace("?", ".");
            return Pattern.compile("^" + regex + "$", Pattern.CASE_INSENSITIVE).matcher(content).matches();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 加载合并三级规则配置文件
     */
    private List<PermissionRule> loadAllRules(String projectBasePath) {
        List<PermissionRule> merged = new ArrayList<>();

        // 1. 用户级：~/.st-cute/permission.json
        File userConfig = ContractFile.getGlobalPermissionFile();
        if (userConfig != null) {
            merged.addAll(loadRulesFromFile(userConfig.toPath()));
        }

        if (projectBasePath != null) {
            // 2. 项目级：当前项目下 permission.json
            ContractFile.forEachProjectFile(projectBasePath, ContractFile.FILE_PERMISSION, projConfig -> {
                merged.addAll(loadRulesFromFile(projConfig.toPath()));
            });

            // 3. 本地级：当前项目下 permission_local.json (人在回路加白自动写入此处)
            ContractFile.forEachProjectFile(projectBasePath, ContractFile.FILE_PERMISSION_LOCAL, localConfig -> {
                merged.addAll(loadRulesFromFile(localConfig.toPath()));
            });
        }

        return merged;
    }

    private List<PermissionRule> loadRulesFromFile(Path path) {
        List<PermissionRule> list = new ArrayList<>();
        if (!Files.exists(path)) {
            return list;
        }
        try {
            String jsonStr = Files.readString(path, StandardCharsets.UTF_8);
            if (!StringUtils.hasText(jsonStr)) return list;

            JSONObject obj = JSON.parseObject(jsonStr);
            if (obj != null && obj.containsKey("rules")) {
                JSONArray arr = obj.getJSONArray("rules");
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        JSONObject rObj = arr.getJSONObject(i);
                        if (rObj != null && rObj.containsKey("toolName") && rObj.containsKey("contentPattern") && rObj.containsKey("effect")) {
                            list.add(new PermissionRule(
                                    rObj.getString("toolName"),
                                    rObj.getString("contentPattern"),
                                    rObj.getString("effect")
                            ));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("加载配置文件规则失败: {}, 降级为空。异常={}", path.toAbsolutePath(), e.getMessage());
        }
        return list;
    }

    /**
     * 将白名单规则写入本地级配置文件
     */
    public synchronized void writeLocalRule(PermissionRule rule) {
        writeLocalRule(rule, null);
    }

    public synchronized void writeLocalRule(PermissionRule rule, String projectBasePath) {
        File localFile = ContractFile.getProjectPermissionLocalFile(projectBasePath);
        if (localFile == null) {
            log.warn("当前会话未绑定具体项目路径，跳过写入本地级权限加白文件。");
            return;
        }
        Path localPath = localFile.toPath();
        try {
            File parent = localFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            List<PermissionRule> existing = loadRulesFromFile(localPath);
            existing.add(rule);

            JSONObject wrapper = new JSONObject();
            wrapper.put("rules", existing);

            Files.writeString(localPath, JSON.toJSONString(wrapper, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8);
            log.info("成功持久化权限规则到本地级配置: {}", rule);
        } catch (IOException e) {
            log.error("写入本地级配置规则失败", e);
        }
    }

    private boolean looksLikePath(String str) {
        if (str == null || str.isBlank()) return false;
        // 排除 URL 链接
        if (str.startsWith("http://") || str.startsWith("https://")) return false;
        // 排除命令行参数选项
        if (str.startsWith("-")) return false;
        // 排除含有冒号的非盘符常数（如 localhost:8080，maven坐标等）
        if (str.contains(":")) {
            if (str.length() >= 3 && Character.isLetter(str.charAt(0)) && str.charAt(1) == ':') {
                char third = str.charAt(2);
                if (third == '\\' || third == '/') {
                    return true;
                }
            }
            return false;
        }
        // 包含路径斜杠的字符串，或者是特殊短相对路径，均认为是路径
        if (str.contains("/") || str.contains("\\") || ".".equals(str) || "..".equals(str)) {
            return true;
        }
        return false;
    }
}
