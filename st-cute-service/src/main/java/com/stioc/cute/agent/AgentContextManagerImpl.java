package com.stioc.cute.agent;

import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.agent.access.AgentContextManager;
import com.stioc.cute.agent.access.ActiveProcess;
import com.stioc.cute.agent.event.AgentEventListener;
import okhttp3.Call;
import com.stioc.cute.conversation.access.ConversationEntity;
import com.stioc.cute.conversation.access.ConversationService;
import com.stioc.cute.hook.access.HookEngineService;
import com.stioc.cute.mcp.access.McpManagerService;
import com.stioc.cute.project.access.ProjectService;
import com.stioc.cute.security.access.PermissionMode;
import com.stioc.cute.skill.access.SkillManagerService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import com.stioc.cute.agent.access.AgentRuleVo;
import com.stioc.cute.platform.contract.ContractFile;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 管理内存中所有的活动智能体运行上下文环境
 */
@Slf4j
@Component
public class AgentContextManagerImpl implements AgentContextManager {

    @Resource
    private List<AgentEventListener> eventListeners;
    @Resource
    private ConversationService conversationService;
    @Resource
    private ProjectService projectService;
    @Resource
    private SkillManagerService skillManagerService;
    @Resource
    private HookEngineService hookEngineService;
    @Resource
    private McpManagerService mcpManagerService;

    /**
     * 内存活动上下文缓存容器 Map
     */
    private final ConcurrentHashMap<Long, AgentContext> contexts = new ConcurrentHashMap<>();

    /**
     * 获取或创建指定会话的运行上下文
     */
    public AgentContext getOrCreateContext(Long cid) {
        if (cid == null) {
            cid = 0L;
        }
        // 快速路：直接从 Map 中读取，不进行锁或数据库查询
        AgentContext existing = contexts.get(cid);
        if (existing != null) {
            return existing;
        }

        // 慢速路：内存中尚无此上下文，需要初始化创建
        return contexts.computeIfAbsent(cid, c -> {
            AgentContext ctx = new AgentContext(c);

            // 1. 从数据库加载已持久化的窗口 Token 快照并动态装配项目级 Skills, Hook, MCP
            try {
                if (conversationService != null) {
                    conversationService.findById(c).ifPresent(conv -> {
                        // 恢复 Token 计数
                        if (conv.getInputTokens() != null) {
                            ctx.setInputTokens(conv.getInputTokens());
                        }
                        if (conv.getOutputTokens() != null) {
                            ctx.setOutputTokens(conv.getOutputTokens());
                        }
                        if (conv.getCachedTokens() != null) {
                            ctx.setCachedTokens(conv.getCachedTokens());
                        }

                        // 恢复 权限模式 与 供应商信息
                        if (conv.getPermissionMode() != null) {
                            try {
                                ctx.setPermissionMode(PermissionMode.fromName(conv.getPermissionMode()));
                            } catch (Exception ex) {
                                ctx.setPermissionMode(PermissionMode.READ_ONLY);
                            }
                        } else {
                            ctx.setPermissionMode(PermissionMode.READ_ONLY);
                        }

                        if (conv.getProviderGroup() != null) {
                            ctx.setProviderGroup(conv.getProviderGroup());
                        }
                        if (conv.getProviderModelName() != null) {
                            ctx.setProviderModelName(conv.getProviderModelName());
                        }

                        // 恢复迭代轮次计数，供进程重启后继续执行时判断是否超过上限
                        if (conv.getIterationCount() != null && conv.getIterationCount() > 0) {
                            ctx.setIterationCount(conv.getIterationCount());
                        }
                        if (conv.getParentCid() != null) {
                            ctx.setParentCid(conv.getParentCid());
                            // 继承父智能体在内存中已解锁的按需工具集合，打破外部工具类的强耦合
                            AgentContext parentCtx = contexts.get(conv.getParentCid());
                            if (parentCtx != null) {
                                ctx.getUnlockedTools().addAll(parentCtx.getUnlockedTools());
                            }
                        }

                        // 恢复 LoopRunning 运行状态
                        if (conv.getLoopRunning() != null) {
                            ctx.setLoopRunning(conv.getLoopRunning() == 1);
                        }

                        // 恢复 Worktree 绑定路径与分支（工具执行 cwd 重定向依赖此值）
                        if (conv.getWorktreePath() != null) {
                            ctx.setWorktreePath(conv.getWorktreePath());
                        }
                        if (conv.getWorktreeBranch() != null) {
                            ctx.setWorktreeBranch(conv.getWorktreeBranch());
                        }

                        // 恢复已解锁的按需工具集合
                        if (conv.getUnlockedToolNames() != null && !conv.getUnlockedToolNames().isBlank()) {
                            for (String name : conv.getUnlockedToolNames().split(",")) {
                                String trimmed = name.trim();
                                if (!trimmed.isEmpty()) {
                                    ctx.getUnlockedTools().add(trimmed);
                                }
                            }
                        }

                        log.info("成功从数据库初始化会话 {} 的窗口 Token 快照和权限与供应商: input={}, output={}, mode={}",
                                c, ctx.getInputTokens(), ctx.getOutputTokens(), ctx.getPermissionMode());

                        // 懒加载当前会话绑定项目专属的 MCP、SKILL、HOOK 资产并原生装配进 context 中
                        boolean loaded = false;
                        if (conv.getProjectId() != null) {
                            if (projectService != null) {
                                var projOpt = projectService.findById(conv.getProjectId());
                                if (projOpt.isPresent()) {
                                    String projectBasePath = projOpt.get().getPath();
                                    if (StringUtils.hasText(projectBasePath)) {
                                        loadContextAssets(ctx, projectBasePath);
                                        loaded = true;
                                    }
                                }
                            }
                        }
                        if (!loaded) {
                            // 若没有绑定任何项目，也应为其装载全局开发规约
                            loadContextRules(ctx, null);
                        }
                    });
                }
            } catch (Exception e) {
                log.error("从数据库初始化会话或装配项目级资产失败: cid={}", c, e);
            }

            if (eventListeners != null) {
                eventListeners.forEach(ctx::registerListener);
            }
            return ctx;
        });
    }

    @Override
    public AgentContext getActiveContext(Long cid) {
        if (cid == null) {
            return null;
        }
        return contexts.get(cid);
    }

    /**
     * 动态装配或重新装配会话绑定的项目专属资产（Skills, Hooks, MCP）
     */
    public void loadContextAssets(AgentContext context, String projectBasePath) {
        if (context == null || !StringUtils.hasText(projectBasePath)) {
            return;
        }
        log.debug("会话 {} 装配专属项目资产，路径: {}", context.getCid(), projectBasePath);

        // 1. 动态装载专属 Skills 并装配到 context 对象中
        if (skillManagerService != null) {
            skillManagerService.loadProjectSkills(context, projectBasePath);
        }

        // 2. 动态装载专属 Hook 规则并装配到 context 对象中
        if (hookEngineService != null) {
            hookEngineService.loadProjectHooks(context, projectBasePath);
        }

        // 3. 动态扫描并拉起专属 MCP 服务进程并装配到 context 对象中
        if (mcpManagerService != null) {
            mcpManagerService.loadAndStartForContext(context, projectBasePath);
        }

        // 4. 动态装载专属开发规约并装配到 context 内存对象中
        loadContextRules(context, projectBasePath);
    }

    private void loadContextRules(AgentContext context, String projectBasePath) {
        context.getRules().clear();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        // 1. 全局开发指令：~/.st-cute/AGENTS.md
        File globalFile = ContractFile.getGlobalAgentsFile();
        if (globalFile != null && globalFile.exists() && globalFile.isFile()) {
            try {
                context.getRules().add(AgentRuleVo.builder()
                        .name("全局级 (Global)")
                        .path(globalFile.getAbsolutePath().replace("\\", "/"))
                        .updateTime(sdf.format(new Date(globalFile.lastModified())))
                        .size(globalFile.length())
                        .content(Files.readString(globalFile.toPath(), StandardCharsets.UTF_8))
                        .build());
            } catch (Exception e) {
                log.error("装载全局开发指令与规范失败", e);
            }
        }

        // 2. 项目级开发指令：从共存目录寻找
        if (StringUtils.hasText(projectBasePath)) {
            ContractFile.forEachProjectFile(projectBasePath, ContractFile.FILE_AGENTS, projectFile -> {
                if (projectFile.isFile()) {
                    try {
                        context.getRules().add(AgentRuleVo.builder()
                                .name("项目级 (" + projectFile.getParentFile().getName() + ")")
                                .path(projectFile.getAbsolutePath().replace("\\", "/"))
                                .updateTime(sdf.format(new Date(projectFile.lastModified())))
                                .size(projectFile.length())
                                .content(Files.readString(projectFile.toPath(), StandardCharsets.UTF_8))
                                .build());
                    } catch (Exception e) {
                        log.error("装载项目级开发指令与规范失败: " + projectFile.getAbsolutePath(), e);
                    }
                }
            });
        }
    }

    /**
     * 用户发出强行中止信号，标记当前上下文已取消
     */
    public void cancelContext(Long cid) {
        AgentContext context = contexts.get(cid);
        if (context != null) {
            context.setCanceled(true);

            // 1. 强杀此会话的所有外部物理子进程
            context.getActiveProcesses().forEach((toolCallId, activeProcess) -> {
                try {
                    log.debug("强制杀掉会话 {} 的子进程树: ToolCallId={}, Command={}", cid, toolCallId, activeProcess.getCommand());
                    activeProcess.destroyForcibly();
                } catch (Exception e) {
                    log.error("强杀进程异常", e);
                }
            });
            context.getActiveProcesses().clear();

            // 2. 强退大模型 HTTP 连接
            try {
                Call activeCall = context.getActiveLlmCall();
                if (activeCall != null && !activeCall.isCanceled()) {
                    log.debug("正在强制取消会话 {} 的主大模型调用", cid);
                    activeCall.cancel();
                }
            } catch (Exception e) {
                log.error("强退网络连接异常", e);
            }
            context.setActiveLlmCall(null);

            // 强退此会话下注册的所有活动网络调用 (如重命名或上下文摘要等同步连接)
            context.getActiveLlmCalls().forEach((llmCallId, activeCall) -> {
                try {
                    Call call = activeCall.getCall();
                    if (call != null && !call.isCanceled()) {
                        log.debug("正在强制取消会话 {} 的活动大模型请求, llmCallId={}, Model={}", cid, llmCallId, activeCall.getModel());
                        call.cancel();
                    }
                } catch (Exception e) {
                    log.error("强退活动网络连接异常", e);
                }
            });
            context.getActiveLlmCalls().clear();

            // 3. 中断执行线程
            Thread activeThread = context.getActiveThread();
            if (activeThread != null && activeThread.isAlive()) {
                log.debug("向活跃执行线程 {} 发送中断信号以取消会话 {}", activeThread.getName(), cid);
                activeThread.interrupt();
            }
            log.debug("运行上下文已被用户标记取消: {}", cid);

            // 4. 级联递归取消名下的并发子代理会话的运行
            contexts.values().forEach(child -> {
                if (cid.equals(child.getParentCid())) {
                    log.debug("级联取消子会话: {}", child.getCid());
                    cancelContext(child.getCid());
                }
            });
        }
    }

    /**
     * 移除会话运行上下文并清理专属项目资源
     */
    public void removeContext(Long cid) {
        // 1. 强制清理本会话（以及下属子会话）的所有物理进程、Call 等副作用
        cancelContext(cid);

        // 2. 级联移除所有子会话上下文缓存并关闭其 mcp 进程
        contexts.values().forEach(child -> {
            if (cid.equals(child.getParentCid())) {
                contexts.remove(child.getCid());
                if (mcpManagerService != null) {
                    mcpManagerService.shutdownForContext(child);
                }
                log.debug("移除了子会话运行上下文并回收了专属 MCP 进程: {}", child.getCid());
            }
        });

        // 3. 移除本会话上下文
        AgentContext context = contexts.remove(cid);
        if (context != null) {
            if (mcpManagerService != null) {
                mcpManagerService.shutdownForContext(context);
            }
        }
        log.debug("移除了会话运行上下文并回收了专属 MCP 进程: {}", cid);
    }

    /**
     * 获取当前在存的所有活跃上下文集合
     */
    public Collection<AgentContext> getAllContexts() {
        return contexts.values();
    }

}
