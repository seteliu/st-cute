package com.stioc.cute.entrypoint.http;

import com.stioc.cute.agent.access.AgentContextManager;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.agent.access.AgentContextVo;
import com.stioc.cute.agent.access.AgentRuleVo;
import com.stioc.cute.platform.common.Result;
import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.conversation.access.ConversationService;
import com.stioc.cute.skill.access.SkillManagerService;
import com.stioc.cute.hook.access.HookEngineService;
import com.stioc.cute.mcp.access.McpManagerService;
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
public class AgentContextApi {

    @Resource
    private AgentContextManager agentContextManager;
    @Resource
    private ConversationService conversationService;
    @Resource
    private SkillManagerService skillManagerService;
    @Resource
    private HookEngineService hookEngineService;
    @Resource
    private McpManagerService mcpManagerService;

    /**
     * 一键获取当前会话关联的完整环境上下文信息
     */
    @GetMapping("/info")
    public Result<AgentContextVo> getContextInfo(@RequestParam Long cid) {
        AgentContext context = agentContextManager.getOrCreateContext(cid);
        if (context == null) {
            return Result.error(500, "无法初始化或获取当前会话上下文");
        }

        AgentContextVo vo = AgentContextVo.builder()
                .cid(cid)
                .permissionMode(context.getPermissionMode() != null ? context.getPermissionMode().name() : null)
                .providerGroup(context.getProviderGroup())
                .providerModelName(context.getProviderModelName())
                .loopRunning(context.isLoopRunning())
                .inputTokens(context.getInputTokens())
                .outputTokens(context.getOutputTokens())
                .cachedTokens(context.getCachedTokens())
                .skills(skillManagerService.getSkills(context))
                .hooks(hookEngineService.getHookRules(context))
                .mcpServers(mcpManagerService.getMcpStatusList(context))
                .rules(context.getRules())
                .build();

        return Result.success(vo);
    }


    /**
     * 一键热重载当前会话专属项目的物理资产，包括强杀专属 MCP 子进程并重新扫描物理配置
     */
    @PostMapping("/reload")
    public Result<Boolean> reloadContextAssets(@RequestParam Long cid) {
        log.info("请求热重载会话 {} 专属环境资产", cid);

        String projectBasePath = conversationService.getProjectPath(cid);
        if (StringUtils.hasText(projectBasePath)) {
            log.info("会话 {} 专属项目路径: {}。开始触发一键热重载...", cid, projectBasePath);

            AgentContext context = agentContextManager.getOrCreateContext(cid);
            if (context != null) {
                // 统一调用管理器提取出的公用方法进行专属资产的热装载
                agentContextManager.loadContextAssets(context, projectBasePath);

                log.info("会话 {} 专属环境资产已全部热重载成功", cid);
                return Result.success(true);
            }
        }

        log.warn("热重载失败：会话 {} 未绑定任何有效项目路径", cid);
        return Result.success(false);
    }
}
