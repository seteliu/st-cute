package com.stioc.cute.controller;

import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.runtime.loop.types.AgentContextVo;
import com.stioc.cute.runtime.loop.RuntimeContext;
import com.stioc.cute.platform.common.Result;
import com.stioc.cute.project.ProjectService;
import com.stioc.cute.skill.SkillManagerService;
import com.stioc.cute.hook.HookService;
import com.stioc.cute.mcp.McpManagerService;
import org.springframework.util.StringUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 统一环境上下文控制器，收拢 Skill、Hook、MCP 的获取与一键热重载
 */
@Slf4j
@RestController
@RequestMapping("/api/agent-context")
public class AgentContextController {

    @Resource
    private AgentEngine agentEngine;
    @Resource
    private ProjectService projectService;
    @Resource
    private SkillManagerService skillManagerService;
    @Resource
    private HookService hookService;
    @Resource
    private McpManagerService mcpManagerService;

    /**
     * 一键获取当前会话关联的完整环境上下文信息
     */
    @GetMapping("/info")
    public Result<AgentContextVo> getContextInfo(@RequestParam Long cid) {
        AgentContext context = agentEngine.getContextFacade().getOrCreateContext(cid);
        if (context == null) {
            return Result.error(500, "无法初始化或获取当前会话上下文");
        }

        RuntimeContext runtimeCtx = context.extra(RuntimeContext.class);
        AgentContextVo vo = AgentContextVo.builder()
                .cid(cid)
                // permissionMode 已字符串化：直接透传引擎上下文中的字符串值
                .permissionMode(context.getPermissionMode())
                .providerGroup(context.getProviderGroup())
                .providerModelName(context.getProviderModelName())
                .loopRunning(context.isLoopRunning())
                .inputTokens(context.getInputTokens())
                .outputTokens(context.getOutputTokens())
                .cachedTokens(context.getCachedTokens())
                .skills(skillManagerService.getSkills(context))
                .hooks(hookService.getHookRules(context))
                .mcpServers(mcpManagerService.getMcpStatusList(context))
                .rules(runtimeCtx != null ? runtimeCtx.getRules() : List.of())
                .build();

        return Result.success(vo);
    }


    /**
     * 一键热重载当前会话专属工作区的物理资产，包括强杀专属 MCP 子进程并重新扫描物理配置
     */
    @PostMapping("/reload")
    public Result<Boolean> reloadContextAssets(@RequestParam Long cid) {
        log.info("请求热重载会话 {} 专属环境资产", cid);

        // Coding 宿主：检查会话是否存在绑定项目根路径
        String workspace = projectService.getProjectBasePathByCid(cid);
        if (StringUtils.hasText(workspace)) {
            log.info("会话 {} 工作区: {}。开始触发一键热重载...", cid, workspace);

            AgentContext context = agentEngine.getContextFacade().getOrCreateContext(cid);
            if (context != null) {
                // 统一调用管理器提取出的公用方法进行专属资产的热装载
                agentEngine.getContextFacade().reloadContextAssets(cid);

                log.info("会话 {} 专属环境资产已全部热重载成功", cid);
                return Result.success(true);
            }
        }

        log.warn("热重载失败：会话 {} 未解析到有效工作区", cid);
        return Result.success(false);
    }
}
