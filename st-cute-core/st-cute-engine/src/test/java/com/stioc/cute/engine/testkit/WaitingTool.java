package com.stioc.cute.engine.testkit;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可阻塞的假工具：让用例精确控制「工具仍在外执行」的时间窗口。
 * <p>
 * 并发批、等待屏障、用户取消三条链路都必须在一个「工具尚未返回」的确定态上做断言，
 * 而真实工具耗时不可控。本工具进入 execute 后先置「已进入」信号，再阻塞等待显式放行，
 * 用例据此获得一个可稳定观测的中间态。
 * </p>
 */
public class WaitingTool implements CuteTool {

    private final String rawName;

    private final ToolAccessLevel accessLevel;

    /**
     * 工具结果
     */
    private final String executeResult;

    /**
     * 已进入 execute 的信号
     */
    private final CountDownLatch entered = new CountDownLatch(1);

    /**
     * 放行信号（用例调用 {@link #release()} 后工具才返回）
     */
    private final CountDownLatch released = new CountDownLatch(1);

    /**
     * 进入 execute 的次数
     */
    private final AtomicInteger executeCount = new AtomicInteger();

    /**
     * 是否至少进入过一次、当前是否仍在执行中
     */
    private volatile boolean executing = false;

    public WaitingTool(String rawName, ToolAccessLevel accessLevel, String executeResult) {
        this.rawName = rawName;
        this.accessLevel = accessLevel;
        this.executeResult = executeResult;
    }

    @Override
    public String getRawName() {
        return rawName;
    }

    @Override
    public String getDescription() {
        return "可阻塞假工具（测试用）";
    }

    @Override
    public String getArgumentSchema() {
        return """
                {"type":"object","properties":{"text":{"type":"string"}}}
                """;
    }

    @Override
    public ToolAccessLevel getAccessLevel() {
        return accessLevel;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        executeCount.incrementAndGet();
        executing = true;
        entered.countDown();
        try {
            // 阻塞等待用例放行；超时兜底防止用例失败时把线程永久挂住
            released.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executing = false;
        }
        return executeResult;
    }

    /**
     * 等待工具真正进入 execute（返回 false 表示超时未进入）
     */
    public boolean awaitEntered(long timeoutMs) {
        try {
            return entered.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 放行工具返回
     */
    public void release() {
        released.countDown();
    }

    /**
     * 工具结果
     */
    public String getExecuteResult() {
        return executeResult;
    }

    /**
     * 进入 execute 的次数
     */
    public int getExecuteCount() {
        return executeCount.get();
    }

    /**
     * 当前是否仍在 execute 中
     */
    public boolean isExecuting() {
        return executing;
    }
}
