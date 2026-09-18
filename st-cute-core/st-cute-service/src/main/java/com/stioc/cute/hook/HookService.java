package com.stioc.cute.hook;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.conversation.ConversationService;
import com.stioc.cute.runtime.common.HostExecutorProvider;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.hook.types.HookContext;
import com.stioc.cute.hook.types.HookEventType;
import com.stioc.cute.hook.types.HookRule;
import com.stioc.cute.platform.common.CharsetAwareFileKit;
import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.project.ProjectService;
import com.stioc.cute.runtime.loop.RuntimeContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * 智能体运行事件钩子（Hook）触发与引擎机制：智能体生命周期切面 Hook 触发执行服务
 */
@Slf4j
@Service
public class HookService {

    @Resource
    private ProjectService projectService;
    /**
     * 内存全局静态加载的全局级 Hook 切面规则缓存映射 Map（~/.st-cute/hooks.json）
     */
    private final Map<String, HookRule> globalRulesCache = new ConcurrentHashMap<>();

    private synchronized void loadGlobalHooks() {
        globalRulesCache.clear();
        File globalHooksFile = ContractFile.getGlobalHooksFile();
        if (globalHooksFile != null && globalHooksFile.exists()) {
            loadRulesFromFile(globalHooksFile.toPath(), "GLOBAL", globalRulesCache);
        }
        log.info("生命周期 Hook 全局级配置加载完毕，全局规则: {} 条", globalRulesCache.size());
    }

    /**
     * 动态热装载指定项目工作区下的专属生命周期切面钩子规则。
     * <p>
     * Hook 为叠加型契约，装载顺序：项目级 .st-cute → 项目通用级 .agents → 全局级 ~/.st-cute，
     * 优先级高者先装载、注入在列表前面，三层规则全部生效。
     * </p>
     */
    public synchronized void loadProjectHooks(AgentContext context, String projectBasePath) {
        if (context == null) {
            return;
        }

        RuntimeContext runtimeContext = context.extra(RuntimeContext.class);
        if (runtimeContext == null) {
            return;
        }

        runtimeContext.getHookRules().clear();

        // 1. 项目专属 Hook 规则：项目级 .st-cute → 项目通用级 .agents
        if (StringUtils.hasText(projectBasePath)) {
            ContractFile.forEachProjectFileByPriority(projectBasePath, ContractFile.FILE_HOOKS, projectHooksFile -> {
                Map<String, HookRule> tempCache = new LinkedHashMap<>();
                loadRulesFromFile(projectHooksFile.toPath(), "PROJECT", tempCache, projectBasePath);
                runtimeContext.getHookRules().addAll(tempCache.values());
                log.info("会话 {} 从 {} 成功装载了 {} 个项目专属生命周期 Hook 规则", context.getCid(), projectHooksFile.getParentFile().getName(), tempCache.size());
            });
        }

        // 2. 重新载入全局级 Hook 规则（~/.st-cute/hooks.json），以支持热重载
        loadGlobalHooks();
        runtimeContext.getHookRules().addAll(globalRulesCache.values());
    }

    /**
     * 获取指定会话环境下已加载生效的全部钩子挂钩规则
     */
    public List<HookRule> getHookRules(AgentContext context) {
        if (context != null) {
            RuntimeContext runtimeContext = context.extra(RuntimeContext.class);
            if (runtimeContext != null) {
                return runtimeContext.getHookRules();
            }
        }
        return new ArrayList<>(globalRulesCache.values());
    }

    /**
     * 触发特定切面类型的钩子规则执行流程
     *
     * @param event   事件名（如 on_tool_complete）
     * @param context 运行时上下文
     * @throws Exception 如果阻断型 Hook 执行失败则向外抛出阻断异常
     */
    public void triggerHook(HookEventType event, HookContext context) throws Exception {
        AgentContext loopContext = context.getAgentContext();
        List<HookRule> rules = getHookRules(loopContext);
        if (rules.isEmpty()) {
            return;
        }

        for (HookRule rule : rules) {
            if (event == null || !rule.getEvent().equalsIgnoreCase(event.name())) {
                continue;
            }

            // 1. 过滤工具名
            if (StringUtils.hasText(rule.getToolFilter())) {
                if (context.getToolName() == null || !context.getToolName().equalsIgnoreCase(rule.getToolFilter())) {
                    continue;
                }
            }

            // 2. 过滤文件通配符 (Glob 模式)
            if (StringUtils.hasText(rule.getPattern())) {
                if (context.getFilePath() == null || !matchGlob(context.getFilePath(), rule.getPattern())) {
                    continue;
                }
            }

            // 匹配命中，开始执行
            if (rule.isBlocking()) {
                log.debug("触发强阻断生命周期挂钩 [{}], 事件: {}", rule.getName(), event);
                runHookProcess(rule, context);
            } else {
                log.debug("触发非阻断异步生命周期挂钩 [{}], 事件: {}", rule.getName(), event);
                HostExecutorProvider.submit(() -> {
                    try {
                        runHookProcess(rule, context);
                    } catch (Exception e) {
                        log.error("非阻断 Hook {} 运行异常: {}", rule.getName(), e.getMessage(), e);
                    }
                });
            }
        }
    }

    private void runHookProcess(HookRule rule, HookContext context) throws Exception {
        // 1. 推送事件开始通知
        sendHookEventWs(context.getAgentContext(), rule.getName(), "running", null, context.getToolCallId());

        Path tempJsonFile = null;
        try {
            // 2. 将运行时上下文写成临时 JSON，并通过环境变量交给 Hook 脚本读取。
            // 注意：不能直接 JSON.toJSONString(context)，因为 AgentContext 内部持有 OkHttp Call、
            // MCP 客户端实例、事件监听器等无法被 fastjson2 序列化的复杂引用对象。
            String contextJson = buildHookContextJson(rule, context);
            tempJsonFile = Files.createTempFile("st-cute_hook_" + rule.getName() + "_", ".json");
            Files.writeString(tempJsonFile, contextJson, StandardCharsets.UTF_8);

            // 3. 构建底层可执行命令行与参数
            String command = (String) rule.getArgs().get("command");
            if (!StringUtils.hasText(command)) {
                throw new IllegalArgumentException("缺少执行命令: command 不能为空");
            }

            // 对命令中包含 of ${path} 进行宏替换，若 filePath 为相对路径转换为绝对路径
            if (command.contains("${path}") && context.getFilePath() != null) {
                String absPath = Paths.get(context.getFilePath()).toAbsolutePath().normalize().toString();
                command = command.replace("${path}", absPath);
            }

            log.debug("启动 Hook 子进程, 最终指令: {}", command);

            // 4. 起 ProcessBuilder 并添加 ST_CUTE_HOOK_DATA_PATH 环境变量
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }

            pb.environment().put("ST_CUTE_HOOK_DATA_PATH", tempJsonFile.toAbsolutePath().toString());
            File workingDir = new File(".");
            if (projectService != null && context.getCid() != null) {
                String projectPath = projectService.getProjectBasePathByCid(context.getCid());
                if (StringUtils.hasText(projectPath)) {
                    File pDir = new File(projectPath);
                    if (pDir.exists() && pDir.isDirectory()) {
                        workingDir = pDir;
                    }
                }
            }
            pb.directory(workingDir);

            Process process = pb.start();

            // 5. 60s 强超时控制
            boolean completed = process.waitFor(60, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new TimeoutException("Hook 进程运行超时 (60s) 被强制销毁！");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!StringUtils.hasText(stderr)) {
                    stderr = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                }
                throw new Exception("退出码: " + exitCode + ", 详情: " + stderr);
            }

            // 6. 推送成功事件
            sendHookEventWs(context.getAgentContext(), rule.getName(), "success", null, context.getToolCallId());
            log.debug("Hook 挂钩点 [{}] 执行成功！", rule.getName());

        } catch (Exception e) {
            // 推送失败事件
            sendHookEventWs(context.getAgentContext(), rule.getName(), "failed", e.getMessage(), context.getToolCallId());
            throw e;
        } finally {
            // 7. 清理临时数据文件
            if (tempJsonFile != null) {
                try {
                    Files.deleteIfExists(tempJsonFile);
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    /**
     * 构建安全的 Hook 上下文 JSON，仅提取 Hook 脚本所需的简单类型数据，
     * 避免直接序列化 AgentContext 中无法被 fastjson2 处理的复杂引用对象。
     *
     * @param rule    匹配命中的 Hook 规则
     * @param context Hook 运行时上下文
     * @return 安全可序列化的 JSON 字符串
     */
    private String buildHookContextJson(HookRule rule, HookContext context) {
        JSONObject json = new JSONObject();
        AgentContext agent = context.getAgentContext();

        // 基础会话信息
        json.put("cid", context.getCid());
        json.put("hookName", rule.getName());
        json.put("event", rule.getEvent());
        json.put("blocking", rule.isBlocking());

        // 工具调用元数据
        json.put("toolCallId", context.getToolCallId());
        json.put("toolName", context.getToolName());
        json.put("filePath", context.getFilePath());
        json.put("toolArgs", context.getToolArgs());
        json.put("toolResult", context.getToolResult());

        // 从 AgentContext 中提取安全的基础类型快照
        if (agent != null) {
            json.put("canceled", agent.isCanceled());
            json.put("loopRunning", agent.isLoopRunning());
            json.put("activeAssistantMsgId", agent.getActiveAssistantMsgId());
            json.put("inputTokens", agent.getInputTokens());
            json.put("outputTokens", agent.getOutputTokens());
            json.put("cachedTokens", agent.getCachedTokens());
            json.put("loopCount", agent.getLoopCount());
            json.put("parentCid", agent.getParentCid());
            json.put("permissionMode", agent.getPermissionMode());
            json.put("providerGroup", agent.getProviderGroup());
            json.put("providerModelName", agent.getProviderModelName());
            json.put("workspaceId", agent.getWorkspaceId());
            json.put("callToolCount", agent.getCallToolCount());
            json.put("consecutiveUnknownTools", agent.getConsecutiveUnknownTools());
        }

        return json.toJSONString();
    }

    private void sendHookEventWs(AgentContext context, String hookName, String status, String errorMsg, String toolCallId) {
        if (context == null) {
            return;
        }
        Long cid = context.getCid();

        log.debug("Hook 执行状态变更: name={}, status={}, toolCallId={}, error={}", hookName, status, toolCallId, errorMsg);
    }

    private void loadRulesFromFile(Path path, String source, Map<String, HookRule> targetCache) {
        loadRulesFromFile(path, source, targetCache, null);
    }

    private void loadRulesFromFile(Path path, String source, Map<String, HookRule> targetCache, String projectBasePath) {
        if (!Files.exists(path)) {
            log.info("{} Hook 配置文件不存在，跳过加载: {}", source, path.toAbsolutePath());
            return;
        }

        try {
            String jsonContent = CharsetAwareFileKit.readString(path);
            if (!StringUtils.hasText(jsonContent)) {
                return;
            }

            List<HookRule> rules = JSON.parseArray(jsonContent, HookRule.class);
            if (rules != null) {
                for (HookRule rule : rules) {
                    if (StringUtils.hasText(rule.getName())) {
                        String key = rule.getName();
                        if (projectBasePath != null) {
                            key = projectBasePath + ":" + rule.getName();
                        }
                        targetCache.put(key, rule);
                        log.info("成功载入 Hook 规则 [{}]，来源: {}", rule.getName(), source);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 Hook 配置文件 {} 发生异常", path, e);
        }
    }

    private boolean matchGlob(String text, String pattern) {
        if (text == null || pattern == null) {
            return false;
        }
        // 简易 Glob 校验转换为 Regex (如 *.java -> ^.*\.java$)
        String regex = "^" + pattern.replace(".", "\\.").replace("*", ".*") + "$";
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).matches();
        } catch (Exception e) {
            return text.endsWith(pattern.replace("*", ""));
        }
    }
}
