package com.stioc.cute.permission;

import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolPermissionDecision;
import com.stioc.cute.engine.tool.types.ToolPermissionVerdict;
import com.stioc.cute.file.FileHashSupport;
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
     * 危险命令形态黑名单：命令形态本身即具破坏性、无法靠路径沙箱兜底的场景。
     * <p>
     * 收录原则是「是否危险与目标路径无关」；形如 rm / chmod / chown 这类
     * 「危险与否完全取决于目标路径」的命令不在此列：其沙箱外访问由层级 4 路径沙箱拦截，
     * 沙箱内的正常操作则交由审批矩阵裁决，不做形态一刀切，避免正常运维被无差别误拦。
     * </p>
     */
    private static final List<DangerousCommandPattern> DANGEROUS_COMMAND_PATTERNS = List.of(
            new DangerousCommandPattern(Pattern.compile("mkfs(\\..*)?\\s+.*", Pattern.CASE_INSENSITIVE),
                    "mkfs 会格式化磁盘分区，造成不可逆的数据销毁"),
            new DangerousCommandPattern(Pattern.compile("dd\\s+.*if=.*", Pattern.CASE_INSENSITIVE),
                    "dd 可向裸设备或分区直接写入，存在不可逆的数据销毁风险"),
            new DangerousCommandPattern(Pattern.compile("(curl|wget)\\s+.*\\|\\s*(bash|sh|zsh|python3?|perl).*", Pattern.CASE_INSENSITIVE),
                    "将网络下载内容直接管道给解释器执行，其真实行为无法审计"),
            // 强制推送会覆盖远端提交历史：(?![-\w]) 用于排除 --force-with-lease —— 该写法会在
            // 覆盖前校验远端是否已被他人更新，是官方推荐的更安全替代，不应与 --force 一视同仁
            new DangerousCommandPattern(Pattern.compile("git\\s+push\\s+.*(--force(?![\\-\\w])|-f)(\\s|$).*", Pattern.CASE_INSENSITIVE),
                    "git push 强制推送会覆盖远端提交历史，可能导致他人提交永久丢失（如需安全强推请用 --force-with-lease）"),
            new DangerousCommandPattern(Pattern.compile("git\\s+reset\\s+--hard.*", Pattern.CASE_INSENSITIVE),
                    "git reset --hard 会丢弃工作区与暂存区的全部未提交改动"),
            new DangerousCommandPattern(Pattern.compile("git\\s+clean\\s+(-[a-zA-Z]*f[a-zA-Z]*|.*--force).*", Pattern.CASE_INSENSITIVE),
                    "git clean 带 -f/--force 会永久删除未跟踪文件，删除后无法通过 Git 恢复"),
            // fork bomb 形态为 ":(){ :|:& };:"，递归自调用部分位于串中而非串尾，
            // 故首尾均需 .* 兜住，不能用 ^:...:&$ 形态的锚定写法
            new DangerousCommandPattern(Pattern.compile(".*:.*\\|.*:&.*", Pattern.CASE_INSENSITIVE),
                    "疑似 fork bomb，会耗尽系统进程资源")
    );

    /**
     * 破坏性删除目标的精确匹配集合：系统根及其通配写法。
     */
    private static final Set<String> DESTRUCTIVE_TARGETS = Set.of(
            "/", "/*"
    );

    /**
     * 主目录展开前缀集合：以这些前缀开头的删除目标一律按破坏性处理。
     * <p>
     * 采用前缀而非精确匹配的原因：{@code ~/Documents} 这类路径会被路径解析器
     * 当作项目内相对路径（解析为 {@code {项目根}/~/Documents}）而误判为沙箱内合法，
     * 但 shell 实际展开后指向真实的主目录，存在真实的数据丢失风险。
     * 因此凡是以主目录展开语义开头的目标，均在其未被展开的形态下拦截。
     * </p>
     */
    private static final Set<String> HOME_EXPANSION_PREFIXES = Set.of(
            "~", "$HOME", "${HOME}", "%USERPROFILE%", "%HOMEDRIVE%%HOMEPATH%"
    );

    /**
     * 驱动器根路径形态（如 C:\、C:/、D:），用于识别整盘级破坏性删除目标
     */
    private static final Pattern DRIVE_ROOT_PATTERN = Pattern.compile("(?i)^[a-z]:[\\\\/]?$");

    /**
     * 删除类程序名集合（跨平台）：仅这些程序参与「破坏性删除目标」判定，
     * 避免把 cat、echo 等程序路径参数里的 "/" 误判为删除目标
     */
    private static final Set<String> DELETE_PROGRAMS = Set.of(
            "rm", "rmdir", "rd", "del", "erase"
    );

    /**
     * 命令分隔符集合：用于在链式命令中切分出各个独立的命令位置
     */
    private static final Set<String> COMMAND_SEPARATORS = Set.of(
            "&&", "||", ";", "|", "&"
    );

    /**
     * 前置包装命令集合：其后的 token 仍处于命令位置（如 {@code sudo rm -rf /}）
     */
    private static final Set<String> COMMAND_WRAPPERS = Set.of(
            "sudo", "doas", "command", "nohup", "setsid", "time", "env", "xargs"
    );

    /**
     * 危险命令形态及其可读风险说明
     *
     * @param pattern 命令形态正则（对整个命令串做全串匹配）
     * @param reason  面向模型与用户的拒绝原因（人类可读，刻意不回显裸正则，
     *                避免把排查方向误导到正则语义而非真实风险上）
     */
    private record DangerousCommandPattern(Pattern pattern, String reason) {
    }

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
        // 非约定参数名的路径入口补漏：grep_search/find_files 的 rootDir 与 execute_command 的 cwd
        // 也是物理路径入口，须参与沙箱安检与命令快速放行前置校验，防止越界路径经非 path 参数名旁路进入
        String rootDirVal = permArgs.getString("rootDir");
        String cwdVal = permArgs.getString("cwd");

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
            targetContent = extractDeclaredResource(tool, arguments, context);
        }

        // 层级 1: 计划模式已移除，豁免检查跳过

        // 层级 2: 安全只读命令快速放行（防元字符旁路）
        if ((ToolNames.EXECUTE_COMMAND.equalsIgnoreCase(toolName)) && StringUtils.hasText(commandVal)) {
            // 前置防线：快速放行前必须先校验 cwd 落沙箱。否则 execute_command(command="ls", cwd="C:/Windows/...")
            // 会在沙箱检查之前命中白名单直接 ALLOW，越界工作目录从未被校验（cwd 参数不在 path 参数提取链内）
            if (StringUtils.hasText(cwdVal) && !isPathInSandbox(cwdVal, context)) {
                log.warn("权限裁决: DENY [安全命令快速放行被越界 cwd 阻断] - cwd={}", cwdVal);
                return ToolPermissionVerdict.deny("命令工作目录(cwd)路径越界。禁止访问项目根目录或临时目录外的系统敏感路径: " + cwdVal);
            }
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

            // a. 递归删除指向破坏性目标（系统根、用户主目录、驱动器根）时硬拦。
            // 判据刻意选择「目标路径」而非「命令形态」：沙箱内的常规清理（如 rm -rf dist、
            // rm -rf node_modules）交由审批矩阵按权限模式裁决，不做无差别形态拦截，
            // 避免正常运维被误伤；沙箱外的目标路径则由下方层级 4 的路径沙箱统一兜底。
            String destructiveTarget = findDestructiveDeleteTarget(cmdParts);
            if (destructiveTarget != null) {
                log.warn("权限裁决: DENY [破坏性删除目标拦截] - command={}, target={}", commandVal, destructiveTarget);
                return ToolPermissionVerdict.deny("命令尝试递归删除重要系统路径或用户主目录（目标: "
                        + destructiveTarget + "）。该操作可能造成不可逆的数据丢失，已被安全拦截。");
            }

            // b. 命令形态本身即具破坏性的场景（是否危险与目标路径无关）
            for (DangerousCommandPattern danger : DANGEROUS_COMMAND_PATTERNS) {
                if (danger.pattern().matcher(commandVal).matches()) {
                    log.warn("权限裁决: DENY [危险命令形态拦截] - command={}, reason={}", commandVal, danger.reason());
                    return ToolPermissionVerdict.deny("该命令被判定为危险操作：" + danger.reason()
                            + "。如确需执行，请改用影响范围明确、风险更小的等价命令。");
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

            // a+. 搜索类工具的根目录安检：grep_search / find_files 的路径入口参数是 rootDir
            //（不在 path/filepath/file 约定参数名提取链内），历史上属沙箱盲区，必须补入安检名单
            if (isFileTool && StringUtils.hasText(rootDirVal)) {
                pathsToCheck.add(rootDirVal);
            }

            // b. 命令行工具的参数路径提取与安检
            if (isExecuteCommand && StringUtils.hasText(commandVal)) {
                // b.0 cwd 显式安检：工作目录是确定的物理路径入口。命令 token 提取（looksLikePath）只覆盖
                // 命令文本内的路径形态参数，cwd 参数本身越界的场景必须在此显式拦截
                if (StringUtils.hasText(cwdVal)) {
                    pathsToCheck.add(cwdVal);
                }
                List<String> cmdParts = splitCommand(commandVal);
                for (int i = 1; i < cmdParts.size(); i++) {
                    String part = cmdParts.get(i);
                    if (looksLikePath(part)) {
                        pathsToCheck.add(part);
                    }
                }
            }

            for (String pVal : pathsToCheck) {
                if (!isPathInSandbox(pVal, context)) {
                    log.warn("权限裁决: DENY [路径沙箱外越界] - path={}", pVal);
                    return ToolPermissionVerdict.deny("文件路径越界。禁止访问项目根目录或临时目录外的系统敏感路径: " + pVal);
                }
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

        // 层级 5.5: 已读文件白名单强化（read_file 读过且内容哈希仍与磁盘一致的文件，其 Write/Modify 直接 ALLOW 放行）。
        // 仅授给写级工具：敏感级（删除/命令）即使先读过也必须走完整审批链，防止只读模式下"先读后删"被静默放行。
        // 刻意置于规则层（层级 5）之后评估：用户显式配置的 DENY/ASK 规则必须优先于此白名单生效，
        // 防止"先 read_file 再修改"的组合绕过用户明确表达的拒绝意图（历史上先于此层评估存在绕过隐患）
        if (isWriteOrModify && !isSensitiveTool && StringUtils.hasText(pathVal)) {
            try {
                // 与 ReadFileTool/ModifyFileTool 统一走 ProjectService 解析与 FileHashSupport.toStorageKey 规范化 Key，
                // 保证相对路径以项目根目录为基准，消除 Windows 路径大小写差异导致的白名单失配
                Path resolved = projectService.resolvePath(pathVal, context);
                String storageKey = FileHashSupport.toStorageKey(resolved);
                // 读取哈希门禁记录已迁运行时伴生上下文
                RuntimeContext runtimeCtx = context.extra(RuntimeContext.class);
                String recordedHash = runtimeCtx != null ? runtimeCtx.getReadFiles().get(storageKey) : null;
                if (recordedHash != null && !recordedHash.isEmpty()
                        && recordedHash.equals(FileHashSupport.computeFileHash(resolved))) {
                    log.debug("权限裁决: ALLOW [已读文件白名单强化放行] - path={}", pathVal);
                    return ToolPermissionVerdict.allow();
                }
            } catch (Exception e) {
                // ignore
            }
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
     * 工具的 {@link CuteTool#getTargetResource(Map, AgentContext)} 由工具自身定义语义（如文件路径、搜索关键词），
     * 相比按 path/command 等固定参数名猜测更可靠；自声明资源为多路径拼接形态（含 " -> "）
     * 时不参与特征匹配（可读性差且无规则匹配价值），跳过由上层兜底逻辑处理。
     * 提取期异常按无声明处理（评估不因资源提取崩溃）。
     * </p>
     */
    private String extractDeclaredResource(CuteTool tool, Map<String, Object> arguments, AgentContext context) {
        try {
            String resource = tool.getTargetResource(arguments, context);
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

    /**
     * 判定路径是否落在允许的沙箱范围内（项目根目录、系统临时目录、用户级配置目录）。
     * <p>
     * 解析失败（非法路径字符等）按越界处理返回 false，安全方向保守。
     * 供层级 2 快速放行前置校验与层级 4 沙箱强拦截共用，保证两处口径一致。
     * </p>
     *
     * @param pathVal 待校验的路径字符串（相对路径以项目根为基准解析）
     * @param context 当前会话上下文（可能为 null）
     * @return true 表示在沙箱内；false 表示越界或解析失败
     */
    private boolean isPathInSandbox(String pathVal, AgentContext context) {
        try {
            Path targetPath = projectService.resolvePath(pathVal, context);
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
            Path userHomeConfig = ContractFile.getGlobalDir().toPath().toAbsolutePath().normalize();

            return targetPath.startsWith(projectRoot)
                    || targetPath.startsWith(tempDir)
                    || targetPath.startsWith(userHomeConfig);
        } catch (Exception e) {
            log.error("沙箱绝对路径转化异常, path={}", pathVal, e);
            return false;
        }
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

    /**
     * 从命令 token 中识别指向破坏性目标的删除操作。
     * <p>
     * 判据是「删除程序 + 破坏性目标」的组合，而非对整条命令串做形态正则：
     * 这样「rm -rf dist」保持放行、「rm -rf /」被拦截，不会因命令长得像 rm -rf 就无差别拒绝。
     * </p>
     * <p>
     * 扫描范围覆盖命令中<b>所有</b>命令位置而非仅首 token：命令可以链式拼接
     * （如 {@code echo x && rm -rf ~}、{@code ls ; rm -rf /}），若只看首 token，
     * 把危险命令接在无害命令之后即可绕过本层拦截。
     * </p>
     * <p>
     * 目标一律取自命令 token 的真实参数（经 {@link #splitCommand} 分词，引号已被剥离）。
     * </p>
     *
     * @param cmdParts 已分词的命令（首元素为程序名）
     * @return 命中的破坏性目标原文；未命中返回 null
     */
    private String findDestructiveDeleteTarget(List<String> cmdParts) {
        // 先规范化 token 序列：粘连写法（如 fs&&rm）会把分隔符与下一个程序名并入同一 token，
        // 不切开就识别不出「命令位置」，危险命令藏在无害命令后会整体漏检
        List<String> tokens = normalizeCommandTokens(cmdParts);
        for (int i = 0; i < tokens.size(); i++) {
            if (!isCommandPosition(tokens, i)) {
                continue;
            }
            String programName = extractProgramName(tokens.get(i));
            if (!DELETE_PROGRAMS.contains(programName)) {
                continue;
            }
            // 仅在该删除程序的参数范围内查找目标，直到遇到下一个命令分隔符为止
            for (int j = i + 1; j < tokens.size(); j++) {
                String arg = tokens.get(j).trim();
                if (isCommandSeparator(arg)) {
                    break;
                }
                if (arg.isEmpty() || isOptionArg(arg, programName)) {
                    continue;
                }
                // 分词阶段已剥离引号，此处再兜一层，防止其它调用路径传入带引号的目标
                String target = stripQuotes(arg);
                if (isDestructiveDeleteTarget(target)) {
                    return target;
                }
            }
        }
        return null;
    }

    /**
     * 将命令 token 序列规范化：把 token 内部粘连的命令分隔符切开为独立 token。
     * <p>
     * 例如 {@code ls&&rm} 拆为 {@code ls}、{@code &&}、{@code rm}，
     * 使后续「命令位置」判定能正确识别出分隔符之后的新命令。
     * </p>
     *
     * @param cmdParts 原始分词结果
     * @return 分隔符已独立成项的 token 序列
     */
    private List<String> normalizeCommandTokens(List<String> cmdParts) {
        List<String> normalized = new ArrayList<>();
        for (String token : cmdParts) {
            StringBuilder buffer = new StringBuilder();
            int cursor = 0;
            while (cursor < token.length()) {
                String matched = matchSeparatorAt(token, cursor);
                if (matched == null) {
                    buffer.append(token.charAt(cursor));
                    cursor++;
                    continue;
                }
                if (buffer.length() > 0) {
                    normalized.add(buffer.toString());
                    buffer.setLength(0);
                }
                normalized.add(matched);
                cursor += matched.length();
            }
            if (buffer.length() > 0) {
                normalized.add(buffer.toString());
            }
        }
        return normalized;
    }

    /**
     * 在 token 的指定位置匹配命令分隔符，按最长优先（{@code &&} 优先于 {@code &}）
     *
     * @param token 待匹配 token
     * @param index 匹配起始位置
     * @return 命中的分隔符；无命中返回 null
     */
    private String matchSeparatorAt(String token, int index) {
        String matched = null;
        for (String separator : COMMAND_SEPARATORS) {
            if (token.startsWith(separator, index)
                    && (matched == null || separator.length() > matched.length())) {
                matched = separator;
            }
        }
        return matched;
    }

    /**
     * 判定 token 是否处于「命令位置」（即真正会被 shell 执行的程序名所在位置）。
     * <p>
     * 命令位置包括：整条命令的首 token；紧跟命令分隔符（{@code &&}、{@code ||}、
     * {@code ;}、{@code |}、{@code &}）之后的 token；以及 {@code sudo} 等前置包装命令之后的 token。
     * </p>
     * <p>
     * 该判定用于排除 {@code git rm}、{@code echo rm -rf /} 这类 token 恰好叫 rm、
     * 但并非真正执行删除的场景，避免将非删除语义误判为破坏性操作。
     * </p>
     *
     * @param cmdParts 已分词的命令
     * @param index    待判定的 token 下标
     * @return 处于命令位置返回 true
     */
    private boolean isCommandPosition(List<String> cmdParts, int index) {
        if (index == 0) {
            return true;
        }
        String previous = cmdParts.get(index - 1).trim();
        return isCommandSeparator(previous) || COMMAND_WRAPPERS.contains(previous.toLowerCase());
    }

    /**
     * 判定 token 是否为命令分隔符或前置包装命令。
     * <p>
     * 分隔符兼容粘写形态（如 {@code a&&b}、{@code a;b}），只要 token 以分隔符结尾即认定为分隔符。
     * </p>
     *
     * @param token 待判定 token
     * @return 是分隔符或前置包装命令返回 true
     */
    private boolean isCommandSeparator(String token) {
        if (COMMAND_SEPARATORS.contains(token)) {
            return true;
        }
        for (String separator : COMMAND_SEPARATORS) {
            if (separator.length() > 1 && token.endsWith(separator)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定删除目标是否为破坏性目标（系统根、驱动器根、主目录及其下任意子路径）。
     * <p>
     * 三重判据：精确命中系统根；形如驱动器根（{@code C:\}）；或以主目录展开语义开头
     * （{@code ~/Documents} 这类须在其未展开形态下拦截，见 {@link #HOME_EXPANSION_PREFIXES}）。
     * </p>
     *
     * @param target 已剥离引号的删除目标
     * @return 属破坏性目标返回 true
     */
    private boolean isDestructiveDeleteTarget(String target) {
        if (DESTRUCTIVE_TARGETS.contains(target) || DRIVE_ROOT_PATTERN.matcher(target).matches()) {
            return true;
        }
        for (String prefix : HOME_EXPANSION_PREFIXES) {
            if (target.equals(prefix) || target.startsWith(prefix + "/") || target.startsWith(prefix + "\\")
                    || target.startsWith(prefix + "*")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提取程序名（去掉目录前缀与 Windows 可执行后缀），统一小写。
     * 兼容 {@code /bin/rm}、{@code C:\tools\rm.exe} 等带路径的写法。
     *
     * @param programToken 命令首 token
     * @return 归一化后的程序名（如 rm、rmdir）
     */
    private String extractProgramName(String programToken) {
        String name = programToken;
        int slashIdx = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slashIdx >= 0 && slashIdx < name.length() - 1) {
            name = name.substring(slashIdx + 1);
        }
        if (name.toLowerCase().endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.toLowerCase();
    }

    /**
     * 判定是否为命令行选项参数，避免把 {@code -rf}、{@code /s} 这类开关误当作删除目标。
     * <p>
     * Windows 风格的 {@code /x} 开关仅在删除类程序下识别，且限定 1~2 个字母，
     * 以免把 {@code /tmp} 这类真实路径误判为开关而漏检。
     * </p>
     *
     * @param arg         参数 token
     * @param programName 归一化程序名
     * @return 是选项返回 true
     */
    private boolean isOptionArg(String arg, String programName) {
        if (arg.startsWith("-")) {
            return true;
        }
        return DELETE_PROGRAMS.contains(programName) && arg.matches("/[a-zA-Z]{1,2}");
    }

    /**
     * 剥离参数两端成对的引号（单引号或双引号）
     *
     * @param value 原始参数
     * @return 去引号结果
     */
    private String stripQuotes(String value) {
        String result = value;
        while (result.length() >= 2
                && ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("'") && result.endsWith("'")))) {
            result = result.substring(1, result.length() - 1);
        }
        return result;
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
            // ** 必须先经占位符保护：若直接 replace("**",".*") 后再做单星替换，
            // 单星替换会把上一步产物里的 "*" 再次殃及替换，导致 "**/*.java" 退化为
            // "只能匹配一层目录"的错误语义（递归白名单静默失效）
            String placeholder = "\u0000DOUBLE_STAR\u0000";
            String regex = pattern
                    .replace(".", "\\.")
                    .replace("**", placeholder)
                    .replace("*", starReplacement)
                    .replace("?", ".")
                    .replace(placeholder, ".*");
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
                        // 三要素任一缺失（含 null 值）的规则直接跳过：避免 {"toolName": null} 类脏数据
                        // 在裁决链 evaluateRules 中触发 NPE 中断整个权限评估
                        if (rObj != null && StringUtils.hasText(rObj.getString("toolName"))
                                && rObj.getString("contentPattern") != null
                                && StringUtils.hasText(rObj.getString("effect"))) {
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
