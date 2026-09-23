package com.stioc.cute.controller;

import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.facade.ContextFacade;
import com.stioc.cute.engine.facade.ConversationFacade;
import com.stioc.cute.engine.facade.LoopFacade;
import com.stioc.cute.engine.facade.ToolFacade;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.hook.HookService;
import com.stioc.cute.mcp.McpManagerService;
import com.stioc.cute.project.ProjectService;
import com.stioc.cute.runtime.loop.RuntimeContext;
import com.stioc.cute.skill.SkillManagerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AgentContextController} 切片测试。
 * <p>info 的上下文缺失拒绝、VO 装配（RuntimeContext 缺失时 rules 空列表兜底）、
 * reload 的工作区判定分支。</p>
 */
@WebMvcTest(controllers = AgentContextController.class)
@Import(AgentContextController.class)
class AgentContextControllerTest extends AbstractControllerSliceTest {

    @MockitoBean
    private AgentEngine agentEngine;
    @MockitoBean
    private ProjectService projectService;
    @MockitoBean
    private SkillManagerService skillManagerService;
    @MockitoBean
    private HookService hookService;
    @MockitoBean
    private McpManagerService mcpManagerService;

    private ContextFacade contextFacade;

    @BeforeEach
    void stubEngine() {
        contextFacade = mock(ContextFacade.class);
        stubEngineFacades(agentEngine, contextFacade, mock(LoopFacade.class),
                mock(ConversationFacade.class), mock(ToolFacade.class));
        ensurePasswordlessMode();
    }

    @Test
    @DisplayName("info：上下文缺失时 code 500")
    void infoWithoutContext() throws Exception {
        when(contextFacade.getOrCreateContext(5L)).thenReturn(null);

        mockMvc.perform(localGet("/api/agent-context/info").param("cid", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    @DisplayName("info：完整装配 VO，RuntimeContext 缺失时 rules 兜底空列表")
    void infoAssemblesVoWithRulesFallback() throws Exception {
        AgentContext ctx = mock(AgentContext.class);
        when(contextFacade.getOrCreateContext(5L)).thenReturn(ctx);
        when(ctx.extra(RuntimeContext.class)).thenReturn(null);
        when(ctx.getPermissionMode()).thenReturn("default");
        when(ctx.getProviderGroup()).thenReturn("openrouter");
        when(ctx.getProviderModelName()).thenReturn("claude-4");
        when(ctx.isLoopRunning()).thenReturn(false);
        when(ctx.getInputTokens()).thenReturn(100L);
        when(ctx.getOutputTokens()).thenReturn(50L);
        when(ctx.getCachedTokens()).thenReturn(10L);
        when(skillManagerService.getSkills(ctx)).thenReturn(List.of());
        when(hookService.getHookRules(ctx)).thenReturn(List.of());
        when(mcpManagerService.getMcpStatusList(ctx)).thenReturn(List.of());

        mockMvc.perform(localGet("/api/agent-context/info").param("cid", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.cid").value(5))
                .andExpect(jsonPath("$.data.permissionMode").value("default"))
                .andExpect(jsonPath("$.data.providerGroup").value("openrouter"))
                .andExpect(jsonPath("$.data.providerModelName").value("claude-4"))
                .andExpect(jsonPath("$.data.loopRunning").value(false))
                .andExpect(jsonPath("$.data.inputTokens").value(100))
                .andExpect(jsonPath("$.data.outputTokens").value(50))
                .andExpect(jsonPath("$.data.cachedTokens").value(10))
                .andExpect(jsonPath("$.data.rules").isEmpty());
    }

    @Test
    @DisplayName("reload：会话未绑定工作区时返回 false 不触发热重载")
    void reloadWithoutWorkspace() throws Exception {
        when(projectService.getProjectBasePathByCid(5L)).thenReturn("");

        mockMvc.perform(localPost("/api/agent-context/reload").param("cid", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));

        verify(contextFacade, never()).reloadContextAssets(5L);
    }

    @Test
    @DisplayName("reload：绑定工作区且上下文可用时触发资产热重载")
    void reloadWithWorkspace() throws Exception {
        when(projectService.getProjectBasePathByCid(5L)).thenReturn("P:/demo");
        when(contextFacade.getOrCreateContext(5L)).thenReturn(mock(AgentContext.class));

        mockMvc.perform(localPost("/api/agent-context/reload").param("cid", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        verify(contextFacade).reloadContextAssets(5L);
    }
}
