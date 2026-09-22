package com.stioc.cute.engine.event;

import com.stioc.cute.engine.event.types.AgentEvent;
import com.stioc.cute.engine.event.types.AgentEventType;
import com.stioc.cute.engine.event.types.StreamChunkPayload;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessagePatch;
import com.stioc.cute.engine.testkit.EngineStubs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentEventFactory} 事件创建单元测试。
 * <p>
 * 事件是引擎与前端之间的唯一数据通道，事件类型与载荷的错配会导致前端静默丢帧。
 * 本测试固化「工厂方法 → 事件类型 → 载荷形态」的三角对应关系。
 * </p>
 */
class AgentEventFactoryTest {

    private final AgentContext context = EngineStubs.agentContext(42L);

    /**
     * 所有事件必须携带发起上下文与有效时间戳
     */
    @Test
    void bindsContextAndTimestamp() {
        AgentEvent event = AgentEventFactory.createMessageDelete(context, Message.builder().build());

        assertSame(context, event.getAgentContext(), "事件必须绑定发起上下文");
        assertTrue(event.getTimestamp() > 0, "事件时间戳应为正值");
        assertNotNull(event.getType(), "事件类型不可为空");
    }

    /**
     * 会话类事件：创建、更新、删除的类型与载荷形态
     */
    @Test
    void createsConversationEvents() {
        AgentEvent create = AgentEventFactory.createConversationCreate(context, "payload");
        assertEquals(AgentEventType.CONVERSATION_CREATE, create.getType());
        assertEquals("payload", create.getPayload());

        ConversationPatch patch = new ConversationPatch(42L).title("标题");
        AgentEvent update = AgentEventFactory.createConversationUpdate(context, patch);
        assertEquals(AgentEventType.CONVERSATION_UPDATE, update.getType());
        assertSame(patch, update.getPayload(), "差量载荷应原样透传");

        AgentEvent delete = AgentEventFactory.createConversationDelete(context, 42L);
        assertEquals(AgentEventType.CONVERSATION_DELETE, delete.getType());
        assertEquals(42L, delete.getPayload());
    }

    /**
     * 消息类事件：落库创建与差量更新的类型与载荷形态
     */
    @Test
    void createsMessageEvents() {
        Message entity = Message.builder().id(1L).content("正文").build();
        AgentEvent create = AgentEventFactory.createMessageCreate(context, entity);
        assertEquals(AgentEventType.MESSAGE_CREATE, create.getType());
        assertSame(entity, create.getPayload());

        MessagePatch patch = new MessagePatch(1L).content("新正文");
        AgentEvent update = AgentEventFactory.createMessageUpdate(context, patch);
        assertEquals(AgentEventType.MESSAGE_UPDATE, update.getType());
        assertSame(patch, update.getPayload());
    }

    /**
     * 思考流事件：载荷必须携带归属消息 ID 与增量文本
     */
    @Test
    void createsThinkingStreamEvent() {
        AgentEvent event = AgentEventFactory.createThinkingStream(context, 7L, "思考中");

        assertEquals(AgentEventType.AGENT_THINKING_STREAM, event.getType());
        StreamChunkPayload payload = assertInstanceOf(StreamChunkPayload.class, event.getPayload());
        assertEquals("思考中", payload.getText());
        assertEquals(7L, payload.getId(), "载荷应可解析出归属的助手消息 ID");
    }

    /**
     * 正文流事件：载荷必须携带归属消息 ID 与增量文本
     */
    @Test
    void createsContentStreamEvent() {
        AgentEvent event = AgentEventFactory.createContentStream(context, 8L, "正文增量");

        assertEquals(AgentEventType.AGENT_CONTENT_STREAM, event.getType());
        StreamChunkPayload payload = assertInstanceOf(StreamChunkPayload.class, event.getPayload());
        assertEquals("正文增量", payload.getText());
        assertEquals(8L, payload.getId());
    }

    /**
     * 工具日志流事件：载荷以「工具消息 ID」作为归属标识（与助手流统一为消息 ID 模型）
     */
    @Test
    void createsToolLogStreamEvent() {
        AgentEvent event = AgentEventFactory.createToolLogStream(context, 9L, "命令输出行");

        assertEquals(AgentEventType.TOOL_LOG_STREAM, event.getType());
        StreamChunkPayload payload = assertInstanceOf(StreamChunkPayload.class, event.getPayload());
        assertEquals("命令输出行", payload.getText());
        assertEquals(9L, payload.getId(), "工具日志流的归属标识应为 TOOL 消息 ID");
    }
}
