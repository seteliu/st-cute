package com.stioc.cute.permission;

import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolPermissionDecision;
import com.stioc.cute.engine.tool.types.ToolPermissionVerdict;
import com.stioc.cute.service.FileHashSupport;
import com.stioc.cute.permission.types.PermissionMode;
import com.stioc.cute.permission.types.PermissionRule;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.tool.ToolNames;

import com.alibaba.fastjson2.JSON;
import com.stioc.cute.platform.common.CharsetAwareFileKit;
import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.platform.contract.ContractProperty;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.stioc.cute.engine.loop.core.AgentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.stioc.cute.project.ProjectService;
import com.stioc.cute.runtime.loop.RuntimeContext;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import jakarta.annotation.Resource;

/**
 * 权限服务：执行多层级安全规则流水线，作出 Allow / Deny / Ask 裁决。
 * <p>
 * 对外契约：{@link #evaluateVerdict(CuteTool, Map, AgentContext)} 评估工具权限返回强类型裁决，
 * {@link #evaluate(CuteTool, Map, AgentContext)} 为兼容命名别名；
 * {@link #writeLocalRule(PermissionRule)} 与 {@link #writeLocalRule(PermissionRule, String)}
 * 向全局/项目工作区本地配置写入持久化权限授信规则。
 * </p>
 */
@Slf4j
@Service
public class PermissionService {

    @Resource
    private ProjectService projectService;
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
     * 评估单次工具调用的安全性，返回强类型裁决结果 ToolPermissionVerdict
     *
     * @param tool 待评估的工具实例（工具名等标识由本方法经 getName() 自取）
     */
    public ToolPermissionVerdict evaluateVerdict(CuteTool tool, Map<String, Object> arguments, AgentContext context) {
        // 工具名自取：引擎传入的工具实例恒非 null（未知工具在引擎侧已早失败拦截），
        // 与模型的原始调用名最多存在大小写差异，后续所有比较均为 equalsIgnoreCase，行为一致
        String toolName = tool.getName();
        log.debug("权限评估开始: toolName={}", toolName);

        // 宽松参数访问：模型传参类型偏差（如数字形态路径）不致权限评估期 ClassCastException，
        // 类型非法按缺失处理，交由工具层给出精确的参数错误
        ToolArgs permArgs = ToolArgs.of(arguments);
        String pathVal = permArgs.getString("path");
        if (pathVal == null) {
            pathVal = permArgs.getString("filepath");
        }
        if (pathVal == null) {
            pathVal = permArgs.getString("file");
        }
        String commandVal = permArgs.getString("command");
        String patternVal = permArgs.getString("pattern");
        String queryVal = permArgs.getString("query");

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
        // 工具自声明资源优先：引擎传入的工具实例已自述目标资源（如文件路径、查询关键词），
        // 优先消费该声明消除参数名猜测耦合；声明缺失（null）时保持上面的旧猜测链结果，行为完全兼容
        if (!StringUtils.hasText(targetContent)) {
            targetContent = extractDeclaredResource(tool, arguments);
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
                    return ToolPermissionVerdict.allow();
                }
            }
        }

        // 层级 3: 危险命令黑名单硬拦截（DENY，高优先级，ALL_ALLOW 模式也得拦）
        if ((ToolNames.EXECUTE_COMMAND.equalsIgnoreCase(toolName)) && StringUtils.hasText(commandVal)) {
            List<String> cmdParts = splitCommand(commandVal);
            if (cmdParts.isEmpty()) {
                return ToolPermissionVerdict.deny("命令内容为空。");
            }
            String program = cmdParts.get(0).toLowerCase();
            List<String> args = cmdParts.subList(1, cmdParts.size());

            // a. 拦截高危的 rm -rf 行为
            if (program.contains("rm")) {
                boolean hasR = false;
                boolean hasF = false;
                for (String arg : args) {
                    if (arg.startsWith("-")) {
                        if (arg.contains("r") || arg.contains("R")) {
                            hasR = true;
                        }
                        if (arg.contains("f") || arg.contains("F")) {
                            hasF = true;
                        }
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
                                return ToolPermissionVerdict.deny("运行的命令尝试强制删除重要系统或工作目录。操作被安全硬拦截。");
                            }
                        }
                    }
                }
            }

            // b. 匹配其等的危险正则黑名单
            for (Pattern pattern : BLACKLIST_PATTERNS) {
                if (pattern.matcher(commandVal).matches()) {
                    log.warn("权限裁决: DENY [危险命令黑名单拦截] - command={}", commandVal);
                    return ToolPermissionVerdict.deny("运行的命令匹配高危黑名单特征(" + pattern.pattern() + ")。操作被安全硬拦截。");
                }
            }
        }

        // 层级 4: 路径沙箱强拦截（只限文件读写类工具及终端命令参数中的物理路径访问）
        // 工具实例由引擎直接传入（原先经 resolveTool 反查注册中心，现随评估调用一并下发，权限层不再反向依赖引擎）
        CuteTool toolInstance = tool;
        boolean isFileTool = toolInstance != null && toolInstance.getAccessLevel() != ToolAccessLevel.SENSITIVE;
        boolean isWriteOrModify = toolInstance != null && toolInstance.getAccessLevel() == ToolAccessLevel.WRITE;
        boolean isSensitiveTool = toolInstance != null && toolInstance.getAccessLevel() == ToolAccessLevel.SENSITIVE;
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
                    Path targetPath = projectService.resolvePath(pVal, context);
                    Path projectRoot = null;
                    if (context != null) {
                        String projectPath = projectService.getProjectBasePath(context);
                        if (StringUtils.hasText(projectPath)) {
                            projectRoot = Paths.get(projectPath).toAbsolutePath().normalize();
                        }
                    }
                    if (projectRoot == null) {
                        projectRoot = Paths.get(".").toAbsolutePath().normalize();
                    }

                    // 普通沙箱校验：允许项目根目录、临时目录与用户级配置目录
                    Path tempDir = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
                    Path userHomeConfig = Paths.get(System.getProperty("user.home"), ".st-cute").toAbsolutePath().normalize();

                    boolean inSandbox = targetPath.startsWith(projectRoot)
                            || targetPath.startsWith(tempDir)
                            || targetPath.startsWith(userHomeConfig);

                    if (!inSandbox) {
                        log.warn("权限裁决: DENY [路径沙箱外越界] - path={}", pVal);
                        return ToolPermissionVerdict.deny("文件路径越界。禁止访问项目根目录或临时目录外的系统敏感路径: " + pVal);
                    }
                } catch (Exception e) {
                    log.error("沙箱绝对路径转化异常, path={}", pVal, e);
                    return ToolPermissionVerdict.deny("解析文件路径失败: " + pVal + ", 原因: " + e.getMessage());
                }
            }
        }

        // 层级 4.5: 已读文件白名单强化（read_file 读过且内容哈希仍与磁盘一致的文件，其 Write/Modify 直接 ALLOW 放行）。
        // 仅授给写级工具：敏感级（删除/命令）即使先读过也必须走完整审批链，防止只读模式下"先读后删"被静默放行
        if (isWriteOrModify && !isSensitiveTool && StringUtils.hasText(pathVal)) {
            try {
                // 与 ReadFileTool/ModifyFileTool 统一走 ProjectService 解析，
                // 保证相对路径以项目根目录为基准，而非 JVM 工作目录，避免白名单路径基准不一致
                String absPath = projectService.resolvePath(pathVal, context).toAbsolutePath().normalize().toString();
                // 读取哈希门禁记录已迁运行时伴生上下文
                RuntimeContext runtimeCtx = context.extra(RuntimeContext.class);
                String recordedHash = runtimeCtx != null ? runtimeCtx.getReadFiles().get(absPath) : null;
                if (recordedHash != null && !recordedHash.isEmpty()
                        && recordedHash.equals(FileHashSupport.computeFileHash(Paths.get(absPath)))) {
                    log.debug("权限裁决: ALLOW [已读文件白名单强化放行] - path={}", pathVal);
                    return ToolPermissionVerdict.allow();
                }
            } catch (Exception e) {
                // ignore
            }
        }

        // 层级 5: 评估本地配置文件加白规则（全局级 → 项目级 → 本地级，末条优先）
        String projectBasePath = context != null ? projectService.getProjectBasePath(context) : null;
        List<PermissionRule> rules = loadAllRules(projectBasePath);
        ToolPermissionVerdict ruleVerdict = evaluateRules(rules, toolName, targetContent);
        if (ruleVerdict != null) {
            log.debug("权限裁决: {} [三级配置规则命中]", ruleVerdict);
            return ruleVerdict;
        }

        // 层级 6: 矩阵四档权限兜底决策 (只产 ALLOW 或 ASK，不产 DENY)
        // permissionMode 已字符串化：宿主侧按需解析为枚举（null 会安全兜底为 READ_ONLY）
        PermissionMode mode = PermissionMode.fromName(context != null ? context.getPermissionMode() : null);
        String category = getToolCategory(toolInstance);

        if ("readonly".equalsIgnoreCase(category)) {
            log.debug("权限裁决: ALLOW [矩阵兜底-只读工具]");
            return ToolPermissionVerdict.allow();
        }

        if (mode == PermissionMode.ALL_ALLOW) {
            log.debug("权限裁决: ALLOW [矩阵兜底-全部放行模式]");
            return ToolPermissionVerdict.allow();
        }

        if (mode == PermissionMode.SMART_APPROVAL && "filewrite".equalsIgnoreCase(category)) {
            log.debug("权限裁决: ALLOW [矩阵兜底-智能审批下的文件写入直接放行]");
            return ToolPermissionVerdict.allow();
        }

        // 只读模式下的写与命令，以及智能审批下的终端命令，均进入人在回路 Ask
        log.info("权限裁决: ASK [需人在回路二次授权] - toolName={}, mode={}", toolName, mode);
        return ToolPermissionVerdict.ask();
    }

    /**
     * 评估单次工具调用的安全性，直接透传强类型裁决结果
     */
    public ToolPermissionVerdict evaluate(CuteTool tool, Map<String, Object> arguments, AgentContext context) {
        return evaluateVerdict(tool, arguments, context);
    }

    /**
     * 提取工具自声明的目标资源：优先作为规则匹配特征。
     * <p>
     * 工具的 {@link CuteTool#getTargetResource(Map)} 由工具自身定义语义（如文件路径、搜索关键词），
     * 相比按 path/command 等固定参数名猜测更可靠；自声明资源为多路径拼接形态（含 " -> "）
     * 时不参与特征匹配（可读性差且无规则匹配价值），跳过由上层兜底逻辑处理。
     * 提取期异常按无声明处理（评估不因资源提取崩溃）。
     * </p>
     */
    private String extractDeclaredResource(CuteTool tool, Map<String, Object> arguments) {
        try {
            String resource = tool.getTargetResource(arguments);
            // 拼接形态（含 " -> "）与单路径语义不适配规则匹配特征，跳过
            if (resource != null && !resource.isBlank() && !resource.contains(" -> ")) {
                return resource;
            }
        } catch (Exception e) {
            log.debug("提取工具自声明资源失败，按无声明处理: {}", tool.getName(), e);
        }
        return null;
    }

    /**
     * 按照工具声明的访问等级（读/写/敏感三档）返回工具分类字符串。
     * null 工具（未知工具）兜底为 "command" 类别，触发命令审批。
     */
    private String getToolCategory(CuteTool tool) {
        if (tool == null) {
            return "command";
        }
        ToolAccessLevel level = tool.getAccessLevel();
        if (level == ToolAccessLevel.READ) {
            return "readonly";
        }
        if (level == ToolAccessLevel.WRITE) {
            return "filewrite";
        }
        // 敏感级工具与未知工具一样按命令类治理：智能审批不放行，进入人工审批
        return "command";
    }

    private List<String> splitCommand(String command) {
        List<String> list = new ArrayList<>();
        if (command == null) {
            return list;
        }
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
    private ToolPermissionVerdict evaluateRules(List<PermissionRule> rules, String toolName, String targetContent) {
        for (int i = rules.size() - 1; i >= 0; i--) { // 倒序扫描（末条优先）
            PermissionRule rule = rules.get(i);
            if (rule.getToolName().equalsIgnoreCase(toolName)) {
                if (matchGlob(rule.getContentPattern(), targetContent, toolName)) {
                    String effect = rule.getEffect();
                    if (ToolPermissionDecision.DENY.name().equalsIgnoreCase(effect)) {
                        return ToolPermissionVerdict.deny("命中配置文件拒绝规则");
                    } else if (ToolPermissionDecision.ALLOW.name().equalsIgnoreCase(effect)) {
                        return ToolPermissionVerdict.allow();
                    } else if (ToolPermissionDecision.ASK.name().equalsIgnoreCase(effect)) {
                        return ToolPermissionVerdict.ask();
                    }
                    return ToolPermissionVerdict.fromRaw(effect);
                }
            }
        }
        return null;
    }

    private boolean matchGlob(String pattern, String content, String toolName) {
        if (pattern == null || content == null) {
            return false;
        }
        if ("*".equals(pattern)) {
            return true;
        }

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
     * 加载合并权限规则配置文件（顺序：全局级 → 项目级 → 本地级，裁决时末条优先）。
     * <p>
     * 权限契约的特殊性：仅认 全局级 + 项目级 + 本地级，项目通用级 .agents 完全不参与。
     * </p>
     */
    private List<PermissionRule> loadAllRules(String projectBasePath) {
        List<PermissionRule> merged = new ArrayList<>();

        // 1. 全局级：~/.st-cute/permission.json
        File globalConfig = ContractFile.getGlobalPermissionFile();
        if (globalConfig != null) {
            merged.addAll(loadRulesFromFile(globalConfig.toPath()));
        }

        // 2. 项目级与本地级：固定读取 {project}/.st-cute/ 下文件（项目通用级 .agents 完全不参与）
        File levelDir = ContractFile.getProjectLevelDir(projectBasePath);
        if (levelDir != null) {
            // 2.1 项目级：permission.json
            File projConfig = new File(levelDir, ContractFile.FILE_PERMISSION);
            if (projConfig.exists()) {
                merged.addAll(loadRulesFromFile(projConfig.toPath()));
            }

            // 2.2 本地级：permission_local.json (人在回路加白自动写入此处)
            File localConfig = new File(levelDir, ContractFile.FILE_PERMISSION_LOCAL);
            if (localConfig.exists()) {
                merged.addAll(loadRulesFromFile(localConfig.toPath()));
            }
        }

        return merged;
    }

    private List<PermissionRule> loadRulesFromFile(Path path) {
        List<PermissionRule> list = new ArrayList<>();
        if (!Files.exists(path)) {
            return list;
        }
        try {
            String jsonStr = CharsetAwareFileKit.readString(path);
            if (!StringUtils.hasText(jsonStr)) {
                return list;
            }

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
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

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
        if (str == null || str.isBlank()) {
            return false;
        }
        // 排除 URL 链接
        if (str.startsWith("http://") || str.startsWith("https://")) {
            return false;
        }
        // 排除命令行参数选项
        if (str.startsWith("-")) {
            return false;
        }
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
