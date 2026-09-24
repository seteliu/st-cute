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

import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.platform.security.DesktopTokenStore;
import com.stioc.cute.engine.loop.core.AgentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.stioc.cute.project.ProjectService;
import com.stioc.cute.runtime.loop.RuntimeContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.annotation.Resource;

/**
 * 权限服务：执行多层级安全规则流水线，作出 Allow / Deny / Ask 裁决。
 * <p>
 * 对外契约：{@link #evaluateVerdict(CuteTool, Map, AgentContext)} 评估工具权限返回强类型裁决，
 * {@link #evaluate(CuteTool, Map, AgentContext)} 为兼容命名别名；
 * {@link #writeLocalPermissionRule(PermissionRule)} 与 {@link #writeLocalPermissionRule(PermissionRule, String)}
 * 向全局/项目工作区本地配置写入持久化权限授信规则。
 * </p>
 * <p>
 * 本类只负责「裁决编排」：命令词法分析与危险形态判定委托 {@link CommandInspector}，
 * 规则文件的合并读取、元数据指纹缓存与写盘委托 {@link PermissionRuleStore}。
 * 二者均为纯职责组件，不参与裁决决策。
 * </p>
 * <p>
 * 规则文件读取策略：合并结果按「项目根路径」缓存，并以构成该结果的三个文件（全局级 / 项目级 /
 * 本地级）的元数据指纹（mtime + 长度）作自洽校验——指纹一致复用缓存，指纹变化（用户手工编辑、
 * 新建或删除配置、热重载）自动重读。故权限规则的更新时机收敛为「装载 + 权限文件改写」两处，
 * 常规工具调用不再重复读盘；本地规则写盘后主动失效缓存，保证「总是放行」当次立即生效。
 * </p>
 */
@Slf4j
@Service
public class PermissionService {

    @Resource
    private ProjectService projectService;
    @Resource
    private ContractProperty contractProperty;
    @Resource
    private PermissionRuleStore permissionRuleStore;

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

        // 路径沙箱开关：层级 2 的 cwd 前置防线与层级 4 的沙箱强拦截共用同一开关口径，
        // 关闭（pathSandboxEnabled=false）时两处均不拦截——cwd 出项目的限制只在开启路径沙箱保护后才生效
        boolean pathSandboxEnabled = contractProperty == null || contractProperty.isPathSandboxEnabled();

        // 权限模式一次性解析：层级 2 / 5.5 / 6 三处模式门槛共用同一解析结果
        //（permissionMode 已字符串化：宿主侧按需解析为枚举，null 会安全兜底为 STRICT_APPROVAL）
        PermissionMode mode = PermissionMode.fromName(context != null ? context.getPermissionMode() : null);

        // 层级 2: 安全只读命令快速放行（防元字符旁路）
        // 模式门槛（与前端三档语义对齐）：快速放行仅面向宽松审批与全部放行——
        // 宽松审批语义为「开放文件读写与常用安全命令直接执行」，此白名单正是「常用安全命令」的实现载体；
        // 严格审批语义为「写操作与命令执行均需审批」，白名单命令也必须落入层级 6 转 ASK，不得在此豁免
        boolean safeCommandFastPathEnabled = mode != PermissionMode.STRICT_APPROVAL;
        if (safeCommandFastPathEnabled
                && (ToolNames.EXECUTE_COMMAND.equalsIgnoreCase(toolName)) && StringUtils.hasText(commandVal)) {
            // 前置防线：快速放行前必须先校验 cwd 落沙箱（仅沙箱开启时）。否则 execute_command(command="ls", cwd="C:/Windows/...")
            // 会在沙箱检查之前命中白名单直接 ALLOW，越界工作目录从未被校验（cwd 参数不在 path 参数提取链内）
            if (pathSandboxEnabled && StringUtils.hasText(cwdVal) && !isPathInSandbox(cwdVal, context)) {
                log.warn("权限裁决: DENY [安全命令快速放行被越界 cwd 阻断] - cwd={}", cwdVal);
                return ToolPermissionVerdict.deny("命令工作目录(cwd)路径越界。禁止访问项目根目录或临时目录外的系统敏感路径: " + cwdVal);
            }
            if (!CommandInspector.containsShellMetaCharacters(commandVal)) {
                String cmdTrim = commandVal.trim();
                boolean isSafePrefix = false;
                for (String prefix : SAFE_COMMAND_PREFIXES) {
                    if (cmdTrim.equals(prefix) || cmdTrim.startsWith(prefix + " ") || cmdTrim.startsWith(prefix + "\t")) {
                        isSafePrefix = true;
                        break;
                    }
                }
                if (isSafePrefix) {
                    log.debug("权限裁决: ALLOW [安全只读命令快速放行] - mode={}", mode);
                    return ToolPermissionVerdict.allow();
                }
            }
        }

        // 层级 3: 危险命令黑名单硬拦截（DENY，高优先级，ALL_ALLOW 模式也得拦）
        if ((ToolNames.EXECUTE_COMMAND.equalsIgnoreCase(toolName)) && StringUtils.hasText(commandVal)) {
            List<String> cmdParts = CommandInspector.splitCommand(commandVal);
            if (cmdParts.isEmpty()) {
                return ToolPermissionVerdict.deny("命令内容为空。");
            }

            // 危险形态判定（破坏性删除目标 + 形态黑名单）整体委托 CommandInspector：
            // 判据细节见其类注释，此处只负责把命中结果转成拒绝裁决
            CommandInspector.DangerFinding finding = CommandInspector.inspectDanger(commandVal);
            if (finding != null) {
                log.warn("权限裁决: DENY [危险命令拦截] - command={}, reason={}", commandVal, finding.reason());
                return ToolPermissionVerdict.deny(finding.reason());
            }
        }

        // 层级 4: 路径沙箱强拦截（只限文件读写类工具及终端命令参数中的物理路径访问）
        // 工具实例由引擎直接传入（原先经 resolveTool 反查注册中心，现随评估调用一并下发，权限层不再反向依赖引擎）
        CuteTool toolInstance = tool;
        boolean isFileTool = toolInstance != null && toolInstance.getAccessLevel() != ToolAccessLevel.SENSITIVE;
        boolean isWriteOrModify = toolInstance != null && toolInstance.getAccessLevel() == ToolAccessLevel.WRITE;
        boolean isSensitiveTool = toolInstance != null && toolInstance.getAccessLevel() == ToolAccessLevel.SENSITIVE;
        boolean isExecuteCommand = ToolNames.EXECUTE_COMMAND.equalsIgnoreCase(toolName);

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
                List<String> cmdParts = CommandInspector.splitCommand(commandVal);
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
        List<PermissionRule> rules = permissionRuleStore.loadAll(projectBasePath);
        ToolPermissionVerdict ruleVerdict = evaluatePermissions(rules, toolName, targetContent);
        if (ruleVerdict != null) {
            log.debug("权限裁决: {} [三级配置规则命中]", ruleVerdict);
            return ruleVerdict;
        }

        // 层级 5.5: 已读文件白名单强化（read_file 读过且内容哈希仍与磁盘一致的文件，其 Write/Modify 直接 ALLOW 放行）。
        // 仅授给写级工具：敏感级（删除/命令）即使先读过也必须走完整审批链，防止严格审批模式下"先读后删"被静默放行。
        // 模式门槛：严格审批语义为「写操作与命令执行均需审批」，先读后写的便利性放行仅面向宽松/全部放行档；
        // 刻意置于规则层（层级 5）之后评估：用户显式配置的 DENY/ASK 规则必须优先于此白名单生效，
        // 防止"先 read_file 再修改"的组合绕过用户明确表达的拒绝意图（历史上先于此层评估存在绕过隐患）
        if (isWriteOrModify && !isSensitiveTool && StringUtils.hasText(pathVal)
                && mode != PermissionMode.STRICT_APPROVAL) {
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
                // 异常时安全方向是保守的（不放行，继续走后续审批链），但不能静默吞掉：
                // resolvePath 配置错误、RuntimeContext 缺失等真实故障需留痕可查，故降级为 debug 记录
                log.debug("已读文件白名单强化评估失败，本次不放行：path={}, 异常={}", pathVal, e.getMessage());
            }
        }

        // 层级 6: 矩阵四档权限兜底决策 (只产 ALLOW 或 ASK，不产 DENY)
        String category = getToolCategory(toolInstance);

        if ("readonly".equalsIgnoreCase(category)) {
            log.debug("权限裁决: ALLOW [矩阵兜底-只读工具]");
            return ToolPermissionVerdict.allow();
        }

        if (mode == PermissionMode.ALL_ALLOW) {
            log.debug("权限裁决: ALLOW [矩阵兜底-全部放行模式]");
            return ToolPermissionVerdict.allow();
        }

        if (mode == PermissionMode.RELAXED_APPROVAL && "filewrite".equalsIgnoreCase(category)) {
            log.debug("权限裁决: ALLOW [矩阵兜底-宽松审批下的文件写入直接放行]");
            return ToolPermissionVerdict.allow();
        }

        // 严格审批模式下的写与命令，以及宽松审批下的终端命令，均进入人在回路 Ask
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
        // 敏感级工具与未知工具一样按命令类治理：宽松审批不放行，进入人工审批
        return "command";
    }

    /**
     * 判定路径是否落在允许的沙箱范围内（项目根目录、系统临时目录、用户级配置目录）。
     * <p>
     * 解析失败（非法路径字符等）按越界处理返回 false，安全方向保守。
     * 供层级 2 快速放行前置校验与层级 4 沙箱强拦截共用，保证两处口径一致。
     * </p>
     * <p>
     * <b>用户级配置目录的凭证排除</b>：全局目录同时存放着敏感凭证载体
     * （config.json 内含各供应商 apiKey 明文、.desktop-token 为桌面端停机凭证），
     * 二者若可被 READ 级工具（免审批）读取，即构成「读凭证 → 调 /api/shutdown」的
     * 提示注入权限提升链，故在此显式排除，不放其进入沙箱。
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

            // 物理路径复核：软链接（Windows 上的 junction 普通用户即可创建）能让
            // 字面路径落在沙箱内、实际内容却在沙箱外，仅靠 normalize 的纯字面比较判不出来。
            // 此处将目标与各沙箱根一并展开为真实物理路径后再比较，与 SearchSandboxGuard 口径一致
            Path realTarget = toRealPathSafe(targetPath);
            Path realProjectRoot = toRealPathSafe(projectRoot);
            Path realTempDir = toRealPathSafe(tempDir);
            Path realUserHomeConfig = toRealPathSafe(userHomeConfig);

            if (realTarget == null) {
                return false;
            }

            // 凭证排除在物理路径上判定：软链别名指向凭证文件同样须被拒绝
            if (isSensitiveCredentialPath(realTarget, realUserHomeConfig)) {
                log.warn("拒绝访问敏感凭证文件（不在沙箱允许范围内）: {}", realTarget);
                return false;
            }

            return realTarget.startsWith(realProjectRoot)
                    || realTarget.startsWith(realTempDir)
                    || realTarget.startsWith(realUserHomeConfig);
        } catch (Exception e) {
            log.error("沙箱绝对路径转化异常, path={}", pathVal, e);
            return false;
        }
    }

    /**
     * 将路径转换为真实的物理规范路径（展开软链接与 Windows 8.3 短路径名），消除别名判定失真。
     * <p>
     * 路径不存在时回退到 canonicalPath（同样会展开已存在部分的软链接），
     * 全部失败才退回绝对路径归一化，保证任何情况下都返回可比较的路径。
     * </p>
     *
     * @param path 待转换路径
     * @return 物理规范路径；入参为 null 时返回 null
     */
    private Path toRealPathSafe(Path path) {
        if (path == null) {
            return null;
        }
        try {
            if (Files.exists(path)) {
                return path.toRealPath();
            }
            return Paths.get(path.toFile().getCanonicalPath());
        } catch (Exception e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /**
     * 判定目标路径是否为不允许进入沙箱的敏感凭证文件。
     * <p>
     * 覆盖两种载体：全局目录下的 config.json（含 apiKey 明文）与桌面端凭证文件 .desktop-token。
     * 比对同时覆盖规范化后的绝对路径与其父目录形态，防止通过 <code>子目录/../config.json</code>
     * 之类的等价写法绕过字面路径比较。
     * </p>
     *
     * @param targetPath     已归一化的目标绝对路径
     * @param userHomeConfig 已归一化的用户级配置目录路径
     * @return true 表示属于敏感凭证文件
     */
    private boolean isSensitiveCredentialPath(Path targetPath, Path userHomeConfig) {
        Path globalConfigFile = ContractFile.getGlobalConfigJsonFile().toPath().toAbsolutePath().normalize();
        Path desktopTokenFile = userHomeConfig.resolve(DesktopTokenStore.TOKEN_FILE_NAME).normalize();
        return targetPath.equals(globalConfigFile) || targetPath.equals(desktopTokenFile);
    }

    /**
     * 将放行规则写入本地级配置文件（转发 {@link PermissionRuleStore}）。
     *
     * @param rule 待写入的规则
     */
    public void writeLocalPermissionRule(PermissionRule rule) {
        permissionRuleStore.writeLocalRule(rule);
    }

    /**
     * 将放行规则写入指定项目的本地级配置文件（转发 {@link PermissionRuleStore}）。
     *
     * @param rule            待写入的规则
     * @param projectBasePath 项目根路径（null 表示无项目归属）
     */
    public void writeLocalPermissionRule(PermissionRule rule, String projectBasePath) {
        permissionRuleStore.writeLocalRule(rule, projectBasePath);
    }


    /**
     * 评估权限规则：末条优先，支持 Glob
     */
    private ToolPermissionVerdict evaluatePermissions(List<PermissionRule> rules, String toolName, String targetContent) {
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
            // 正则元字符全量转义：仅转义 "." 而放任 | + ( ) { } [ ] ^ $ \ 原样进入正则，
            // 会使「总是放行」写入的命令模式（取自命令原文，可能含 | 等字符）被当作正则语法解析，
            // 语义从「字面匹配该命令」漂移为「匹配其任一分支」——偏差方向是放宽权限。
            // 故先整体转义元字符，再对通配符做定向还原
            String escaped = pattern.replaceAll("([.\\\\+()\\[\\]{}^$|])", "\\\\$1");
            // 占位符保护 **：若直接替换单星会殃及上一步产物，使 "**/*.java" 退化为
            // "只能匹配一层目录"的错误语义（递归白名单静默失效），故先占位再统一还原
            String placeholder = "\u0000DOUBLE_STAR\u0000";
            // 如果是命令执行工具，星号通配符应该支持跨目录匹配（即匹配命令中的任意字符，包括斜杠）
            String starReplacement = ToolNames.EXECUTE_COMMAND.equalsIgnoreCase(toolName) ? ".*" : "[^/]*";
            String regex = escaped
                    .replace("**", placeholder)
                    .replace("*", starReplacement)
                    .replace("?", ".")
                    .replace(placeholder, ".*");
            return Pattern.compile("^" + regex + "$", Pattern.CASE_INSENSITIVE).matcher(content).matches();
        } catch (Exception e) {
            return false;
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
