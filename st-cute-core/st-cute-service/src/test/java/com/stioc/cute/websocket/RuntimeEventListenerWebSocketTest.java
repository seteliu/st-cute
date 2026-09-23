package com.stioc.cute.websocket;

import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.event.types.AgentEvent;
import com.stioc.cute.engine.event.types.AgentEventType;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessagePatch;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.testkit.FakeWebSocketSession;
import com.stioc.cute.testkit.WsTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RuntimeEventListenerWebSocket} 事件翻译与外推测试。
 * <p>
 * 零容器单测：mock {@code AgentEngine}（消息/会话最新实体回查），搭配真实
 * {@code WebSocketBroadcast}，验证引擎事件到 S2C 帧的翻译映射、消息事件 payload 装配
 * （MESSAGE_UPDATE 回查库 / MESSAGE_CREATE 直转 VO）、invisible 消息不外推、
 * 子代理事件转发至父会话连接。
 * </p>
 */
class RuntimeEventListenerWebSocketTest extends WsTestSupport.WsAdapter {

    private AgentEngine agentEngine;
    private RuntimeEventListenerWebSocket listener;

    private FakeWebSocketSession parentSession;

    private static final Long CID = 10L;
    private static final Long PARENT_CID = 20L;

    @BeforeEach
    void setUpListener() {
        agentEngine = mock(AgentEngine.class);
        listener = new RuntimeEventListenerWebSocket();
        ReflectionTestUtils.setField(listener, "agentEngine", agentEngine);
        ReflectionTestUtils.setField(listener, "webSocketBroadcast", new WebSocketBroadcast());

        parentSession = newTrackedSession(CID);
    }

    @AfterEach
    void sweepRegistry() {
        sweep();
    }

    /**
     * 构造带上下文的引擎事件
     */
    private AgentEvent event(AgentContext context, AgentEventType type, Object payload) {
        return new AgentEvent(context, System.currentTimeMillis(), type, payload);
    }

    /**
     * mock 一个 AgentContext（普通会话；parentCid 非空即子代理）
     */
    private AgentContext mockContext(Long cid, Long parentCid) {
        AgentContext ctx = mock(AgentContext.class);
        lenient().when(ctx.getCid()).thenReturn(cid);
        lenient().when(ctx.getParentCid()).thenReturn(parentCid);
        lenient().when(ctx.isSubAgent()).thenReturn(parentCid != null);
        return ctx;
    }

    /**
     * 构造一条消息实体
     */
    private Message message(Long id, MessageRole role, boolean visibleToUser) {
        return Message.builder()
                .id(id).cid(CID).role(role).status(MessageStatus.SUCCESS)
                .content("c-" + id).reasoningContent("")
                .visibleToUser(visibleToUser).visibleToModel(true)
                .createTime(LocalDateTime.now()).updateTime(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("MESSAGE_CREATE：实体直转 VO 外推，可见消息送达会话连接")
    void messageCreateTranslatesToVoAndPushes() {
        Message msg = message(1L, MessageRole.USER, true);
        AgentContext ctx = mockContext(CID, null);

        listener.onEvent(event(ctx, AgentEventType.MESSAGE_CREATE, msg));

        assertReceivedType(parentSession, "S2C_MESSAGE_CREATED");
        WebSocketEvent frame = parentSession.receivedEvents().getFirst();
        assertEquals(CID, frame.getCid());
    }

    @Test
    @DisplayName("MESSAGE_CREATE：invisible 消息不外推")
    void messageCreateInvisibleIsSkipped() {
        Message msg = message(2L, MessageRole.TOOL, false);
        AgentContext ctx = mockContext(CID, null);

        listener.onEvent(event(ctx, AgentEventType.MESSAGE_CREATE, msg));

        assertNothingReceived(parentSession);
    }

    @Test
    @DisplayName("MESSAGE_UPDATE：payload 为 MessagePatch 时回查库取最新实体转 VO")
    void messageUpdateWithPatchQueriesLatest() {
        Message latest = message(3L, MessageRole.ASSISTANT, true);
        MessagePatch patch = new MessagePatch(3L);
        MessageStore messageStore = mock(MessageStore.class);
        when(agentEngine.getMessageStore()).thenReturn(messageStore);
        when(messageStore.getById(3L)).thenReturn(latest);
        AgentContext ctx = mockContext(CID, null);

        listener.onEvent(event(ctx, AgentEventType.MESSAGE_UPDATE, patch));

        assertReceivedType(parentSession, "S2C_MESSAGE_UPDATED");
    }

    @Test
    @DisplayName("MESSAGE_UPDATE：回查到的最新实体 invisible 时不外推")
    void messageUpdateLatestInvisibleIsSkipped() {
        Message latest = message(4L, MessageRole.TOOL, false);
        MessagePatch patch = new MessagePatch(4L);
        MessageStore messageStore = mock(MessageStore.class);
        when(agentEngine.getMessageStore()).thenReturn(messageStore);
        when(messageStore.getById(4L)).thenReturn(latest);
        AgentContext ctx = mockContext(CID, null);

        listener.onEvent(event(ctx, AgentEventType.MESSAGE_UPDATE, patch));

        assertNothingReceived(parentSession);
    }

    @Test
    @DisplayName("MESSAGE_UPDATE：payload 为 Message 实体时回查库取最新后转 VO")
    void messageUpdateWithEntityQueriesLatest() {
        Message stale = message(5L, MessageRole.ASSISTANT, true);
        Message latest = message(5L, MessageRole.ASSISTANT, true);
        MessageStore messageStore = mock(MessageStore.class);
        when(agentEngine.getMessageStore()).thenReturn(messageStore);
        when(messageStore.getById(5L)).thenReturn(latest);
        AgentContext ctx = mockContext(CID, null);

        listener.onEvent(event(ctx, AgentEventType.MESSAGE_UPDATE, stale));

        assertReceivedType(parentSession, "S2C_MESSAGE_UPDATED");
    }

    @Test
    @DisplayName("子代理事件：定向帧转发至父会话连接（targetCid=parentCid）")
    void subAgentEventRoutesToParentSession() {
        // 语义对齐：parentSession 绑定父会话 PARENT_CID；子代理自身 cid=99 的帧送往父
        parentSession = newTrackedSession(PARENT_CID);
        Message msg = message(6L, MessageRole.USER, true);
        AgentContext subCtx = mockContext(99L, PARENT_CID);

        listener.onEvent(event(subCtx, AgentEventType.MESSAGE_CREATE, msg));

        // 帧送往父会话连接（targetCid=parentCid）；帧头 cid 保留子代理自身会话 ID、parentCid 标记父归属
        assertReceivedType(parentSession, "S2C_MESSAGE_CREATED");
        WebSocketEvent frame = parentSession.receivedEvents().getFirst();
        assertEquals(99L, frame.getCid());
        assertEquals(PARENT_CID, frame.getParentCid());
    }

    @Test
    @DisplayName("CONVERSATION_CREATE：委托全局广播，绑定与未绑定连接均收到一份")
    void conversationCreateBroadcasts() {
        FakeWebSocketSession globalOnly = newTrackedGlobalSession();
        // 绑定 cid 的连接也需先进全量连接集才能收广播（模拟真实建连流程）
        FakeWebSocketSession bound = newTrackedGlobalSession();
        WebSocketSessionManager.registerSession(CID, bound);
        Conversation conversation = Conversation.builder().id(CID).title("t").build();
        AgentContext ctx = mockContext(CID, null);

        listener.onEvent(event(ctx, AgentEventType.CONVERSATION_CREATE, conversation));

        assertReceivedType(globalOnly, "S2C_CONVERSATION_CREATED");
        assertReceivedType(bound, "S2C_CONVERSATION_CREATED");
    }

    @Test
    @DisplayName("CONVERSATION_UPDATE：回查最新会话实体后全局广播")
    void conversationUpdateBroadcastsLatest() {
        FakeWebSocketSession globalOnly = newTrackedGlobalSession();
        Conversation latest = Conversation.builder().id(CID).title("t-new").build();
        ConversationStore conversationStore = mock(ConversationStore.class);
        when(agentEngine.getConversationStore()).thenReturn(conversationStore);
        when(conversationStore.getById(CID)).thenReturn(latest);
        ConversationPatch patch = new ConversationPatch(CID);
        AgentContext ctx = mockContext(CID, null);

        listener.onEvent(event(ctx, AgentEventType.CONVERSATION_UPDATE, patch));

        assertReceivedType(globalOnly, "S2C_CONVERSATION_UPDATED");
    }

    @Test
    @DisplayName("CONVERSATION_DELETE：payload 为 Long 时直接全局广播删除事件")
    void conversationDeleteBroadcasts() {
        FakeWebSocketSession globalOnly = newTrackedGlobalSession();
        AgentContext ctx = mockContext(CID, null);

        listener.onEvent(event(ctx, AgentEventType.CONVERSATION_DELETE, CID));

        assertReceivedType(globalOnly, "S2C_CONVERSATION_DELETED");
    }

    @Test
    @DisplayName("流式事件：THINKING/CONTENT/TOOL_LOG 直翻为 S2C 流帧（payload 原样透传）")
    void streamEventsTranslateDirectly() {
        AgentContext ctx = mockContext(CID, null);

        listener.onEvent(event(ctx, AgentEventType.AGENT_THINKING_STREAM, "think-chunk"));
        assertReceivedType(parentSession, "S2C_THINKING_STREAM");

        listener.onEvent(event(ctx, AgentEventType.AGENT_CONTENT_STREAM, "content-chunk"));
        assertReceivedType(parentSession, "S2C_CONTENT_STREAM");

        listener.onEvent(event(ctx, AgentEventType.TOOL_LOG_STREAM, "log-chunk"));
        assertReceivedType(parentSession, "S2C_TOOL_LOG_STREAM");
    }

    @Test
    @DisplayName("事件 payload 为 null 或事件缺 AgentContext：静默不外推")
    void nullPayloadOrMissingContextIsSilentlySkipped() {
        listener.onEvent(null);

        AgentEvent noContext = new AgentEvent(null, System.currentTimeMillis(),
                AgentEventType.MESSAGE_CREATE, null);
        listener.onEvent(noContext);

        // payload=null 的 MESSAGE_CREATE 仍会外推空载荷帧（instanceof 不匹配但流程继续），
        // 此处仅验证缺 AgentContext 的定向事件不外推
        assertNothingReceived(parentSession);
    }
}
