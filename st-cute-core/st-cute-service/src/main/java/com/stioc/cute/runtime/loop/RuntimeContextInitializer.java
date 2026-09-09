package com.stioc.cute.runtime.loop;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.loop.AgentContextInitializer;
import com.stioc.cute.engine.tool.DynamicToolProvider;
import com.stioc.cute.hook.HookService;
import com.stioc.cute.mcp.McpClientInstance;
import com.stioc.cute.mcp.McpManagerService;
import com.stioc.cute.platform.common.CharsetAwareFileKit;
import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.project.ProjectService;
import com.stioc.cute.skill.SkillManagerService;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.runtime.loop.types.AgentRuleVo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运行时上下文装载器：实现引擎上下文恢复扩展点。
 * <p>
 * 引擎上下文创建/恢复完成时回调，负责初始化 {@link RuntimeContext} 伴生扩展并装载专属资产：
 * 技能（SKILL.md）、Hook 规则（hooks.json）、MCP 客户端、开发规约（AGENTS.md / rules/）。
 * MCP 客户端同步包装为 {@link DynamicToolProvider} 注入引擎上下文供工具注册中心消费。
 * <p>
 * 开发规约（叠加型契约）注入顺序：项目级 → 项目通用级 → 全局级，优先级高者注入在提示词前面。
 * </p>
 */
@Slf4j
@Component
public class RuntimeContextInitializer implements AgentContextInitializer {

    @Resource
    private SkillManagerService skillManagerService;
    @Resource
    private HookService hookService;
    @Resource
    private McpManagerService mcpManagerService;
    @Resource
    private ProjectService projectService;

    /**
     * 子目录规则文件的 enable 元数据开关匹配：仅识别整行形式的 "enable: true/false"
     * （大小写不敏感、容忍缩进），未声明或格式非法时默认视为启用
     */
    private static final Pattern RULE_ENABLE_PATTERN =
            Pattern.compile("^\\s*enable\\s*:\\s*(true|false)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    @Override
    public void onContextRestored(AgentContext context) {
        String projectBasePath = projectService != null ? projectService.getProjectBasePath(context) : null;
        Long cid = context.getCid();
        // 1. 注册伴生上下文并注入 AgentContext（同体生命周期）
        RuntimeContext runtimeCtx = context.extra(RuntimeContext.class);
        if (runtimeCtx == null) {
            runtimeCtx = new RuntimeContext(cid);
            context.putExtraContext(RuntimeContext.class, runtimeCtx);
        }

        // 2. 动态装载专属 Skills 并装配到伴生上下文中
        if (skillManagerService != null && StringUtils.hasText(projectBasePath)) {
            skillManagerService.loadProjectSkills(context, projectBasePath);
        }

        // 3. 动态装载专属 Hook 规则并装配到伴生上下文中
        if (hookService != null && StringUtils.hasText(projectBasePath)) {
            hookService.loadProjectHooks(context, projectBasePath);
        }

        // 4. 动态扫描并拉起专属 MCP 服务进程并装配到伴生上下文中
        if (mcpManagerService != null && StringUtils.hasText(projectBasePath)) {
            mcpManagerService.loadAndStartForContext(context, projectBasePath);
        }

        // 5. 动态装载专属开发规约（AGENTS.md / rules/，无项目时仅装载全局规约）
        loadContextRules(runtimeCtx, projectBasePath);

        log.debug("会话 {} 运行时伴生资产装载完成: skills={}, hooks={}, mcp={}, rules={}",
                cid, runtimeCtx.getSkills().size(), runtimeCtx.getHookRules().size(),
                runtimeCtx.getMcpClients().size(), runtimeCtx.getRules().size());
    }

    /**
     * 装载全局与项目开发规约到伴生上下文。
     * <p>
     * 叠加型契约注入顺序：项目级 → 项目通用级 → 全局级，优先级高者注入在列表/提示词前面。
     * </p>
     */
    private void loadContextRules(RuntimeContext runtimeCtx, String projectBasePath) {
        runtimeCtx.getRules().clear();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        // 1. 项目开发指令：项目级 .st-cute → 项目通用级 .agents
        if (StringUtils.hasText(projectBasePath)) {
            // 1.1 主规约文件：AGENTS.md
            ContractFile.forEachProjectFileByPriority(projectBasePath, ContractFile.FILE_AGENTS, projectFile -> {
                if (projectFile.isFile()) {
                    try {
                        String content = CharsetAwareFileKit.readString(projectFile.toPath());
                        if (StringUtils.hasText(content)) {
                            runtimeCtx.getRules().add(AgentRuleVo.builder()
                                    .name("项目级 (" + projectFile.getParentFile().getName() + ")")
                                    .path(projectFile.getAbsolutePath().replace("\\", "/"))
                                    .updateTime(sdf.format(new Date(projectFile.lastModified())))
                                    .size(projectFile.length())
                                    .content(content)
                                    .build());
                        }
                    } catch (Exception e) {
                        log.error("装载项目级开发指令与规范失败: " + projectFile.getAbsolutePath(), e);
                    }
                }
            });

            // 1.2 rules/ 子目录规则集：一文件一规则，支持 enable 元数据启停
            ContractFile.forEachProjectFileByPriority(projectBasePath, ContractFile.DIR_RULES, rulesDir -> {
                if (rulesDir.isDirectory()) {
                    loadProjectSubRules(rulesDir, sdf, runtimeCtx, "项目规则");
                }
            });
        }

        // 2. 全局级开发指令：~/.st-cute/AGENTS.md（编码感知读取，防本地 ANSI 编码文件静默乱码注入）
        File globalFile = ContractFile.getGlobalAgentsFile();
        if (globalFile != null && globalFile.exists() && globalFile.isFile()) {
            try {
                String content = CharsetAwareFileKit.readString(globalFile.toPath());
                if (StringUtils.hasText(content)) {
                    runtimeCtx.getRules().add(AgentRuleVo.builder()
                            .name("全局级 (Global)")
                            .path(globalFile.getAbsolutePath().replace("\\", "/"))
                            .updateTime(sdf.format(new Date(globalFile.lastModified())))
                            .size(globalFile.length())
                            .content(content)
                            .build());
                }
            } catch (Exception e) {
                log.error("装载全局级开发指令与规范失败", e);
            }
        }

        // 3. 全局级规则集：~/.st-cute/rules/ 一文件一规则（enable 元数据启停机制与项目级一致）
        File globalRulesDir = new File(ContractFile.getGlobalDir(), ContractFile.DIR_RULES);
        if (globalRulesDir.exists() && globalRulesDir.isDirectory()) {
            loadProjectSubRules(globalRulesDir, sdf, runtimeCtx, "全局规则");
        }
    }

    /**
     * 递归扫描装载 rules/ 子目录下的分区规则文件（一文件一规则）。
     * <p>
     * 按目录步进递归收集 *.md 文件并排序，保证装载顺序稳定可预期；
     * 规则文件可包含 "enable: true/false" 元数据行控制启停（由用户手动编辑维护），缺省视为启用。
     * </p>
     */
    private void loadProjectSubRules(File rulesDir, SimpleDateFormat sdf, RuntimeContext runtimeCtx, String scopeLabel) {
        File[] children = rulesDir.listFiles();
        if (children == null || children.length == 0) {
            return;
        }
        Arrays.sort(children, Comparator.comparing(File::getName));
        String configDirName = rulesDir.getParentFile() != null ? rulesDir.getParentFile().getName() : "rules";
        for (File child : children) {
            if (child.isDirectory()) {
                // 递归步进子目录，逐层解释扫描日志
                log.info("扫描规则子目录: {}", child.getAbsolutePath());
                loadProjectSubRules(child, sdf, runtimeCtx, scopeLabel);
            } else if (child.isFile() && child.getName().toLowerCase().endsWith(".md")) {
                loadSingleRuleFile(child, configDirName, sdf, runtimeCtx, scopeLabel);
            }
        }
    }

    /**
     * 装载单个子目录规则文件：解析 enable 元数据开关（缺省视为启用），
     * 装载生成的规则命名带完整相对路径段与文件名，保证嵌套层级下前端展示与大模型提示词均可辨识来源；
     * scopeLabel 标识来源层级（如 "项目规则"/"全局规则"），注入顺序按层级优先级排定
     */
    private void loadSingleRuleFile(File ruleFile, String configDirName, SimpleDateFormat sdf, RuntimeContext runtimeCtx, String scopeLabel) {
        try {
            String content = CharsetAwareFileKit.readString(ruleFile.toPath());
            if (!StringUtils.hasText(content)) {
                return;
            }
            // 解析 enable 元数据开关（缺省视为启用）
            boolean enabled = true;
            Matcher matcher = RULE_ENABLE_PATTERN.matcher(content);
            if (matcher.find()) {
                enabled = Boolean.parseBoolean(matcher.group(1));
            }
            if (!enabled) {
                log.info("子目录规则 [{}] 已声明 enable: false，跳过装载", ruleFile.getName());
                return;
            }
            // 规则相对路径：rules 目录下的相对路径段（含文件名），嵌套层级用 / 串联
            String relativePath = rulesDirPath(configDirName, ruleFile);
            log.info("成功装载{} [{}]", scopeLabel, relativePath);
            runtimeCtx.getRules().add(AgentRuleVo.builder()
                    .name(scopeLabel + " (" + relativePath + ")")
                    .path(ruleFile.getAbsolutePath().replace("\\", "/"))
                    .updateTime(sdf.format(new Date(ruleFile.lastModified())))
                    .size(ruleFile.length())
                    .content(content)
                    .build());
        } catch (Exception e) {
            log.error("装载子目录规则文件失败: " + ruleFile.getAbsolutePath(), e);
        }
    }

    /**
     * 计算规则文件相对 rules 根目录的路径段：由文件父目录逐级上溯至 rules 根目录，
     * 嵌套层级用 / 串联（如 rules/backend/api.md → backend/api.md），防全量路径泄漏用户盘符目录结构
     */
    private String rulesDirPath(String configDirName, File ruleFile) {
        String currentName = ruleFile.getName();
        File parent = ruleFile.getParentFile();
        while (parent != null && !configDirName.equals(parent.getName())) {
            currentName = parent.getName() + "/" + currentName;
            parent = parent.getParentFile();
        }
        return currentName;
    }

    /**
     * 将伴生上下文中的 MCP 客户端实例包装为引擎动态工具提供者并注入引擎上下文。
     * （由 McpManagerService 装载完成 MCP 后调用，也可在 MCP 变更时重复调用刷新）
     */
    public static void syncDynamicToolProviders(AgentContext context) {
        if (context == null) {
            return;
        }
        RuntimeContext runtimeCtx = context.extra(RuntimeContext.class);
        if (runtimeCtx == null) {
            return;
        }
        List<DynamicToolProvider> providers = new ArrayList<>();
        for (McpClientInstance client : runtimeCtx.getMcpClients().values()) {
            providers.add(new DynamicToolProvider() {
                @Override
                public String getName() {
                    return client.getName();
                }

                @Override
                public boolean isRunning() {
                    return "RUNNING".equals(client.getStatus());
                }

                @Override
                public List<CuteTool> getExposedTools() {
                    return client.getExposedTools();
                }
            });
        }
        context.replaceDynamicToolProviders(providers);
    }
}
