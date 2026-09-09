package com.stioc.cute.engine.llm;

/**
 * 引擎重试策略供血接口（宿主实现）。
 * <p>
 * 大模型调用失败后的透明重试参数（次数与间隔）由宿主配置提供，
 * 引擎的重试装饰器经本接口读取，不感知配置存储。
 * </p>
 */
public interface RetryPolicyProvider {

    /**
     * 失败重试次数（<=0 表示不重试）
     */
    int getRetryCount();

    /**
     * 重试间隔（秒）
     */
    int getRetryIntervalSec();
}
