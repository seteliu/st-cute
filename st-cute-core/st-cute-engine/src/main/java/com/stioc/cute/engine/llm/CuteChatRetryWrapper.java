package com.stioc.cute.engine.llm;

import com.stioc.cute.engine.llm.types.CuteChatResponse;
import com.stioc.cute.engine.llm.types.CutePrompt;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 带有高容错透明重试的大模型客户端装饰器类
 */
@Slf4j
public class CuteChatRetryWrapper implements CuteChat {

    private final CuteChat delegate;
    private final RetryPolicyProvider retryPolicyProvider;

    public CuteChatRetryWrapper(CuteChat delegate, RetryPolicyProvider retryPolicyProvider) {
        this.delegate = delegate;
        this.retryPolicyProvider = retryPolicyProvider;
    }

    @Override
    public CuteChatResponse call(CutePrompt prompt) {
        int retryCount = retryPolicyProvider != null ? retryPolicyProvider.getRetryCount() : 3;
        int retryIntervalSec = retryPolicyProvider != null ? retryPolicyProvider.getRetryIntervalSec() : 5;

        Exception lastException = null;
        for (int i = 0; i <= retryCount; i++) {
            try {
                return delegate.call(prompt);
            } catch (Exception e) {
                lastException = e;
                if (i < retryCount) {
                    log.warn("大模型非流式调用失败，进行第 {}/{} 次重试，等待 {} 秒。失败原因: {}",
                            i + 1, retryCount, retryIntervalSec, e.getMessage());
                    try {
                        Thread.sleep(retryIntervalSec * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试等待被中断", ie);
                    }
                }
            }
        }
        throw new RuntimeException("大模型调用失败，重试 " + retryCount + " 次仍未成功。最后失败原因: " + (lastException != null ? lastException.getMessage() : "未知"), lastException);
    }

    /**
     * 流式调用透明重试。
     * <p>
     * 【重试语义：consumer 会被多次调用，消费方必须按「每次尝试重置」处理】
     * 本方法在失败重试时复用同一个 consumer，每次尝试都会从头发起并重新
     * {@code consumer.accept(stream)}，因此：
     * <ul>
     *   <li>已推向前端的流式通知（思考流/正文流）会重复到达，重试 N 次即最多重复 N 次。
     *       该重复被判定为可接受——chunk 的副作用仅为中间过程展示，属实时通知；</li>
     *   <li>但<b>累积语义的消费方必须自行在每次 accept 时重置累积状态</b>，
     *       否则失败前已产出的片段会被重试叠加第二遍，使最终内容（返回值/落库）出现重复。
     *       生产实现的处理见 {@code AgentLoopProcessor#consumeChatResponseStream}，
     *       其 consumer 体首行即重置思考/正文/工具调用三个累加器。</li>
     * </ul>
     * 装饰器不引入产出闸门、不做缓冲区回滚：零产出失败与半途失败都保留完整重试能力，
     * 正确性由「每次尝试一份独立累积状态」这一约定保证。
     * </p>
     * <p>
     * 注意：若将来新增其他累积型消费方，必须遵循同一约定；若改为「严格增量追加、不做终态覆盖」
     * 的渲染方式，或出现需要精确去重的消费方，此处需重新评估（届时可在「零产出才允许重试」
     * 的方向上加闸门，但会一并牺牲半途失败场景的重试能力，需权衡）。
     * </p>
     */
    @Override
    public void streamConsume(CutePrompt prompt, Consumer<Stream<CuteChatResponse>> consumer) {
        int retryCount = retryPolicyProvider != null ? retryPolicyProvider.getRetryCount() : 3;
        int retryIntervalSec = retryPolicyProvider != null ? retryPolicyProvider.getRetryIntervalSec() : 5;

        Exception lastException = null;
        for (int i = 0; i <= retryCount; i++) {
            try {
                delegate.streamConsume(prompt, consumer);
                return;
            } catch (Exception e) {
                lastException = e;
                if (i < retryCount) {
                    log.warn("大模型流式调用失败，进行第 {}/{} 次重试，等待 {} 秒。失败原因: {}",
                            i + 1, retryCount, retryIntervalSec, e.getMessage());
                    try {
                        Thread.sleep(retryIntervalSec * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试等待被中断", ie);
                    }
                }
            }
        }
        throw new RuntimeException("大模型流式调用失败，重试 " + retryCount + " 次仍未成功。最后失败原因: " + (lastException != null ? lastException.getMessage() : "未知"), lastException);
    }
}
