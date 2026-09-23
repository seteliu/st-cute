package com.stioc.cute.controller;

import com.stioc.cute.conversation.AgentRuntimeQueryService;
import com.stioc.cute.conversation.ConversationService;
import com.stioc.cute.conversation.types.ActiveLlmCallVo;
import com.stioc.cute.conversation.types.ActiveProcessVo;
import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.facade.ContextFacade;
import com.stioc.cute.engine.facade.ConversationFacade;
import com.stioc.cute.engine.facade.LoopFacade;
import com.stioc.cute.engine.facade.ToolFacade;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ConversationController} 切片测试。
 * <p>路由、参数绑定、Result 封装与门面转发；鉴权由过滤器层保障（本机来源辅助统一通过）。</p>
 */
@WebMvcTest(controllers = ConversationController.class)
@Import(ConversationController.class)
class ConversationControllerTest extends AbstractControllerSliceTest {

    @MockitoBean
    private ConversationService conversationService;
    @MockitoBean
    private AgentRuntimeQueryService agentRuntimeQueryService;
    @MockitoBean
    private AgentEngine agentEngine;

    private ContextFacade contextFacade;
    private ConversationFacade conversationFacade;

    @BeforeEach
    void stubEngine() {
        contextFacade = mock(ContextFacade.class);
        conversationFacade = mock(ConversationFacade.class);
        stubEngineFacades(agentEngine, contextFacade, mock(LoopFacade.class),
                conversationFacade, mock(ToolFacade.class));
        ensurePasswordlessMode();
    }

    @Test
    @DisplayName("list：返回全部会话实体列表")
    void listConversations() throws Exception {
        when(conversationService.getConversations()).thenReturn(List.of(
                Conversation.builder().id(1L).title("t1").build()));

        mockMvc.perform(localGet("/api/conversation/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].title").value("t1"));
    }

    @Test
    @DisplayName("create：请求体反序列化后经 service 落库并回传实体")
    void createConversation() throws Exception {
        Conversation created = Conversation.builder().id(9L).title("new").build();
        when(conversationService.createConversation(any(Conversation.class))).thenReturn(created);

        mockMvc.perform(localPost("/api/conversation/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"new\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(9));
    }

    @Test
    @DisplayName("update-provider：级联转发至运行态查询服务")
    void updateProvider() throws Exception {
        mockMvc.perform(localPost("/api/conversation/update-provider")
                        .param("id", "5")
                        .param("providerGroup", "openrouter")
                        .param("providerModelName", "claude-4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(agentRuntimeQueryService).cascadeProviderToChildren(5L, "openrouter", "claude-4");
    }

    @Test
    @DisplayName("update-provider：providerModelName 缺省回退空串")
    void updateProviderDefaultModel() throws Exception {
        mockMvc.perform(localPost("/api/conversation/update-provider")
                        .param("id", "5")
                        .param("providerGroup", "openrouter"))
                .andExpect(status().isOk());

        verify(agentRuntimeQueryService).cascadeProviderToChildren(5L, "openrouter", "");
    }

    @Test
    @DisplayName("config：body 带 permissionMode 时级联同步")
    void updateConfigWithPermission() throws Exception {
        mockMvc.perform(localPost("/api/conversation/config")
                        .param("id", "5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionMode\":\"allowEdits\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(agentRuntimeQueryService).cascadePermissionModeToChildren(5L, "allowEdits");
    }

    @Test
    @DisplayName("config：body 为 null 或无 permissionMode 时不动 service")
    void updateConfigWithoutPermission() throws Exception {
        mockMvc.perform(localPost("/api/conversation/config")
                        .param("id", "5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(agentRuntimeQueryService, never())
                .cascadePermissionModeToChildren(any(), any());
    }

    @Test
    @DisplayName("delete：按主键级联物理删除")
    void deleteConversation() throws Exception {
        mockMvc.perform(localDelete("/api/conversation/delete")
                        .param("id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(conversationService).deleteConversation(7L);
    }

    @Test
    @DisplayName("batch-delete：空列表不调 service，仍返回成功")
    void batchDeleteEmptyList() throws Exception {
        mockMvc.perform(localPost("/api/conversation/batch-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(conversationService, never()).deleteConversations(any());
    }

    @Test
    @DisplayName("batch-delete：非空列表级联删除")
    void batchDelete() throws Exception {
        mockMvc.perform(localPost("/api/conversation/batch-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[1,2,3]"))
                .andExpect(status().isOk());

        verify(conversationService).deleteConversations(List.of(1L, 2L, 3L));
    }

    @Test
    @DisplayName("cancel：转发引擎强停循环")
    void cancelContext() throws Exception {
        LoopFacade loopFacade = mock(LoopFacade.class);
        when(agentEngine.getLoopFacade()).thenReturn(loopFacade);

        mockMvc.perform(localPost("/api/conversation/cancel")
                        .param("id", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(loopFacade).forceStopLoop(11L);
    }

    @Test
    @DisplayName("rename：上下文存在时发布标题差量更新")
    void renameConversation() throws Exception {
        AgentContext ctx = mock(AgentContext.class);
        when(contextFacade.getOrCreateContext(3L)).thenReturn(ctx);

        mockMvc.perform(localPost("/api/conversation/rename")
                        .param("id", "3")
                        .param("title", "新标题"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(conversationFacade).publishConversationUpdate(eq(ctx), any(ConversationPatch.class));
    }

    @Test
    @DisplayName("rename：上下文为 null 时静默跳过发布")
    void renameWithoutContext() throws Exception {
        when(contextFacade.getOrCreateContext(3L)).thenReturn(null);

        mockMvc.perform(localPost("/api/conversation/rename")
                        .param("id", "3")
                        .param("title", "新标题"))
                .andExpect(status().isOk());

        verify(conversationFacade, never()).publishConversationUpdate(any(), any());
    }

    @Test
    @DisplayName("processes：查询会话活动子进程列表")
    void listActiveProcesses() throws Exception {
        when(agentRuntimeQueryService.listActiveProcesses(5L)).thenReturn(List.of(
                ActiveProcessVo.builder().cid(5L).pid(1234L).command("ping").build()));

        mockMvc.perform(localGet("/api/conversation/processes")
                        .param("id", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].pid").value(1234));
    }

    @Test
    @DisplayName("processes/kill：带 toolCallId 精确强杀")
    void killProcessWithToolCallId() throws Exception {
        mockMvc.perform(localPost("/api/conversation/processes/kill")
                        .param("id", "5")
                        .param("toolCallId", "call-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(agentRuntimeQueryService).killProcesses(5L, "call-9");
    }

    @Test
    @DisplayName("processes/kill：不带 toolCallId 全杀")
    void killAllProcesses() throws Exception {
        mockMvc.perform(localPost("/api/conversation/processes/kill")
                        .param("id", "5"))
                .andExpect(status().isOk());

        verify(agentRuntimeQueryService).killProcesses(5L, null);
    }

    @Test
    @DisplayName("llm-calls：查询会话活动大模型请求列表")
    void listActiveLlmCalls() throws Exception {
        when(agentRuntimeQueryService.listActiveLlmCalls(5L)).thenReturn(List.of(
                ActiveLlmCallVo.builder().cid(5L).llmCallId("call-1").model("gpt-x").build()));

        mockMvc.perform(localGet("/api/conversation/llm-calls")
                        .param("id", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].model").value("gpt-x"));
    }
}
