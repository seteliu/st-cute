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
import com.stioc.cute.skill.SkillManagerService;
import com.stioc.cute.skill.types.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SlashController} 切片测试。
 * <p>context 缺失拒绝、skills→SlashItemVo 映射、空技能列表成空分组。</p>
 */
@WebMvcTest(controllers = SlashController.class)
@Import(SlashController.class)
class SlashControllerTest extends AbstractControllerSliceTest {

    @MockitoBean
    private AgentEngine agentEngine;
    @MockitoBean
    private SkillManagerService skillManagerService;

    private ContextFacade contextFacade;

    @BeforeEach
    void stubEngine() {
        contextFacade = mock(ContextFacade.class);
        stubEngineFacades(agentEngine, contextFacade, mock(LoopFacade.class),
                mock(ConversationFacade.class), mock(ToolFacade.class));
        ensurePasswordlessMode();
    }

    @Test
    @DisplayName("list：上下文缺失时 code 500")
    void listWithoutContext() throws Exception {
        when(contextFacade.getOrCreateContext(5L)).thenReturn(null);

        mockMvc.perform(localGet("/api/slash/list").param("cid", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("无法初始化或获取当前会话上下文"));
    }

    @Test
    @DisplayName("list：技能清单映射为 skill 分组条目")
    void listMapsSkillsToItems() throws Exception {
        AgentContext ctx = mock(AgentContext.class);
        when(contextFacade.getOrCreateContext(5L)).thenReturn(ctx);
        Skill skill = new Skill();
        skill.setName("st-commit");
        skill.setDescription("提交助手");
        when(skillManagerService.getSkills(ctx)).thenReturn(List.of(skill));

        mockMvc.perform(localGet("/api/slash/list").param("cid", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].group").value("skill"))
                .andExpect(jsonPath("$.data[0].items[0].name").value("st-commit"))
                .andExpect(jsonPath("$.data[0].items[0].description").value("提交助手"));
    }

    @Test
    @DisplayName("list：空技能清单产出空 items 分组")
    void listEmptySkills() throws Exception {
        AgentContext ctx = mock(AgentContext.class);
        when(contextFacade.getOrCreateContext(5L)).thenReturn(ctx);
        when(skillManagerService.getSkills(ctx)).thenReturn(List.of());

        mockMvc.perform(localGet("/api/slash/list").param("cid", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].group").value("skill"))
                .andExpect(jsonPath("$.data[0].items").isEmpty());
    }
}
