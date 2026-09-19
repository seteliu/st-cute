package com.stioc.cute.platform.config;

import com.stioc.cute.platform.common.VirtualThreads;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;

/**
 * 全局虚拟线程基础设施配置。
 * <p>
 * 基于 Java 虚拟线程特性（JEP 444），提供非池化、任务即新建（Per-Task）的受管执行器，
 * 支持统一的命名规则、未捕获异常日志兜底以及 Spring 容器生命周期的有界安全停机。
 * </p>
 */
@Slf4j
@Configuration
public class ThreadConfig {

    public static final String VIRTUAL_EXECUTOR_BEAN = "virtualThreadExecutor";

    /**
     * 优雅停机最大等待时长（3秒），杜绝 JDK 默认 close() 无限期挂起关停流程
     */
    private static final long SHUTDOWN_TIMEOUT_MS = 3_000L;

    /**
     * 注册全局通用的虚拟线程执行器 Bean。
     * <p>
     * 遵循虚拟线程核心设计理念：不复用线程（Per-Task）、无固定容量上限，任务执行完毕立即消亡；
     * destroyMethod 显式置空，由 PreDestroy 执行有界安全停机等待，彻底规避 JDK 默认 close() 的死等风险。
     * </p>
     */
    @Bean(name = VIRTUAL_EXECUTOR_BEAN, destroyMethod = "")
    public ExecutorService virtualThreadExecutor() {
        return VirtualThreads.getGlobalExecutor();
    }

    @PreDestroy
    public void onShutdown() {
        log.info("[ThreadConfig] 触发全局虚拟线程有界安全停机...");
        VirtualThreads.shutdownGracefully(SHUTDOWN_TIMEOUT_MS);
    }
}
