package com.stioc.cute.runtime.store;

import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.engine.store.types.ConversationQuery;
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
 * 宿主会话存储供血实现真实 SQLite 落库集成测试。
 * <p>
 * 覆盖单实体/批量新增、自增主键回填、全属性无损查询、排序分页、整实体覆盖、
 * Patch 差量更新（含 null 清空语义）、Delta 差量集合原子运算（去重、加减、清空、直写）、
 * 物理删除与自定义批量复位 SQL。
 * </p>
 */
@DisplayName("ConversationStoreImpl 真实 SQLite 落库集成测试")
class ConversationStoreImplTest extends AbstractStoreIntegrationTest {

    @Test
    @DisplayName("insert: 成功新增单实体并自动回填自增 ID 与时间")
    void testInsert_SuccessAndIdGenerated() {
        Conversation conv = Conversation.builder()
                .workspaceId("ws-01")
                .title("测试会话 1")
                .providerGroup("default")
                .providerModelName("deepseek-chat")
                .permissionMode("DEFAULT")
                .loopCount(1)
                .loopRunning(0)
                .build();

        Conversation inserted = conversationStore.insert(conv);
        assertNotNull(inserted);
        assertNotNull(inserted.getId(), "自增主键 ID 必须由数据库生成并回填");
        assertTrue(inserted.getId() > 0);
        assertNotNull(inserted.getCreateTime());
        assertNotNull(inserted.getUpdateTime());

        // 从数据库重新读取验证
        Conversation fetched = conversationStore.getById(inserted.getId());
        assertNotNull(fetched);
        assertEquals("ws-01", fetched.getWorkspaceId());
        assertEquals("测试会话 1", fetched.getTitle());
        assertEquals("deepseek-chat", fetched.getProviderModelName());
    }

    @Test
    @DisplayName("insertBatch: 批量插入多条会话并全部回填自增 ID")
    void testInsertBatch_Success() {
        List<Conversation> list = List.of(
                Conversation.builder().workspaceId("ws-batch").title("会话 A").build(),
                Conversation.builder().workspaceId("ws-batch").title("会话 B").build(),
                Conversation.builder().workspaceId("ws-batch").title("会话 C").build()
        );

        List<Conversation> result = conversationStore.insertBatch(list);
        assertEquals(3, result.size());
        for (Conversation c : result) {
            assertNotNull(c.getId());
            Conversation found = conversationStore.getById(c.getId());
            assertNotNull(found);
            assertEquals("ws-batch", found.getWorkspaceId());
        }
    }

    @Test
    @DisplayName("getById: 查询存在实体全字段无损还原，查询不存在实体返回 null")
    void testGetById_FoundAndNotFound() {
        Conversation conv = Conversation.builder()
                .workspaceId("ws-full")
                .title("全字段测试会话")
                .providerGroup("google")
                .providerModelName("gemini-2.5-pro")
                .permissionMode("AUTO")
                .parentCid(888L)
                .inputTokens(1234L)
                .outputTokens(567L)
                .cachedTokens(89L)
                .callToolCount(5)
                .waitingToolIds("call_1,call_2")
                .waitingSubCids("sub_10,sub_11")
                .loopCount(3)
                .loopRunning(1)
                .build();
        conversationStore.insert(conv);

        Conversation fetched = conversationStore.getById(conv.getId());
        assertNotNull(fetched);
        assertEquals("ws-full", fetched.getWorkspaceId());
        assertEquals("全字段测试会话", fetched.getTitle());
        assertEquals("google", fetched.getProviderGroup());
        assertEquals("gemini-2.5-pro", fetched.getProviderModelName());
        assertEquals("AUTO", fetched.getPermissionMode());
        assertEquals(888L, fetched.getParentCid());
        assertEquals(1234L, fetched.getInputTokens());
        assertEquals(567L, fetched.getOutputTokens());
        assertEquals(89L, fetched.getCachedTokens());
        assertEquals(5, fetched.getCallToolCount());
        assertEquals("call_1,call_2", fetched.getWaitingToolIds());
        assertEquals("sub_10,sub_11", fetched.getWaitingSubCids());
        assertEquals(3, fetched.getLoopCount());
        assertEquals(1, fetched.getLoopRunning());

        // 查询不存在的 ID
        assertNull(conversationStore.getById(999999L));
    }

    @Test
    @DisplayName("getByQuery: 根据 workspaceId 与 title 精确定位会话")
    void testGetByQuery_ByWorkspaceIdAndTitle() {
        conversationStore.insert(Conversation.builder().workspaceId("ws-q1").title("标题 1").build());
        conversationStore.insert(Conversation.builder().workspaceId("ws-q1").title("标题 2").build());

        Conversation found = conversationStore.getByQuery(ConversationQuery.builder()
                .workspaceId("ws-q1")
                .title("标题 2")
                .build());
        assertNotNull(found);
        assertEquals("标题 2", found.getTitle());

        // 不存在的条件查空
        assertNull(conversationStore.getByQuery(ConversationQuery.builder()
                .workspaceId("ws-q1")
                .title("无此标题")
                .build()));
    }

    @Test
    @DisplayName("listByQuery: 验证排序与分页条数限制")
    void testListByQuery_WithSortAndLimit() {
        conversationStore.insert(Conversation.builder().workspaceId("ws-page").title("A").build());
        conversationStore.insert(Conversation.builder().workspaceId("ws-page").title("B").build());
        conversationStore.insert(Conversation.builder().workspaceId("ws-page").title("C").build());

        ConversationQuery query = ConversationQuery.builder()
                .workspaceId("ws-page")
                .sortField("id")
                .sortDirection(SortDirection.DESC)
                .limit(2)
                .build();

        List<Conversation> list = conversationStore.listByQuery(query);
        assertEquals(2, list.size());
        assertEquals("C", list.get(0).getTitle());
        assertEquals("B", list.get(1).getTitle());
    }

    @Test
    @DisplayName("existsByQuery: 存在性布尔判定")
    void testExistsByQuery() {
        conversationStore.insert(Conversation.builder().workspaceId("ws-exist").title("存在").build());

        assertTrue(conversationStore.existsByQuery(ConversationQuery.builder().workspaceId("ws-exist").build()));
        assertFalse(conversationStore.existsByQuery(ConversationQuery.builder().workspaceId("ws-none").build()));
    }

    @Test
    @DisplayName("updateById: 整实体覆盖更新，updateTime 自动刷新")
    void testUpdateById_Success() {
        Conversation conv = Conversation.builder()
                .workspaceId("ws-up")
                .title("原标题")
                .loopRunning(0)
                .build();
        conversationStore.insert(conv);
        LocalDateTime initialUpdateTime = conv.getUpdateTime();

        conv.setTitle("新标题");
        conv.setLoopRunning(1);
        int rows = conversationStore.updateById(conv);
        assertEquals(1, rows);

        Conversation updated = conversationStore.getById(conv.getId());
        assertEquals("新标题", updated.getTitle());
        assertEquals(1, updated.getLoopRunning());
        assertNotNull(updated.getUpdateTime());
        assertTrue(!updated.getUpdateTime().isBefore(initialUpdateTime));
    }

    @Test
    @DisplayName("updateByQuery: 条件批量更新且跳过 entity 中的 null 字段")
    void testUpdateByQuery_SkipNullFields() {
        Conversation conv = Conversation.builder()
                .workspaceId("ws-query-up")
                .title("初始标题")
                .providerGroup("openai")
                .build();
        conversationStore.insert(conv);

        // 仅修改 title，providerGroup 为 null
        Conversation patchEntity = Conversation.builder()
                .title("已通过条件更新")
                .build();
        int rows = conversationStore.updateByQuery(patchEntity, ConversationQuery.builder()
                .workspaceId("ws-query-up")
                .build());
        assertEquals(1, rows);

        Conversation after = conversationStore.getById(conv.getId());
        assertEquals("已通过条件更新", after.getTitle());
        // providerGroup 未被覆盖为 null
        assertEquals("openai", after.getProviderGroup());
    }

    @Test
    @DisplayName("updateByPatch: 部分字段更新，未涉及字段保持原值")
    void testUpdateByPatch_PartialUpdate() {
        Conversation conv = Conversation.builder()
                .workspaceId("ws-patch")
                .title("原标题")
                .loopCount(0)
                .providerModelName("gpt-4o")
                .build();
        conversationStore.insert(conv);

        ConversationPatch patch = new ConversationPatch(conv.getId())
                .set(Conversation::getTitle, "Patch新标题")
                .set(Conversation::getLoopCount, 5);

        int rows = conversationStore.updateByPatch(patch);
        assertEquals(1, rows);

        Conversation updated = conversationStore.getById(conv.getId());
        assertEquals("Patch新标题", updated.getTitle());
        assertEquals(5, updated.getLoopCount());
        // 未涉及的字段保持原值
        assertEquals("gpt-4o", updated.getProviderModelName());
        assertEquals("ws-patch", updated.getWorkspaceId());
    }

    @Test
    @DisplayName("updateByPatch: 显式指定 null 字段触发清空语义 (UpdateEntity)")
    void testUpdateByPatch_NullClearSemantic() {
        Conversation conv = Conversation.builder()
                .workspaceId("ws-patch-null")
                .title("带父会话")
                .parentCid(999L)
                .waitingToolIds("tool-1")
                .build();
        conversationStore.insert(conv);

        // 显式将 parentCid 和 waitingToolIds 清空为 null
        ConversationPatch patch = new ConversationPatch(conv.getId())
                .set(Conversation::getParentCid, null)
                .set(Conversation::getWaitingToolIds, null);

        int rows = conversationStore.updateByPatch(patch);
        assertEquals(1, rows);

        Conversation updated = conversationStore.getById(conv.getId());
        assertNull(updated.getParentCid(), "显式 patch 为 null 的属性必须在库中被置为 NULL");
        assertNull(updated.getWaitingToolIds(), "显式 patch 为 null 的集合属性必须在库中被置为 NULL");
        assertEquals("带父会话", updated.getTitle());
    }

    @Test
    @DisplayName("updateByDelta: waitingToolIds 集合原子追加与自动去重")
    void testUpdateByDelta_WaitingToolIds_Add() {
        Conversation conv = Conversation.builder()
                .workspaceId("ws-delta")
                .title("差量测试")
                .waitingToolIds(null)
                .build();
        conversationStore.insert(conv);

        // 1. 空值追加
        conversationStore.updateByDelta(conv.getId(), Conversation::getWaitingToolIds, "+call_1");
        Conversation after1 = conversationStore.getById(conv.getId());
        assertEquals("call_1", after1.getWaitingToolIds());

        // 2. 追加第二项
        conversationStore.updateByDelta(conv.getId(), Conversation::getWaitingToolIds, "+call_2");
        Conversation after2 = conversationStore.getById(conv.getId());
        assertEquals("call_1,call_2", after2.getWaitingToolIds());

        // 3. 重复追加第一项（自动去重）
        conversationStore.updateByDelta(conv.getId(), Conversation::getWaitingToolIds, "+call_1");
        Conversation after3 = conversationStore.getById(conv.getId());
        assertEquals("call_1,call_2", after3.getWaitingToolIds());
    }

    @Test
    @DisplayName("updateByDelta: waitingToolIds 集合原子移除与全部清空置 null")
    void testUpdateByDelta_WaitingToolIds_Remove() {
        Conversation conv = Conversation.builder()
                .workspaceId("ws-delta-rm")
                .title("差量删除")
                .waitingToolIds("call_1,call_2,call_3")
                .build();
        conversationStore.insert(conv);

        // 1. 移除中间一项
        conversationStore.updateByDelta(conv.getId(), Conversation::getWaitingToolIds, "-call_2");
        Conversation after1 = conversationStore.getById(conv.getId());
        assertEquals("call_1,call_3", after1.getWaitingToolIds());

        // 2. 移除不存在的项（安全无变动）
        conversationStore.updateByDelta(conv.getId(), Conversation::getWaitingToolIds, "-call_999");
        Conversation after2 = conversationStore.getById(conv.getId());
        assertEquals("call_1,call_3", after2.getWaitingToolIds());

        // 3. 移除剩余全部项
        conversationStore.updateByDelta(conv.getId(), Conversation::getWaitingToolIds, "-call_1");
        conversationStore.updateByDelta(conv.getId(), Conversation::getWaitingToolIds, "-call_3");
        Conversation after3 = conversationStore.getById(conv.getId());
        assertNull(after3.getWaitingToolIds(), "所有项移除后必须清空为 null，而非空串");
    }

    @Test
    @DisplayName("updateByDelta: waitingSubCids 子智能体 cid 集合增减")
    void testUpdateByDelta_WaitingSubCids_AddAndRemove() {
        Conversation conv = Conversation.builder()
                .workspaceId("ws-subcid")
                .title("子智能体会话")
                .build();
        conversationStore.insert(conv);

        conversationStore.updateByDelta(conv.getId(), Conversation::getWaitingSubCids, "+101");
        conversationStore.updateByDelta(conv.getId(), Conversation::getWaitingSubCids, "+102");
        Conversation afterAdd = conversationStore.getById(conv.getId());
        assertEquals("101,102", afterAdd.getWaitingSubCids());

        conversationStore.updateByDelta(conv.getId(), Conversation::getWaitingSubCids, "-101");
        Conversation afterRm = conversationStore.getById(conv.getId());
        assertEquals("102", afterRm.getWaitingSubCids());
    }

    @Test
    @DisplayName("updateByDelta: 非 +/- 前缀全量直写覆盖")
    void testUpdateByDelta_DirectOverwrite() {
        Conversation conv = Conversation.builder()
                .workspaceId("ws-direct")
                .title("直写测试")
                .waitingToolIds("old_1,old_2")
                .build();
        conversationStore.insert(conv);

        conversationStore.updateByDelta(conv.getId(), Conversation::getWaitingToolIds, "direct_tools_123");
        Conversation updated = conversationStore.getById(conv.getId());
        assertEquals("direct_tools_123", updated.getWaitingToolIds());
    }

    @Test
    @DisplayName("updateByDelta: delta 为 null 不变，delta 为空串清空为 null")
    void testUpdateByDelta_NullOrEmptyDelta() {
        Conversation conv = Conversation.builder()
                .workspaceId("ws-null-delta")
                .title("测试空delta")
                .waitingToolIds("keep_me")
                .build();
        conversationStore.insert(conv);

        // 1. null delta -> 保持原样
        conversationStore.updateByDelta(conv.getId(), Conversation::getWaitingToolIds, null);
        assertEquals("keep_me", conversationStore.getById(conv.getId()).getWaitingToolIds());

        // 2. 空串 delta -> 清空为 null
        conversationStore.updateByDelta(conv.getId(), Conversation::getWaitingToolIds, "");
        assertNull(conversationStore.getById(conv.getId()).getWaitingToolIds());
    }

    @Test
    @DisplayName("updateByDelta: 不存在的 cid 安全跳过无异常")
    void testUpdateByDelta_NonExistentCid() {
        // 不存在的 cid 不抛异常
        conversationStore.updateByDelta(777777L, Conversation::getWaitingToolIds, "+call_x");
    }

    @Test
    @DisplayName("deleteById & deleteByQuery: 物理删除会话记录")
    void testDeleteOperations() {
        Conversation c1 = conversationStore.insert(Conversation.builder().workspaceId("ws-del").title("D1").build());
        Conversation c2 = conversationStore.insert(Conversation.builder().workspaceId("ws-del").title("D2").build());
        Conversation c3 = conversationStore.insert(Conversation.builder().workspaceId("ws-other").title("D3").build());

        // 1. 根据 ID 删除 c1
        conversationStore.deleteById(c1.getId());
        assertNull(conversationStore.getById(c1.getId()));

        // 2. 根据 Query 删除 ws-del 剩余的 c2
        int rows = conversationStore.deleteByQuery(ConversationQuery.builder().workspaceId("ws-del").build());
        assertEquals(1, rows);
        assertNull(conversationStore.getById(c2.getId()));

        // 3. ws-other 不受影响
        assertNotNull(conversationStore.getById(c3.getId()));
    }

    @Test
    @DisplayName("ConversationMapper: resetAllLoopRunning 将所有运行中标记批量重置为 0")
    void testConversationMapper_ResetAllLoopRunning() {
        Conversation c1 = conversationStore.insert(Conversation.builder().workspaceId("ws-lr").loopRunning(1).build());
        Conversation c2 = conversationStore.insert(Conversation.builder().workspaceId("ws-lr").loopRunning(1).build());
        Conversation c3 = conversationStore.insert(Conversation.builder().workspaceId("ws-lr").loopRunning(0).build());

        conversationMapper.resetAllLoopRunning();

        assertEquals(0, conversationStore.getById(c1.getId()).getLoopRunning());
        assertEquals(0, conversationStore.getById(c2.getId()).getLoopRunning());
        assertEquals(0, conversationStore.getById(c3.getId()).getLoopRunning());
    }

    @Test
    @DisplayName("防御性边界测试: null 入参安全防御")
    void testNullGuards() {
        assertNull(conversationStore.getById(null));
        assertNull(conversationStore.getByQuery(null));
        assertTrue(conversationStore.listByQuery(null).isEmpty());
        assertFalse(conversationStore.existsByQuery(null));
        assertNull(conversationStore.insert(null));
        assertTrue(conversationStore.insertBatch(null).isEmpty());
        assertEquals(0, conversationStore.updateById(null));
        assertEquals(0, conversationStore.updateByPatch(null));
        assertEquals(0, conversationStore.updateByQuery(null, null));
        conversationStore.deleteById(null);
        assertEquals(0, conversationStore.deleteByQuery(null));
    }
}
