package com.stioc.cute.controller;

import com.stioc.cute.conversation.types.ApproveToolDto;
import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.common.StreamBufferType;
import com.stioc.cute.engine.facade.ContextFacade;
import com.stioc.cute.engine.facade.ConversationFacade;
import com.stioc.cute.engine.facade.LoopFacade;
import com.stioc.cute.engine.facade.ToolFacade;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.tool.types.ToolApprovalRequest;
import com.stioc.cute.message.MessageService;
import com.stioc.cute.message.types.LimitMessageDto;
import com.stioc.cute.message.types.MessageVo;
import com.stioc.cute.runtime.loop.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MessageController} 切片测试。
 * <p>
 * 重点覆盖 RUNNING 消息的流式缓存回填契约（ASSISTANT 的 CONTENT/THINKING、TOOL 的 TOOL_LOG）、
 * 参数校验异常映射（BusinessException→code 500）、审批参数装箱。
 * </p>
 */
@WebMvcTest(controllers = MessageController.class)
@Import(MessageController.class)
class MessageControllerTest extends AbstractControllerSliceTest {

    @MockitoBean
    private MessageService messageService;
    @MockitoBean
    private AgentEngine agentEngine;

    private ContextFacade contextFacade;
    private LoopFacade loopFacade;
    private ConversationFacade conversationFacade;
    private ToolFacade toolFacade;

    @BeforeEach
    void stubEngine() {
        contextFacade = mock(ContextFacade.class);
        loopFacade = mock(LoopFacade.class);
        conversationFacade = mock(ConversationFacade.class);
        toolFacade = mock(ToolFacade.class);
        stubEngineFacades(agentEngine, contextFacade, loopFacade, conversationFacade, toolFacade);
        ensurePasswordlessMode();
    }

    /**
     * 构造一条 VO
     */
    private MessageVo vo(Long id, MessageRole role, MessageStatus status) {
        MessageVo v = new MessageVo();
        v.setId(id);
        v.setRole(role);
        v.setStatus(status);
        v.setContent("");
        v.setThought("");
        return v;
    }

    @Test
    @DisplayName("list：RUNNING 的 ASSISTANT 消息回填 CONTENT 与 THINKING 流式缓存")
    void listBackfillsRunningAssistant() throws Exception {
        MessageVo assistant = vo(1L, MessageRole.ASSISTANT, MessageStatus.RUNNING);
        LimitMessageDto dto = new LimitMessageDto(List.of(assistant), false);
        when(messageService.getConversationMessages(5L, true, null, null)).thenReturn(dto);
        AgentContext ctx = mock(AgentContext.class);
        when(contextFacade.getActiveContext(5L)).thenReturn(ctx);
        when(ctx.snapshotStreamText(StreamBufferType.CONTENT, 1L)).thenReturn("累积正文");
        when(ctx.snapshotStreamText(StreamBufferType.THINKING, 1L)).thenReturn("累积思考");

        mockMvc.perform(localGet("/api/message/list").param("cid", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[0].content").value("累积正文"))
                .andExpect(jsonPath("$.data.messages[0].thought").value("累积思考"));
    }

    @Test
    @DisplayName("list：RUNNING 的 TOOL 消息仅回填 TOOL_LOG，不读思考流")
    void listBackfillsRunningTool() throws Exception {
        MessageVo tool = vo(2L, MessageRole.TOOL, MessageStatus.RUNNING);
        LimitMessageDto dto = new LimitMessageDto(List.of(tool), false);
        when(messageService.getConversationMessages(5L, true, null, null)).thenReturn(dto);
        AgentContext ctx = mock(AgentContext.class);
        when(contextFacade.getActiveContext(5L)).thenReturn(ctx);
        when(ctx.snapshotStreamText(StreamBufferType.TOOL_LOG, 2L)).thenReturn("日志增量");

        mockMvc.perform(localGet("/api/message/list").param("cid", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[0].content").value("日志增量"));
    }

    @Test
    @DisplayName("list：非 RUNNING 消息跳过回填（保留 DB 快照值）")
    void listSkipsNonRunningMessages() throws Exception {
        MessageVo done = vo(3L, MessageRole.ASSISTANT, MessageStatus.SUCCESS);
        done.setContent("终态正文");
        LimitMessageDto dto = new LimitMessageDto(List.of(done), false);
        when(messageService.getConversationMessages(5L, true, null, null)).thenReturn(dto);
        AgentContext ctx = mock(AgentContext.class);
        when(contextFacade.getActiveContext(5L)).thenReturn(ctx);

        mockMvc.perform(localGet("/api/message/list").param("cid", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[0].content").value("终态正文"));
        verify(ctx, never()).snapshotStreamText(any(), any());
    }

    @Test
    @DisplayName("list：上下文不活跃（无运行中循环）时不触碰流缓存")
    void listWithoutActiveContext() throws Exception {
        LimitMessageDto dto = new LimitMessageDto(List.of(), false);
        when(messageService.getConversationMessages(5L, true, null, null)).thenReturn(dto);
        when(contextFacade.getActiveContext(5L)).thenReturn(null);

        mockMvc.perform(localGet("/api/message/list").param("cid", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("send：空文本抛 BusinessException，全局异常映射为 code 500")
    void sendBlankTextRejected() throws Exception {
        mockMvc.perform(localPost("/api/message/send")
                        .param("cid", "5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("消息内容不能为空"));

        verify(loopFacade, never()).submitUserMessage(any(), any(), any());
    }

    @Test
    @DisplayName("send：合法文本提交引擎循环并携带附件")
    void sendMessageWithAttachments() throws Exception {
        mockMvc.perform(localPost("/api/message/send")
                        .param("cid", "5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"你好\",\"attachments\":\"[{\\\"id\\\":1}]\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(loopFacade).submitUserMessage(eq(5L), eq("你好"), eq("[{\"id\":1}]"));
    }

    @Test
    @DisplayName("retry：按会话与消息 ID 重试")
    void retryMessage() throws Exception {
        mockMvc.perform(localPost("/api/message/retry")
                        .param("cid", "5")
                        .param("messageId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(loopFacade).retryMessage(5L, 42L);
    }

    @Test
    @DisplayName("approve：alwaysAllow 三态装箱（true 透传，null 归 false）并转发审批门面")
    void approveToolBooleanBoxing() throws Exception {
        when(toolFacade.approveTool(any(ToolApprovalRequest.class))).thenReturn(true);

        mockMvc.perform(localPost("/api/message/approve")
                        .param("cid", "5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"call-1\",\"decision\":\"ALLOW\",\"alwaysAllow\":null," +
                                "\"toolName\":\"run_command\",\"contentPattern\":\"mvn.*\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        ArgumentCaptor<ToolApprovalRequest> captor = ArgumentCaptor.forClass(ToolApprovalRequest.class);
        verify(toolFacade).approveTool(captor.capture());
        ToolApprovalRequest request = captor.getValue();
        assertEquals(5L, request.getCid());
        assertEquals("call-1", request.getToolCallId());
        assertEquals("ALLOW", request.getDecision());
        assertFalse(request.isAlwaysAllow());
        assertEquals("run_command", request.getToolName());
        assertEquals("mvn.*", request.getContentPattern());
    }

    @Test
    @DisplayName("approve：alwaysAllow=true 时装箱为 true")
    void approveToolAlwaysAllowTrue() throws Exception {
        when(toolFacade.approveTool(any(ToolApprovalRequest.class))).thenReturn(false);

        mockMvc.perform(localPost("/api/message/approve")
                        .param("cid", "5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"call-1\",\"decision\":\"ALLOW\",\"alwaysAllow\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));

        ArgumentCaptor<ToolApprovalRequest> captor = ArgumentCaptor.forClass(ToolApprovalRequest.class);
        verify(toolFacade).approveTool(captor.capture());
        assertTrue(captor.getValue().isAlwaysAllow());
    }

    @Test
    @DisplayName("detail：消息不存在时 BusinessException 映射 code 500")
    void detailNotFound() throws Exception {
        when(messageService.findById(404L)).thenReturn(Optional.empty());

        mockMvc.perform(localGet("/api/message/detail").param("messageId", "404"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("未找到指定的消息，ID: 404"));
    }

    @Test
    @DisplayName("detail：RUNNING 消息回填流式缓存（与 list 同口径）")
    void detailBackfillsRunningMessage() throws Exception {
        Message entity = Message.builder()
                .id(7L).cid(5L).role(MessageRole.ASSISTANT).status(MessageStatus.RUNNING)
                .content("").reasoningContent("")
                .visibleToUser(true).visibleToModel(true)
                .createTime(LocalDateTime.now()).updateTime(LocalDateTime.now())
                .build();
        when(messageService.findById(7L)).thenReturn(Optional.of(entity));
        AgentContext ctx = mock(AgentContext.class);
        when(contextFacade.getActiveContext(5L)).thenReturn(ctx);
        when(ctx.snapshotStreamText(StreamBufferType.CONTENT, 7L)).thenReturn("详情正文");

        mockMvc.perform(localGet("/api/message/detail").param("messageId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("详情正文"));
    }

    @Test
    @DisplayName("clear：清空会话历史并重置宿主读取门禁缓存")
    void clearConversation() throws Exception {
        AgentContext ctx = mock(AgentContext.class);
        RuntimeContext runtimeCtx = mock(RuntimeContext.class);
        when(contextFacade.getActiveContext(5L)).thenReturn(ctx);
        when(ctx.extra(RuntimeContext.class)).thenReturn(runtimeCtx);
        when(runtimeCtx.getReadFiles()).thenReturn(new ConcurrentHashMap<>());

        mockMvc.perform(localPost("/api/message/clear").param("cid", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(conversationFacade).clearConversation(5L);
    }

    @Test
    @DisplayName("reset：回退并重置至指定消息节点")
    void resetConversationMessages() throws Exception {
        mockMvc.perform(localPost("/api/message/reset")
                        .param("cid", "5")
                        .param("messageId", "33"))
                .andExpect(status().isOk());

        verify(conversationFacade).resetConversationMessages(5L, 33L);
    }
}
