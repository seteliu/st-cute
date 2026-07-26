package com.stioc.cute.llm;

import com.stioc.cute.platform.contract.ContractProperty;
import lombok.extern.slf4j.Slf4j;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 带有高容错透明重试的大模型客户端装饰器类
 */
@Slf4j
public class CuteChatRetryWrapper implements CuteChat {

    private final CuteChat delegate;
    private final ContractProperty contractProperty;

    public CuteChatRetryWrapper(CuteChat delegate, ContractProperty contractProperty) {
        this.delegate = delegate;
        this.contractProperty = contractProperty;
    }

    @Override
    public CuteChatResponse call(CutePrompt prompt) {
        int retryCount = contractProperty != null ? contractProperty.getRetryCount() : 3;
        int retryIntervalSec = contractProperty != null ? contractProperty.getRetryIntervalSec() : 5;

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

    @Override
    public void streamConsume(CutePrompt prompt, Consumer<Stream<CuteChatResponse>> consumer) {
        int retryCount = contractProperty != null ? contractProperty.getRetryCount() : 3;
        int retryIntervalSec = contractProperty != null ? contractProperty.getRetryIntervalSec() : 5;

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
