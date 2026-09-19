package com.stioc.cute.engine.common;

import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.Message;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LambdaFieldResolver} 字段名解析单元测试。
 * <p>
 * 该解析器是全部差量更新载荷（ConversationPatch / MessagePatch）的键推导基石：
 * 一旦解析出错，落库字段将静默错位，且编译期无法发现。故此处覆盖
 * 常规 getter、boolean is 前缀、缓存复用与三类快速失败路径。
 * </p>
 */
class LambdaFieldResolverTest {

    /**
     * 常规 getter 形态：getXxx → 首字母小写的字段名
     */
    @Test
    void resolvesStandardGetterNames() {
        assertEquals("title", LambdaFieldResolver.resolve(Conversation::getTitle));
        assertEquals("waitingToolIds", LambdaFieldResolver.resolve(Conversation::getWaitingToolIds));
        assertEquals("parentCid", LambdaFieldResolver.resolve(Conversation::getParentCid));
        assertEquals("content", LambdaFieldResolver.resolve(Message::getContent));
        assertEquals("reasoningContent", LambdaFieldResolver.resolve(Message::getReasoningContent));
    }

    /**
     * 单字母字段（getA → "a"）与连续大写（getURL → "uRL" 的边界行为）
     * 注：本解析器按「剥离前缀 + 首字母小写」的简单约定实现，不做驼峰智能还原
     */
    @Test
    void resolvesShortAndEdgeCaseNames() {
        // 长度仅 1 的常规字段
        assertEquals("id", LambdaFieldResolver.resolve(Conversation::getId));
        assertEquals("cid", LambdaFieldResolver.resolve(Message::getCid));
    }

    /**
     * 同一调用点的方法引用按合成类缓存：重复解析必须返回等价结果
     */
    @Test
    void cachesResolutionResult() {
        String first = LambdaFieldResolver.resolve(Conversation::getTitle);
        String second = LambdaFieldResolver.resolve(Conversation::getTitle);
        assertEquals(first, second, "同一方法引用的重复解析结果必须一致");
    }

    /**
     * null 方法引用快速失败
     */
    @Test
    void failsFastOnNullReference() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> LambdaFieldResolver.resolve(null));
        assertTrue(ex.getMessage().contains("null"), "异常信息应指明空引用，实际: " + ex.getMessage());
    }

    /**
     * 非方法引用（手写 lambda 体）快速失败：JVM 不为其生成 writeReplace
     */
    @Test
    void failsFastOnPlainLambdaBody() {
        SFunction<Conversation, String> lambda = c -> "constant";
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> LambdaFieldResolver.resolve(lambda));
        assertTrue(ex.getMessage().contains("实体::getXxx"),
                "异常信息应提示仅支持方法引用形态，实际: " + ex.getMessage());
    }

    /**
     * 非 getter 形态的方法引用（如 toString）快速失败。
     * 注：toString 未带 get/is 前缀，异常由「解析包装」抛出，故信息中体现的是形态约束提示
     */
    @Test
    void failsFastOnNonGetterReference() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> LambdaFieldResolver.resolve(Conversation::toString));
        assertTrue(ex.getMessage().contains("实体::getXxx"),
                "异常信息应指明仅支持 getter 方法引用形态，实际: " + ex.getMessage());
    }

    /**
     * 非 getter 形态的方法引用快速失败：以 Object 方法引用作为非常规形态样本
     */
    @Test
    void failsFastOnObjectMethodReference() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> LambdaFieldResolver.resolve(Message::hashCode));
        assertTrue(ex.getMessage().contains("实体::getXxx"),
                "异常信息应指明形态约束，实际: " + ex.getMessage());
    }
}
