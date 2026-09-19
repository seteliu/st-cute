package com.stioc.cute.engine.testkit;

import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.engine.store.types.ConversationQuery;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessagePatch;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.store.types.SortDirection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存 Store 替身的语义守护测试。
 * <p>
 * 这两个替身是整个 L2 闭环测试的地基：若它们与宿主 MyBatis-Flex 实现语义不一致，
 * 上层所有闭环用例都会在错误的假设上「假绿」——这比测试失败更危险。故此处把
 * 宿主已验证的关键语义逐条固化为断言，替身一旦被改错，立刻在此处报警。
 * </p>
 * <p>
 * 重点固化的三条易错语义：
 * <ol>
 *   <li><b>insert 自增并丢弃传入 id</b>：引擎依赖事件发布后能读到回填的自增 id；</li>
 *   <li><b>updateByPatch 的 null 是清空，updateByQuery 的 null 是跳过</b>：两套 null 规则并存；</li>
 *   <li><b>updateByDelta 的 null 是空操作而非清空</b>：清空必须走 Patch 传 null。</li>
 * </ol>
 * </p>
 */
class InMemoryStoreSemanticsTest {

    // ──────────────────────────────────────────────
    // 消息存储
    // ──────────────────────────────────────────────

    /**
     * insert 必须分配自增 id 并回填到入参实体（引擎读 entity.getId() 的关键依赖）
     */
    @Test
    void messageInsertBackfillsAutoIncrementId() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        Message first = Message.builder().cid(1L).role(MessageRole.USER).content("a").build();
        Message second = Message.builder().cid(1L).role(MessageRole.ASSISTANT).content("b").build();

        store.insert(first);
        store.insert(second);

        assertNotNull(first.getId(), "insert 后实体必须被回填 id");
        assertNotNull(second.getId());
        assertNotEquals(first.getId(), second.getId(), "两条消息的 id 必须互异");
        assertEquals(2, store.size());
    }

    /**
     * 传入的显式 id 应被丢弃（宿主 @Id(keyType = Auto) 的真实行为）
     */
    @Test
    void messageInsertDiscardsExplicitId() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        Message withExplicitId = Message.builder().id(500L).cid(1L)
                .role(MessageRole.USER).content("a").build();

        store.insert(withExplicitId);

        assertNotEquals(500L, withExplicitId.getId(), "显式 id 应被丢弃并重新分配（对齐宿主自增策略）");
        assertNull(store.getById(500L), "按原显式 id 不应查到任何记录");
        assertNotNull(store.getById(withExplicitId.getId()));
    }

    /**
     * insertBatch 必须逐条回填 id（宿主刻意未使用真正的批量 API 以保证回填）
     */
    @Test
    void messageInsertBatchBackfillsEachId() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        Message a = Message.builder().cid(1L).role(MessageRole.USER).content("a").build();
        Message b = Message.builder().cid(1L).role(MessageRole.USER).content("b").build();

        store.insertBatch(List.of(a, b));

        assertNotNull(a.getId(), "批量插入也必须逐条回填 id");
        assertNotNull(b.getId());
        assertEquals(2, store.size());
    }

    /**
     * 查询结果必须是深拷贝：外部就地修改不得污染存储
     */
    @Test
    void messageQueryReturnsDetachedCopies() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        store.insert(Message.builder().cid(1L).role(MessageRole.USER).content("原始").build());

        Message fetched = store.listByQuery(MessageQuery.builder().cid(1L).build()).get(0);
        fetched.setContent("被就地改写的值");

        assertEquals("原始", store.getById(fetched.getId()).getContent(),
                "查询结果被就地修改不应影响存储内部数据");
    }

    /**
     * updateByPatch：显式 null 必须真实清空字段（清空语义）
     */
    @Test
    void messagePatchNullClearsField() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        Message msg = Message.builder().cid(1L).role(MessageRole.ASSISTANT)
                .content("原内容").reasoningContent("原思考").build();
        store.insert(msg);

        store.updateByPatch(new MessagePatch(msg.getId()).cid(1L).reasoningContent(null));

        assertNull(store.getById(msg.getId()).getReasoningContent(), "Patch 显式 null 应清空字段");
        assertEquals("原内容", store.getById(msg.getId()).getContent(), "未出现在载荷中的字段不得被动到");
    }

    /**
     * updateByPatch：未出现在载荷中的字段保持不变
     */
    @Test
    void messagePatchLeavesAbsentFieldsUntouched() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        Message msg = Message.builder().cid(1L).role(MessageRole.ASSISTANT)
                .content("原内容").reasoningContent("原思考").build();
        store.insert(msg);

        store.updateByPatch(new MessagePatch(msg.getId()).cid(1L).content("新内容"));

        Message updated = store.getById(msg.getId());
        assertEquals("新内容", updated.getContent());
        assertEquals("原思考", updated.getReasoningContent(), "未设置字段必须保持原值");
    }

    /**
     * updateByQuery：null 字段一律跳过（与 Patch 的清空语义相反）
     */
    @Test
    void updateByQuerySkipsNullFields() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        Message msg = Message.builder().cid(1L).role(MessageRole.ASSISTANT)
                .content("原内容").reasoningContent("原思考").build();
        store.insert(msg);

        // 该实体只有 status 非 null（visibleToUser/visibleToModel 因 @Builder.Default 亦非 null）
        store.updateByQuery(Message.builder().role(MessageRole.ASSISTANT)
                        .status(MessageStatus.FAILED)
                        .visibleToUser(true)
                        .visibleToModel(true)
                        .build(),
                MessageQuery.builder().cid(1L).build());

        Message updated = store.getById(msg.getId());
        assertEquals(MessageStatus.FAILED, updated.getStatus(), "非 null 字段应被覆盖");
        assertEquals("原内容", updated.getContent(), "null 字段必须跳过（不得被清空）");
        assertEquals("原思考", updated.getReasoningContent(), "null 字段必须跳过");
    }

    /**
     * updateByQuery 的 Boolean 默认值语义：@Builder.Default=true 会让字段恒非 null 并参与覆盖
     * <p>
     * 这是宿主已验证的真实行为（引擎的归档链路正依赖它），替身必须复刻，否则归档用例会假绿。
     * </p>
     */
    @Test
    void updateByQueryOverwritesBooleansDueToBuilderDefault() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        Message msg = Message.builder().cid(1L).role(MessageRole.USER).content("a")
                .visibleToUser(false).visibleToModel(false).build();
        store.insert(msg);

        // builder 未显式设置时，这两个字段因 @Builder.Default 依然为 true（非 null）
        store.updateByQuery(Message.builder().visibleToModel(false).build(),
                MessageQuery.builder().cid(1L).build());

        Message updated = store.getById(msg.getId());
        assertFalse(updated.getVisibleToModel(), "显式设置的 false 应落库");
        assertTrue(updated.getVisibleToUser(),
                "visibleToUser 因 @Builder.Default=true 属非 null，会被一并覆盖（宿主既有语义）");
    }

    /**
     * 更新不存在的记录应返回 0（引擎据此做静默失败留痕）
     */
    @Test
    void updateReturnsZeroForMissingRow() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        assertEquals(0, store.updateByPatch(new MessagePatch(999L).content("x")));
        assertEquals(0, store.updateByQuery(Message.builder().content("x").build(),
                MessageQuery.builder().cid(999L).build()));
    }

    /**
     * getByQuery 会话内命中多条时应只取一条（宿主无条件 limit 1）
     */
    @Test
    void getByQueryReturnsAtMostOneRow() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        store.insert(Message.builder().cid(1L).role(MessageRole.USER).content("first").build());
        store.insert(Message.builder().cid(1L).role(MessageRole.USER).content("second").build());

        Message found = store.getByQuery(MessageQuery.builder().cid(1L).build());

        assertNotNull(found);
        assertEquals("first", found.getContent(), "无排序时按 id 升序取首条");
    }

    /**
     * 排序方向生效时应按指定方向取首条
     */
    @Test
    void respectsSortDirectionWhenConfigured() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        store.insert(Message.builder().cid(1L).role(MessageRole.USER).content("first").build());
        store.insert(Message.builder().cid(1L).role(MessageRole.USER).content("second").build());

        Message latest = store.getByQuery(MessageQuery.builder()
                .cid(1L)
                .sortField("id")
                .sortDirection(SortDirection.DESC)
                .build());

        assertEquals("second", latest.getContent(), "降序应按 id 倒序取最新一条");
    }

    /**
     * 条件查询应覆盖 roles / statuses / excludedRoles / callId 等组合条件
     */
    @Test
    void filtersByCompositeConditions() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        store.insert(Message.builder().cid(1L).role(MessageRole.USER).content("u")
                .status(MessageStatus.PENDING).build());
        store.insert(Message.builder().cid(1L).role(MessageRole.TOOL).content("t")
                .status(MessageStatus.WAITING_APPROVAL).callId("c1").build());
        store.insert(Message.builder().cid(1L).role(MessageRole.TOOL).content("t2")
                .status(MessageStatus.SUCCESS).callId("c2").build());

        List<Message> waiting = store.listByQuery(MessageQuery.builder()
                .cid(1L)
                .roles(List.of(MessageRole.TOOL))
                .statuses(List.of(MessageStatus.PENDING, MessageStatus.RUNNING, MessageStatus.WAITING_APPROVAL))
                .build());
        assertEquals(1, waiting.size(), "多角色多状态 IN 过滤应命中待审批的那条工具消息");

        assertNotNull(store.getByQuery(MessageQuery.builder().cid(1L).callId("c2").build()),
                "按 callId 应能定位工具消息");
        // 宿主对 callId 走 isNotBlank 判定：空白视为「未设置条件」，即该条件不参与过滤，
        // 于是退化为「只按 cid 查首条」，而非「查不到」——此处固化这一真实语义
        assertNotNull(store.getByQuery(MessageQuery.builder().cid(1L).callId("").build()),
                "空白 callId 视为未设置条件，应退化为只按 cid 过滤");
        assertNotNull(store.getByQuery(MessageQuery.builder().cid(1L).callId("c2").build()),
                "非空 callId 应精确命中");

        List<Message> noSystem = store.listByQuery(MessageQuery.builder()
                .cid(1L).excludedRoles(List.of(MessageRole.TOOL)).build());
        assertEquals(1, noSystem.size(), "excludedRoles 应排除工具消息");
    }

    /**
     * 无效条件（greaterThanId / createTimeBefore / 空条件）的过滤行为
     */
    @Test
    void filtersByRangeAndTimeConditions() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        Message m1 = Message.builder().cid(1L).role(MessageRole.USER).content("a").build();
        Message m2 = Message.builder().cid(1L).role(MessageRole.USER).content("b").build();
        store.insert(m1);
        store.insert(m2);

        List<Message> afterFirst = store.listByQuery(MessageQuery.builder()
                .cid(1L).greaterThanId(m1.getId()).build());
        assertEquals(1, afterFirst.size());
        assertEquals("b", afterFirst.get(0).getContent());

        assertTrue(store.existsByQuery(MessageQuery.builder().cid(1L).ids(List.of(m2.getId())).build()));
        assertFalse(store.existsByQuery(MessageQuery.builder().cid(1L).ids(List.of(9999L)).build()));
    }

    /**
     * 无条件删除必须被全表守卫拒绝（对齐宿主 ORM 行为，防止误清库）
     */
    @Test
    void rejectsUnconditionalDelete() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        store.insert(Message.builder().cid(1L).role(MessageRole.USER).content("a").build());

        assertThrows(IllegalArgumentException.class,
                () -> store.deleteByQuery(MessageQuery.builder().build()));
        assertEquals(1, store.size(), "被拒绝的删除不得产生任何副作用");
    }

    /**
     * 条件删除应只影响命中的记录
     */
    @Test
    void deletesOnlyMatchingRows() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        store.insert(Message.builder().cid(1L).role(MessageRole.USER).content("a").build());
        store.insert(Message.builder().cid(2L).role(MessageRole.USER).content("b").build());

        int deleted = store.deleteByQuery(MessageQuery.builder().cid(1L).build());

        assertEquals(1, deleted);
        assertEquals(1, store.size());
        assertNotNull(store.getByQuery(MessageQuery.builder().cid(2L).build()));
    }

    /**
     * updateTime 兜底：载荷未携带时应自动补当前时间（宿主无 ORM 自动填充）
     */
    @Test
    void fillsUpdateTimeWhenAbsent() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        Message msg = Message.builder().cid(1L).role(MessageRole.USER).content("a").build();
        store.insert(msg);
        var originalUpdateTime = store.getById(msg.getId()).getUpdateTime();

        store.updateByPatch(new MessagePatch(msg.getId()).cid(1L).content("b"));

        var afterUpdate = store.getById(msg.getId()).getUpdateTime();
        assertNotNull(afterUpdate, "updateTime 应被兜底填充");
        assertTrue(!afterUpdate.isBefore(originalUpdateTime), "updateTime 应被刷新为不早于原值");
    }

    // ──────────────────────────────────────────────
    // 会话存储与集合差量
    // ──────────────────────────────────────────────

    /**
     * 会话 insert 同样回填自增 id
     */
    @Test
    void conversationInsertBackfillsId() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        Conversation conv = Conversation.builder().title("会话").build();

        store.insert(conv);

        assertNotNull(conv.getId(), "会话 insert 必须回填 id");
        assertNotNull(store.getById(conv.getId()));
    }

    /**
     * 集合差量 "+id" 应追加（去重保序），"-id" 应移除
     */
    @Test
    void appliesCollectionDeltaAddAndRemove() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        Conversation conv = Conversation.builder().title("会话").build();
        store.insert(conv);
        long cid = conv.getId();

        store.updateByDelta(cid, Conversation::getWaitingToolIds, "+c1");
        assertEquals("c1", store.getById(cid).getWaitingToolIds());

        store.updateByDelta(cid, Conversation::getWaitingToolIds, "+c2,c3");
        assertEquals("c1,c2,c3", store.getById(cid).getWaitingToolIds(), "追加应保持插入顺序");

        store.updateByDelta(cid, Conversation::getWaitingToolIds, "+c1");
        assertEquals("c1,c2,c3", store.getById(cid).getWaitingToolIds(), "重复追加应去重");

        store.updateByDelta(cid, Conversation::getWaitingToolIds, "-c2,c3");
        assertEquals("c1", store.getById(cid).getWaitingToolIds(), "批量移除应支持逗号分隔");
    }

    /**
     * 集合差量：null 是空操作（非清空），空串才是清空
     */
    @Test
    void collectionDeltaNullIsNoOpWhileEmptyStringClears() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        Conversation conv = Conversation.builder().title("会话").waitingToolIds("c1").build();
        store.insert(conv);
        long cid = conv.getId();

        store.updateByDelta(cid, Conversation::getWaitingToolIds, null);
        assertEquals("c1", store.getById(cid).getWaitingToolIds(),
                "null 差量必须是空操作（极易被误实现为清空）");

        store.updateByDelta(cid, Conversation::getWaitingToolIds, "");
        assertNull(store.getById(cid).getWaitingToolIds(), "空串差量才表达清空");
    }

    /**
     * 集合差量：移除最后一项后应归一为 null（而非空串）
     */
    @Test
    void collectionDeltaNormalizesEmptyResultToNull() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        Conversation conv = Conversation.builder().title("会话").waitingToolIds("c1").build();
        store.insert(conv);

        store.updateByDelta(conv.getId(), Conversation::getWaitingToolIds, "-c1");

        assertNull(store.getById(conv.getId()).getWaitingToolIds(),
                "集合并集扣空后应归一为 null（宿主 LinkedHashSet 为空时返回 null）");
    }

    /**
     * 集合差量：非 +/- 前缀视为全量直写
     */
    @Test
    void collectionDeltaWithoutPrefixOverwrites() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        Conversation conv = Conversation.builder().title("会话").waitingToolIds("old").build();
        store.insert(conv);

        store.updateByDelta(conv.getId(), Conversation::getWaitingToolIds, "x,y");

        assertEquals("x,y", store.getById(conv.getId()).getWaitingToolIds());
    }

    /**
     * 不支持的差量字段必须抛异常（宿主有白名单校验）
     */
    @Test
    void rejectsDeltaOnUnsupportedField() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        Conversation conv = Conversation.builder().title("会话").build();
        store.insert(conv);

        assertThrows(IllegalArgumentException.class,
                () -> store.updateByDelta(conv.getId(), Conversation::getTitle, "+x"));
    }

    /**
     * 会话不存在时差量更新应静默返回（宿主 warn 后返回，不抛异常）
     */
    @Test
    void deltaOnMissingConversationIsSilent() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        store.updateByDelta(999L, Conversation::getWaitingToolIds, "+c1");
    }

    /**
     * ConversationPatch 的 null 集合字段表达清空
     */
    @Test
    void conversationPatchNullClearsCollection() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        Conversation conv = Conversation.builder().title("会话")
                .waitingToolIds("c1,c2").waitingSubCids("9").build();
        store.insert(conv);
        long cid = conv.getId();

        store.updateByPatch(new ConversationPatch(cid)
                .waitingToolIds(null).waitingSubCids(null).loopRunning(0));

        Conversation updated = store.getById(cid);
        assertNull(updated.getWaitingToolIds(), "Patch 传 null 应清空集合");
        assertNull(updated.getWaitingSubCids());
        assertEquals(0, updated.getLoopRunning());
    }

    /**
     * ConversationStore 的 loopRunning 条件查询（引擎据此判断会话是否在运行）
     */
    @Test
    void findsConversationByLoopRunningFlag() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        Conversation running = Conversation.builder().title("运行中").loopRunning(1).build();
        Conversation idle = Conversation.builder().title("空闲").loopRunning(0).build();
        store.insert(running);
        store.insert(idle);

        assertTrue(store.existsByQuery(ConversationQuery.builder().id(running.getId()).loopRunning(1).build()));
        assertFalse(store.existsByQuery(ConversationQuery.builder().id(idle.getId()).loopRunning(1).build()));
    }

    /**
     * 会话侧同样服从全表守卫
     */
    @Test
    void rejectsUnconditionalConversationDelete() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        store.insert(Conversation.builder().title("会话").build());

        assertThrows(IllegalArgumentException.class,
                () -> store.deleteByQuery(ConversationQuery.builder().build()));
        assertEquals(1, store.size());
    }
}
