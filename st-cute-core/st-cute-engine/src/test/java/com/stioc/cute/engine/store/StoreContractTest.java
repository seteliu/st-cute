package com.stioc.cute.engine.store;

import com.stioc.cute.engine.common.SFunction;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.engine.store.types.ConversationQuery;
import com.stioc.cute.engine.store.types.MessagePatch;
import com.stioc.cute.engine.store.types.MessageQuery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储供血契约与差量载荷语义守护测试。
 * <p>
 * 引擎与宿主之间以 Store 接口为唯一契约，宿主实现（MyBatis-Flex）依赖该契约的
 * 命名与签名稳定。本测试以反射方式把契约固化下来：一旦方法被改名/改签名导致
 * 宿主实现脱节，在此处即时报错，而不是等到运行期落库失败。
 * </p>
 * <p>
 * 另覆盖差量载荷（Patch）的键推导语义——Patch 是引擎写入落库的唯一载体，
 * 键错位将导致字段静默写错。
 * </p>
 */
class StoreContractTest {

    // ── 契约命名与签名 ──

    /**
     * 存储接口禁止 find 前缀，单体查询禁止返回 Optional（统一 get/list/exists 口径）
     */
    @Test
    void enforcesNamingConventionOnStores() {
        for (Class<?> store : List.of(ConversationStore.class, MessageStore.class)) {
            for (Method m : store.getMethods()) {
                assertFalse(m.getName().startsWith("find"),
                        store.getSimpleName() + " 禁止 find 前缀方法: " + m.getName());
                if (m.getName().startsWith("get")) {
                    assertFalse(Optional.class.equals(m.getReturnType()),
                            store.getSimpleName() + " 单体查询禁止返回 Optional: " + m.getName());
                }
            }
        }
    }

    /**
     * 消息存储的核心方法签名必须存在（防宿主实现与引擎调用脱节）
     */
    @Test
    void keepsMessageStoreCoreSignatures() {
        assertDoesNotThrow(() -> MessageStore.class.getMethod("getById", Long.class));
        assertDoesNotThrow(() -> MessageStore.class.getMethod("getByQuery", MessageQuery.class));
        assertDoesNotThrow(() -> MessageStore.class.getMethod("listByQuery", MessageQuery.class));
        assertDoesNotThrow(() -> MessageStore.class.getMethod("existsByQuery", MessageQuery.class));
        assertDoesNotThrow(() -> MessageStore.class.getMethod("insert",
                com.stioc.cute.engine.store.types.Message.class));
        assertDoesNotThrow(() -> MessageStore.class.getMethod("updateByPatch", MessagePatch.class));
        assertDoesNotThrow(() -> MessageStore.class.getMethod("updateByQuery",
                com.stioc.cute.engine.store.types.Message.class, MessageQuery.class));
        assertDoesNotThrow(() -> MessageStore.class.getMethod("deleteByQuery", MessageQuery.class));
    }

    /**
     * 会话存储的核心方法签名必须存在，且含差量集合更新的强类型签名
     */
    @Test
    void keepsConversationStoreCoreSignatures() {
        assertDoesNotThrow(() -> ConversationStore.class.getMethod("getById", Long.class));
        assertDoesNotThrow(() -> ConversationStore.class.getMethod("getByQuery", ConversationQuery.class));
        assertDoesNotThrow(() -> ConversationStore.class.getMethod("listByQuery", ConversationQuery.class));
        assertDoesNotThrow(() -> ConversationStore.class.getMethod("updateByPatch", ConversationPatch.class));
        assertDoesNotThrow(() -> ConversationStore.class.getMethod("updateByQuery",
                com.stioc.cute.engine.store.types.Conversation.class, ConversationQuery.class));
        // 集合差量更新必须以 SFunction 强类型指定字段
        assertDoesNotThrow(() -> ConversationStore.class.getMethod("updateByDelta",
                Long.class, SFunction.class, String.class));
    }

    /**
     * 差量更新统一命名为 updateByPatch / updateByQuery，禁止旧名回流
     */
    @Test
    void forbidsLegacyUpdateNames() {
        for (Class<?> store : List.of(ConversationStore.class, MessageStore.class)) {
            for (Method m : store.getMethods()) {
                assertFalse("updateByFieldMap".equals(m.getName()),
                        store.getSimpleName() + " 禁止旧命名回流: " + m.getName());
            }
        }
    }

    // ── 差量载荷语义 ──

    /**
     * MessagePatch 经方法引用写入后，键必须推导为真实字段名且可原样读回
     */
    @Test
    void messagePatchDerivesFieldKeysFromGetter() {
        MessagePatch patch = new MessagePatch(1L)
                .content("正文")
                .reasoningContent("思考")
                .status(com.stioc.cute.engine.store.types.MessageStatus.SUCCESS);

        assertTrue(patch.has("content"), "键应推导为字段名 content");
        assertTrue(patch.has("reasoningContent"), "键应推导为字段名 reasoningContent");
        assertTrue(patch.has("status"), "键应推导为字段名 status");

        assertEquals("正文", patch.get("content"));
        assertEquals("正文", patch.get(com.stioc.cute.engine.store.types.Message::getContent),
                "方法引用读取应与字符串键读取一致");
    }

    /**
     * Patch 应显式支持 null 值（清空语义），且 null 与「未设置」必须可区分
     */
    @Test
    void messagePatchDistinguishesNullFromAbsent() {
        MessagePatch patch = new MessagePatch(1L).content(null);

        assertTrue(patch.has("content"), "显式置 null 应视为已设置（清空语义）");
        assertNull(patch.get("content"), "清空字段的值应为 null");
        assertFalse(patch.has("reasoningContent"), "未设置的字段不应出现在载荷中");
    }

    /**
     * Patch 应支持移除字段（撤销本次差量）
     */
    @Test
    void messagePatchSupportsRemoval() {
        MessagePatch patch = new MessagePatch(1L).content("x");
        assertTrue(patch.has("content"));

        patch.remove(com.stioc.cute.engine.store.types.Message::getContent);
        assertFalse(patch.has("content"), "移除后字段不应再出现在载荷中");
    }

    /**
     * 空 Patch 应如实反映为空（宿主据此可跳过无意义的落库往返）
     */
    @Test
    void messagePatchReportsEmptiness() {
        assertTrue(new MessagePatch(1L).isEmpty(), "未写入任何字段应为空载荷");
        assertFalse(new MessagePatch(1L).content("x").isEmpty(), "写入字段后不应为空载荷");
    }

    /**
     * ConversationPatch 的键推导与集合差量字段语义
     */
    @Test
    void conversationPatchDerivesFieldKeys() {
        ConversationPatch patch = new ConversationPatch(1L)
                .title("标题")
                .loopCount(3)
                .waitingToolIds("+call_1");

        assertTrue(patch.has("title"));
        assertTrue(patch.has("loopCount"));
        assertTrue(patch.has("waitingToolIds"));
        assertEquals("+call_1", patch.get("waitingToolIds"), "集合差量应原样保留差量表达");
        assertEquals(3, patch.get(com.stioc.cute.engine.store.types.Conversation::getLoopCount));
    }

    /**
     * ConversationPatch 的 null 清空语义（集合字段清空依赖此行为）
     */
    @Test
    void conversationPatchSupportsNullClearing() {
        ConversationPatch patch = new ConversationPatch(1L).waitingToolIds(null);

        assertTrue(patch.has("waitingToolIds"), "显式置 null 应视为已设置（清空集合）");
        assertNull(patch.get("waitingToolIds"));
    }

    /**
     * Patch 的字段写入顺序应稳定（保证落库 SQL 的字段顺序可预期）
     */
    @Test
    void patchPreservesInsertionOrder() {
        MessagePatch patch = new MessagePatch(1L).content("a").status(null).callId("c");
        List<String> keys = List.copyOf(patch.getChanged().keySet());

        assertEquals(List.of("content", "status", "callId"), keys);
    }
}
