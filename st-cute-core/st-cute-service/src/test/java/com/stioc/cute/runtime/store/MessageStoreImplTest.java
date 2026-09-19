package com.stioc.cute.runtime.store;

import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessagePatch;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.store.types.SortDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宿主消息存储供血实现真实 SQLite 落库集成测试。
 * <p>
 * 覆盖单实体/批量新增、自增主键回填、枚举类型映射、轻量投影查询（优化关键路径）、
 * 倒序分页、Patch 差量更新（状态机与指标回填）、条件批量更新、物理删除与大载荷无损持久化。
 * </p>
 */
@DisplayName("MessageStoreImpl 真实 SQLite 落库集成测试")
class MessageStoreImplTest extends AbstractStoreIntegrationTest {

    @Test
    @DisplayName("insert: 成功新增消息实体并自动回填自增 ID、枚举及默认值")
    void testInsert_SuccessAndIdGenerated() {
        Message msg = Message.builder()
                .cid(1001L)
                .role(MessageRole.USER)
                .content("帮我重构这段代码")
                .status(MessageStatus.SUCCESS)
                .visibleToUser(true)
                .visibleToModel(true)
                .build();

        messageStore.insert(msg);
        assertNotNull(msg.getId(), "自增主键 ID 必须自动回填");
        assertTrue(msg.getId() > 0);
        assertNotNull(msg.getCreateTime());
        assertNotNull(msg.getUpdateTime());

        Message fetched = messageStore.getById(msg.getId());
        assertNotNull(fetched);
        assertEquals(1001L, fetched.getCid());
        assertEquals(MessageRole.USER, fetched.getRole());
        assertEquals("帮我重构这段代码", fetched.getContent());
        assertEquals(MessageStatus.SUCCESS, fetched.getStatus());
        assertTrue(fetched.getVisibleToUser());
        assertTrue(fetched.getVisibleToModel());
    }

    @Test
    @DisplayName("insertBatch: 批量插入不同角色消息并全量回填 ID")
    void testInsertBatch_Success() {
        List<Message> list = List.of(
                Message.builder().cid(2001L).role(MessageRole.USER).content("提问").status(MessageStatus.SUCCESS).build(),
                Message.builder().cid(2001L).role(MessageRole.ASSISTANT).content("回答").status(MessageStatus.SUCCESS).build(),
                Message.builder().cid(2001L).role(MessageRole.TOOL).content("结果").callId("call_1").status(MessageStatus.SUCCESS).build()
        );

        messageStore.insertBatch(list);
        for (Message m : list) {
            assertNotNull(m.getId());
            Message saved = messageStore.getById(m.getId());
            assertNotNull(saved);
            assertEquals(2001L, saved.getCid());
        }
    }

    @Test
    @DisplayName("getById: 完整还原各字段属性，不存在返回 null")
    void testGetById_FoundAndNotFound() {
        Message msg = Message.builder()
                .cid(3001L)
                .parentMessageId(500L)
                .role(MessageRole.ASSISTANT)
                .content("这是完整助手输出")
                .reasoningContent("思考过程细节分析...")
                .toolCalls("[{\"id\":\"c_1\",\"name\":\"read_file\"}]")
                .callId("c_1")
                .status(MessageStatus.RUNNING)
                .visibleToUser(true)
                .visibleToModel(false)
                .inputTokens(2048L)
                .outputTokens(512L)
                .cachedTokens(128L)
                .executionDurationMs(3456L)
                .attachments("[{\"file\":\"doc.pdf\"}]")
                .build();
        messageStore.insert(msg);

        Message fetched = messageStore.getById(msg.getId());
        assertNotNull(fetched);
        assertEquals(3001L, fetched.getCid());
        assertEquals(500L, fetched.getParentMessageId());
        assertEquals(MessageRole.ASSISTANT, fetched.getRole());
        assertEquals("这是完整助手输出", fetched.getContent());
        assertEquals("思考过程细节分析...", fetched.getReasoningContent());
        assertEquals("[{\"id\":\"c_1\",\"name\":\"read_file\"}]", fetched.getToolCalls());
        assertEquals("c_1", fetched.getCallId());
        assertEquals(MessageStatus.RUNNING, fetched.getStatus());
        assertTrue(fetched.getVisibleToUser());
        assertFalse(fetched.getVisibleToModel());
        assertEquals(2048L, fetched.getInputTokens());
        assertEquals(512L, fetched.getOutputTokens());
        assertEquals(128L, fetched.getCachedTokens());
        assertEquals(3456L, fetched.getExecutionDurationMs());
        assertEquals("[{\"file\":\"doc.pdf\"}]", fetched.getAttachments());

        assertNull(messageStore.getById(9999999L));
    }

    @Test
    @DisplayName("getByQuery: 根据 cid 与 callId 唯一定位工具回包消息")
    void testGetByQuery_ByCidAndCallId() {
        messageStore.insert(Message.builder().cid(4001L).role(MessageRole.TOOL).callId("call_abc").content("res1").build());
        messageStore.insert(Message.builder().cid(4001L).role(MessageRole.TOOL).callId("call_xyz").content("res2").build());

        Message found = messageStore.getByQuery(MessageQuery.builder()
                .cid(4001L)
                .callId("call_xyz")
                .build());
        assertNotNull(found);
        assertEquals("res2", found.getContent());

        assertNull(messageStore.getByQuery(MessageQuery.builder()
                .cid(4001L)
                .callId("call_not_exist")
                .build()));
    }

    @Test
    @DisplayName("listByQuery: light 模式轻量投影仅查询 id, parentMessageId, role, status")
    void testListByQuery_LightProjection() {
        Message msg = Message.builder()
                .cid(5001L)
                .parentMessageId(12L)
                .role(MessageRole.ASSISTANT)
                .content("这是极其庞大的上下文内容...")
                .reasoningContent("这是庞大的推理思考链...")
                .toolCalls("庞大工具详情")
                .status(MessageStatus.SUCCESS)
                .build();
        messageStore.insert(msg);

        MessageQuery lightQuery = MessageQuery.builder()
                .cid(5001L)
                .light(true)
                .build();

        List<Message> list = messageStore.listByQuery(lightQuery);
        assertEquals(1, list.size());
        Message projected = list.get(0);
        assertEquals(msg.getId(), projected.getId());
        assertEquals(12L, projected.getParentMessageId());
        assertEquals(MessageRole.ASSISTANT, projected.getRole());
        assertEquals(MessageStatus.SUCCESS, projected.getStatus());

        // 轻量投影下，庞大的正文与工具链应当不查出（为 null）
        assertNull(projected.getContent(), "轻量投影下 content 必须为 null");
        assertNull(projected.getReasoningContent(), "轻量投影下 reasoningContent 必须为 null");
        assertNull(projected.getToolCalls(), "轻量投影下 toolCalls 必须为 null");
    }

    @Test
    @DisplayName("listByQuery: 支持按角色、状态过滤并验证排序与限制")
    void testListByQuery_WithFiltersAndOrder() {
        long cid = 6001L;
        messageStore.insert(Message.builder().cid(cid).role(MessageRole.USER).status(MessageStatus.SUCCESS).content("1").build());
        messageStore.insert(Message.builder().cid(cid).role(MessageRole.ASSISTANT).status(MessageStatus.RUNNING).content("2").build());
        messageStore.insert(Message.builder().cid(cid).role(MessageRole.ASSISTANT).status(MessageStatus.SUCCESS).content("3").build());

        // 仅查询 ASSISTANT 且 SUCCESS 的消息
        List<Message> filtered = messageStore.listByQuery(MessageQuery.builder()
                .cid(cid)
                .role(MessageRole.ASSISTANT)
                .status(MessageStatus.SUCCESS)
                .build());
        assertEquals(1, filtered.size());
        assertEquals("3", filtered.get(0).getContent());

        // 排序与限制测试
        List<Message> descList = messageStore.listByQuery(MessageQuery.builder()
                .cid(cid)
                .sortField("id")
                .sortDirection(SortDirection.DESC)
                .limit(2)
                .build());
        assertEquals(2, descList.size());
        assertEquals("3", descList.get(0).getContent());
        assertEquals("2", descList.get(1).getContent());
    }

    @Test
    @DisplayName("existsByQuery: 消息存在性查询")
    void testExistsByQuery() {
        messageStore.insert(Message.builder().cid(7001L).role(MessageRole.USER).build());

        assertTrue(messageStore.existsByQuery(MessageQuery.builder().cid(7001L).build()));
        assertFalse(messageStore.existsByQuery(MessageQuery.builder().cid(9999L).build()));
    }

    @Test
    @DisplayName("updateById: 整实体修改并自动刷新 updateTime")
    void testUpdateById_Success() {
        Message msg = Message.builder()
                .cid(8001L)
                .role(MessageRole.ASSISTANT)
                .content("旧回复")
                .status(MessageStatus.RUNNING)
                .build();
        messageStore.insert(msg);
        LocalDateTime oldTime = msg.getUpdateTime();

        msg.setContent("新回复完成");
        msg.setStatus(MessageStatus.SUCCESS);
        int rows = messageStore.updateById(msg);
        assertEquals(1, rows);

        Message after = messageStore.getById(msg.getId());
        assertEquals("新回复完成", after.getContent());
        assertEquals(MessageStatus.SUCCESS, after.getStatus());
        assertTrue(!after.getUpdateTime().isBefore(oldTime));
    }

    @Test
    @DisplayName("updateByPatch: 流式结束差量回填 status、outputTokens 与耗时，正文无损")
    void testUpdateByPatch_StatusAndMetrics() {
        Message msg = Message.builder()
                .cid(8501L)
                .role(MessageRole.ASSISTANT)
                .content("流式输出的正文内容保持原状")
                .reasoningContent("思考过程原状")
                .status(MessageStatus.RUNNING)
                .build();
        messageStore.insert(msg);

        MessagePatch patch = new MessagePatch(msg.getId())
                .set(Message::getStatus, MessageStatus.SUCCESS)
                .set(Message::getOutputTokens, 888L)
                .set(Message::getExecutionDurationMs, 2500L);

        int rows = messageStore.updateByPatch(patch);
        assertEquals(1, rows);

        Message updated = messageStore.getById(msg.getId());
        assertEquals(MessageStatus.SUCCESS, updated.getStatus());
        assertEquals(888L, updated.getOutputTokens());
        assertEquals(2500L, updated.getExecutionDurationMs());
        // 未在 patch 中的正文与思考过程保持完整
        assertEquals("流式输出的正文内容保持原状", updated.getContent());
        assertEquals("思考过程原状", updated.getReasoningContent());
    }

    @Test
    @DisplayName("updateByPatch: 显式指定 null 清空字段")
    void testUpdateByPatch_NullClearSemantic() {
        Message msg = Message.builder()
                .cid(8601L)
                .role(MessageRole.TOOL)
                .callId("call_to_clear")
                .attachments("[{\"f\":\"test.txt\"}]")
                .build();
        messageStore.insert(msg);

        MessagePatch patch = new MessagePatch(msg.getId())
                .set(Message::getCallId, null)
                .set(Message::getAttachments, null);

        int rows = messageStore.updateByPatch(patch);
        assertEquals(1, rows);

        Message updated = messageStore.getById(msg.getId());
        assertNull(updated.getCallId());
        assertNull(updated.getAttachments());
    }

    @Test
    @DisplayName("updateByQuery: 条件批量更新消息（如将会话所有未完结消息置为 FAILED）")
    void testUpdateByQuery_BatchMarkStatus() {
        long cid = 8701L;
        messageStore.insert(Message.builder().cid(cid).status(MessageStatus.RUNNING).content("m1").build());
        messageStore.insert(Message.builder().cid(cid).status(MessageStatus.RUNNING).content("m2").build());
        messageStore.insert(Message.builder().cid(cid).status(MessageStatus.SUCCESS).content("m3").build());

        int rows = messageStore.updateByQuery(
                Message.builder().status(MessageStatus.FAILED).build(),
                MessageQuery.builder().cid(cid).status(MessageStatus.RUNNING).build()
        );
        assertEquals(2, rows);

        List<Message> errList = messageStore.listByQuery(MessageQuery.builder().cid(cid).status(MessageStatus.FAILED).build());
        assertEquals(2, errList.size());
    }

    @Test
    @DisplayName("deleteById & deleteByQuery: 物理删除消息")
    void testDeleteOperations() {
        long cid = 8801L;
        Message m1 = Message.builder().cid(cid).content("1").build();
        Message m2 = Message.builder().cid(cid).content("2").build();
        Message m3 = Message.builder().cid(9999L).content("3").build();
        messageStore.insert(m1);
        messageStore.insert(m2);
        messageStore.insert(m3);

        // 1. 删除单条
        messageStore.deleteById(m1.getId());
        assertNull(messageStore.getById(m1.getId()));

        // 2. 按 cid 批量删除剩余消息
        int rows = messageStore.deleteByQuery(MessageQuery.builder().cid(cid).build());
        assertEquals(1, rows);
        assertNull(messageStore.getById(m2.getId()));

        // 3. 其他 cid 不受影响
        assertNotNull(messageStore.getById(m3.getId()));
    }

    @Test
    @DisplayName("大载荷存储与读取: 万字文本与超大 JSON 工具入参无损还原")
    void testLargePayloadStoreAndRetrieve() {
        String largeText = "A".repeat(20000);
        String largeJson = "{\"data\":\"" + "B".repeat(15000) + "\"}";

        Message msg = Message.builder()
                .cid(9001L)
                .role(MessageRole.ASSISTANT)
                .content(largeText)
                .toolCalls(largeJson)
                .build();
        messageStore.insert(msg);

        Message fetched = messageStore.getById(msg.getId());
        assertNotNull(fetched);
        assertEquals(20000, fetched.getContent().length());
        assertEquals(largeText, fetched.getContent());
        assertEquals(largeJson, fetched.getToolCalls());
    }

    @Test
    @DisplayName("防御性边界测试: null 入参安全防御")
    void testNullGuards() {
        assertNull(messageStore.getById(null));
        assertNull(messageStore.getByQuery(null));
        assertTrue(messageStore.listByQuery(null).isEmpty());
        assertFalse(messageStore.existsByQuery(null));
        messageStore.insert(null);
        messageStore.insertBatch(null);
        assertEquals(0, messageStore.updateById(null));
        assertEquals(0, messageStore.updateByPatch(null));
        assertEquals(0, messageStore.updateByQuery(null, null));
        messageStore.deleteById(null);
        assertEquals(0, messageStore.deleteByQuery(null));
    }
}
