package com.stioc.cute.engine.testkit;

import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.store.types.SortDirection;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * 闭环测试的等待与断言辅助工具。
 * <p>
 * ReAct 循环经异步执行器拉起，用例必须等待其真实收尾而非 sleep 固定时长。此处提供两类等待：
 * <ul>
 *   <li>{@link #awaitCondition}：通用的条件轮询等待；</li>
 *   <li>{@link #awaitLoopIdle}：等待循环「稳定空闲」——loopRunning=0 且等待工具/子会话屏障全空，
 *       并要求条件<b>连续多次成立</b>。</li>
 * </ul>
 * </p>
 * <p>
 * 关于「连续成立」：循环收尾断言必须能跨过两个过渡窗口——① 提交后 loopRunning 置 1 尚在途，
 * 此刻库中仍是 0；② 无工具调用的收尾路径上，waitingToolIds 清空与 loopRunning 归零是两次独立事件。
 * 单次命中即返回会导致用例在循环真正跑起来之前就宣告完成（假绿）。
 * </p>
 */
public final class EngineTestHarness {

    /**
     * 默认等待上限：LLM 走本机 HTTP，闭环正常在毫秒级完成，20 秒已属极宽松
     */
    private static final long DEFAULT_TIMEOUT_MS = 20_000L;

    /**
     * 空闲判定的连续成立次数
     */
    private static final int STABLE_HITS = 3;

    /**
     * 空闲判定的两次采样间隔
     */
    private static final long POLL_INTERVAL_MS = 120L;

    private EngineTestHarness() {
    }

    /**
     * 轮询等待条件成立
     */
    public static void awaitCondition(Callable<Boolean> condition, String description) {
        awaitCondition(condition, description, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 轮询等待条件成立（自定义超时）
     */
    public static void awaitCondition(Callable<Boolean> condition, String description, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(condition.call())) {
                    return;
                }
            } catch (Exception e) {
                throw new IllegalStateException("等待条件求值异常: " + description, e);
            }
            sleepQuietly(POLL_INTERVAL_MS);
        }
        throw new AssertionError("等待超时（" + timeoutMs + "ms）：" + description);
    }

    /**
     * 等待循环彻底收尾：loopRunning=0、等待工具屏障空、等待子会话屏障空，且连续多次成立。
     * <p>
     * 本重载不做「循环确实跑过」的校验，仅适用于已确认循环在跑的场景（如取消后收尾）。
     * 提交消息后等待收尾请用 {@link #awaitLoopIdle(AgentEngine, long, int)} 并传入消息基线。
     * </p>
     */
    public static void awaitLoopIdle(AgentEngine engine, long cid) {
        awaitLoopIdle(engine, cid, -1);
    }

    /**
     * 等待循环彻底收尾，并额外要求会话消息数已超过基线（确认循环真的跑过）。
     * <p>
     * baseline 校验是必要的：仅凭「空闲」不足以判定收尾——从提交消息到循环真正开跑之间存在窗口，
     * 此刻库中 loopRunning 仍是 0、屏障也是空的，条件天然成立。要求消息数增长可跨过该窗口，
     * 避免循环尚未启动用例就宣告完成（假绿）。
     * </p>
     *
     * @param baselineMessageCount 提交前的消息条数；传负数表示不做该校验
     */
    public static void awaitLoopIdle(AgentEngine engine, long cid, int baselineMessageCount) {
        awaitStable(() -> isLoopIdle(engine, cid)
                        && (baselineMessageCount < 0
                        || allMessages(engine, cid).size() > baselineMessageCount),
                "循环彻底收尾（loopRunning=0 且等待屏障全空）", DEFAULT_TIMEOUT_MS, STABLE_HITS);
    }

    /**
     * 等待循环进入运行态（loopRunning=1），供「运行中取消」类用例建立确定的中间态
     */
    public static void awaitLoopRunning(AgentEngine engine, long cid) {
        awaitCondition(() -> {
            Conversation conv = engine.getConversationStore().getById(cid);
            return conv != null && conv.getLoopRunning() != null && conv.getLoopRunning() == 1;
        }, "循环进入运行态（loopRunning=1）");
    }

    /**
     * 循环是否处于空闲态
     */
    public static boolean isLoopIdle(AgentEngine engine, long cid) {
        Conversation conv = engine.getConversationStore().getById(cid);
        if (conv == null) {
            return false;
        }
        boolean running = conv.getLoopRunning() != null && conv.getLoopRunning() == 1;
        return !running
                && StringUtils.isBlank(conv.getWaitingToolIds())
                && StringUtils.isBlank(conv.getWaitingSubCids());
    }

    /**
     * 会话内指定角色的最后一条消息（无则返回 null）
     */
    public static Message lastMessageOfRole(AgentEngine engine, long cid, MessageRole role) {
        List<Message> list = engine.getMessageStore().listByQuery(MessageQuery.builder()
                .cid(cid)
                .role(role)
                .sortField("id")
                .sortDirection(SortDirection.DESC)
                .build());
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 会话内指定角色的全部消息（按 id 升序）
     */
    public static List<Message> messagesOfRole(AgentEngine engine, long cid, MessageRole role) {
        return engine.getMessageStore().listByQuery(MessageQuery.builder()
                .cid(cid)
                .role(role)
                .sortField("id")
                .sortDirection(SortDirection.ASC)
                .build());
    }

    /**
     * 会话内全部消息（按 id 升序）
     */
    public static List<Message> allMessages(AgentEngine engine, long cid) {
        return engine.getMessageStore().listByQuery(MessageQuery.builder()
                .cid(cid)
                .sortField("id")
                .sortDirection(SortDirection.ASC)
                .build());
    }

    /**
     * 按 toolCallId 查工具消息
     */
    public static Message toolMessage(AgentEngine engine, long cid, String toolCallId) {
        return engine.getMessageStore().getByQuery(MessageQuery.builder()
                .cid(cid)
                .callId(toolCallId)
                .build());
    }

    /**
     * 等待某条工具消息进入指定终态
     */
    public static void awaitToolStatus(AgentEngine engine, long cid, String toolCallId, MessageStatus expected) {
        awaitCondition(() -> {
            Message msg = toolMessage(engine, cid, toolCallId);
            return msg != null && msg.getStatus() == expected;
        }, "工具消息 " + toolCallId + " 进入状态 " + expected);
    }

    /**
     * 等待条件成立，并要求其<b>连续</b>成立若干次后才返回。
     * <p>
     * 循环收尾类断言必须用本方法：循环刚提交时 loopRunning 尚为 0（置 1 还在途），
     * 单次判定会立刻误判为「已收尾」。连续成立可可靠跨过启动窗口与事件链在途回填的间隙。
     * </p>
     *
     * @param timeoutMs     总超时
     * @param stableHits    需要连续成立的采样次数
     */
    public static void awaitStable(Callable<Boolean> condition, String description,
                                   long timeoutMs, int stableHits) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int hits = 0;
        while (System.currentTimeMillis() < deadline) {
            boolean hit;
            try {
                hit = Boolean.TRUE.equals(condition.call());
            } catch (Exception e) {
                throw new IllegalStateException("等待条件求值异常: " + description, e);
            }
            if (hit) {
                if (++hits >= stableHits) {
                    return;
                }
            } else {
                hits = 0;
            }
            sleepQuietly(POLL_INTERVAL_MS);
        }
        throw new AssertionError("等待稳定成立超时（" + timeoutMs + "ms）：" + description);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }
}
