package com.stioc.cute.engine.loop.message;

import com.stioc.cute.engine.assembly.EngineStores;
import com.stioc.cute.engine.llm.ChatOptionsFactory;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.testkit.EngineStubs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文窗口的内存裁剪顺序语义测试。
 * <p>
 * 压缩链路（{@code manageContextWindowSync}）走的是「内存裁剪 → 交给大模型总结」，
 * 裁剪结果直接决定摘要模型能看见什么。顺序一旦反了（例如先删 SYSTEM 或先删最近的 USER），
 * 摘要就会丢掉用户意图或角色约束，而这种损失在闭环用例里完全看不出来——只能靠对本方法的
 * 精确断言兜住：<b>先裁 TOOL、再从头删非 SYSTEM、SYSTEM 永不删、未超限一律不动</b>。
 * </p>
 * <p>
 * 本类只覆盖纯内存裁剪，不碰存储与网络（该方法除 token 估算外无任何外部依赖）。
 * </p>
 */
class LlmWindowManagerCropTest {

    /**
     * 未达目标限额时应原样返回同一实例且一条不删（提前分流分支）
     */
    @Test
    void returnsSameListWhenBelowThreshold() {
        LlmWindowManager manager = newManager();
        List<Message> messages = new ArrayList<>(List.of(
                msg(MessageRole.SYSTEM, "系统提示词"),
                msg(MessageRole.USER, "用户提问"),
                msg(MessageRole.ASSISTANT, "助手回答")));

        long total = tokensOf(manager, messages);
        List<Message> result = manager.cropHistoryToThresholdInMemory(context(), messages, total, total + 1);

        assertSame(messages, result, "未超限时必须原样返回入参实例，不做任何裁剪");
        assertEquals(3, result.size(), "未超限时不得删除任何消息");
    }

    /**
     * 阶段 1 只裁 TOOL：删够即停，USER / ASSISTANT / SYSTEM 一概不动
     */
    @Test
    void removesToolMessagesFirstAndStopsWhenEnough() {
        LlmWindowManager manager = newManager();
        Message system = msg(MessageRole.SYSTEM, "系统提示词");
        Message user = msg(MessageRole.USER, "请分析这段历史对话并给出结论");
        Message tool1 = msg(MessageRole.TOOL, "第一个工具返回的冗长内容".repeat(8));
        Message tool2 = msg(MessageRole.TOOL, "第二个工具返回的冗长内容".repeat(8));
        Message assistant = msg(MessageRole.ASSISTANT, "基于工具的初步结论");
        List<Message> messages = new ArrayList<>(List.of(system, user, tool1, tool2, assistant));

        // 目标设为「删掉第一个 TOOL 即可满足」，据此验证 TOOL 优先且删够即停
        long total = tokensOf(manager, messages);
        long tool1Tokens = manager.estimateTokens(tool1.getContent());
        double targetLimit = total - tool1Tokens + 1;

        List<Message> result = manager.cropHistoryToThresholdInMemory(context(), messages, total, targetLimit);

        assertTrue(!result.contains(tool1), "阶段 1 应优先裁掉 TOOL 消息");
        assertTrue(result.contains(tool2), "删够后应立刻停止，后续 TOOL 消息保留");
        assertTrue(result.contains(system), "SYSTEM 提示词永不裁剪");
        assertTrue(result.contains(user), "阶段 1 不得触碰 USER 消息");
        assertTrue(result.contains(assistant), "阶段 1 不得触碰 ASSISTANT 消息");
    }

    /**
     * 阶段 2：TOOL 全删光仍超限时，从第一条非 SYSTEM 开始删，直到只剩 SYSTEM
     */
    @Test
    void fallsBackToRemovingFromHeadUntilOnlySystemRemains() {
        LlmWindowManager manager = newManager();
        Message system = msg(MessageRole.SYSTEM, "系统提示词");
        List<Message> messages = new ArrayList<>(List.of(
                system,
                msg(MessageRole.USER, "最早的用户提问"),
                msg(MessageRole.TOOL, "工具返回内容".repeat(5)),
                msg(MessageRole.ASSISTANT, "中间的助手回答"),
                msg(MessageRole.USER, "最新的用户提问")));

        long total = tokensOf(manager, messages);
        // 目标压到 1：TOOL 删完后仍超限，必然进入阶段 2 一路删到只剩 SYSTEM
        List<Message> result = manager.cropHistoryToThresholdInMemory(context(), messages, total, 1);

        assertEquals(1, result.size(), "阶段 2 应删到只剩不可删的 SYSTEM");
        assertEquals(MessageRole.SYSTEM, result.get(0).getRole(), "唯一幸存者必须是 SYSTEM");
        assertEquals("系统提示词", result.get(0).getContent());
    }

    /**
     * 阶段 2 的删除顺序是「从头（最旧）开始」，且前置换行跳过 SYSTEM 去删其后第一条
     */
    @Test
    void stageTwoRemovesOldestNonSystemMessageFirst() {
        LlmWindowManager manager = newManager();
        Message system = msg(MessageRole.SYSTEM, "系统提示词");
        Message oldUser = msg(MessageRole.USER, "很久以前的用户提问内容");
        Message newUser = msg(MessageRole.USER, "刚刚提出的用户提问内容");
        List<Message> messages = new ArrayList<>(List.of(system, oldUser, newUser));

        // 目标设为「只需删掉最旧的一条非 SYSTEM 消息」
        long total = tokensOf(manager, messages);
        double targetLimit = total - manager.estimateTokens(oldUser.getContent()) + 1;

        List<Message> result = manager.cropHistoryToThresholdInMemory(context(), messages, total, targetLimit);

        assertTrue(!result.contains(oldUser), "阶段 2 必须从最旧的非 SYSTEM 消息开始删（跳过前置 SYSTEM）");
        assertTrue(result.contains(newUser), "只需删一条时应保留最新消息");
        assertTrue(result.contains(system), "阶段 2 同样不得删 SYSTEM");
    }

    /**
     * 全部为 SYSTEM 时无非 SYSTEM 可删：安全收场且不抛异常（阶段 2 的兜底 break 分支）
     */
    @Test
    void keepsAllSystemMessagesWhenNothingElseRemovable() {
        LlmWindowManager manager = newManager();
        Message system1 = msg(MessageRole.SYSTEM, "第一段系统提示词");
        Message system2 = msg(MessageRole.SYSTEM, "第二段系统提示词");
        List<Message> messages = new ArrayList<>(List.of(system1, system2));

        long total = tokensOf(manager, messages);
        List<Message> result = manager.cropHistoryToThresholdInMemory(context(), messages, total, 1);

        assertEquals(2, result.size(), "无非 SYSTEM 消息可删时应安全收场，不得空转或抛异常");
        assertTrue(result.contains(system1) && result.contains(system2));

        // 空列表边界：同样不得抛异常
        List<Message> empty = new ArrayList<>();
        assertTrue(manager.cropHistoryToThresholdInMemory(context(), empty, 0, 1).isEmpty());
    }

    /**
     * 构造一条消息（裁剪只读 role 与 content，其余字段与语义无关）
     */
    private static Message msg(MessageRole role, String content) {
        return Message.builder().cid(1001L).role(role).content(content).build();
    }

    /**
     * 列表内全部消息的 token 估算之和（与生产统计口径一致，保证裁剪算术可预期）
     */
    private static long tokensOf(LlmWindowManager manager, List<Message> messages) {
        long total = 0;
        for (Message message : messages) {
            total += manager.estimateTokens(message.getContent());
        }
        return total;
    }

    /**
     * 裁剪逻辑本身不消费上下文状态，此处给一个真实装配的上下文以满足签名
     */
    private static AgentContext context() {
        return EngineStubs.agentContext(1001L);
    }

    /**
     * 只需 token 估算能力，故除选项工厂外全部依赖传空（裁剪链路不触达它们）
     */
    private static LlmWindowManager newManager() {
        return new LlmWindowManager(new EngineStores(null, null), null, new ChatOptionsFactory(), null, null, null);
    }
}
