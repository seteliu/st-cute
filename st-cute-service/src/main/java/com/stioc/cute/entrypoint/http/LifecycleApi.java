package com.stioc.cute.entrypoint.http;

import com.stioc.cute.platform.common.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 应用生命周期管理控制器（供桌面端壳及探测工具调用）
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class LifecycleApi {

    @Resource
    private Environment environment;

    @Resource
    private ConfigurableApplicationContext applicationContext;

    /**
     * 服务健康探测接口（免鉴权）
     * 用于桌面端壳启动时判断端口就绪状态及应用归属
     *
     * @return 包含应用标识与版本号的探测结果
     */
    @GetMapping("/ping")
    public Result<Map<String, String>> ping() {
        String version = environment.getProperty("st-cute.version", "unknown");
        return Result.success(Map.of(
                "app", "st-cute",
                "version", version
        ));
    }

    /**
     * 优雅关闭服务接口（基于 Token 双重鉴权）
     * 仅当请求头 X-Shutdown-Token 与环境变量 ST_CUTE_SHUTDOWN_TOKEN 一致时允许触发
     *
     * @param token 请求头传入的停机凭证
     * @return 操作响应
     */
    @PostMapping("/shutdown")
    public Result<String> shutdown(@RequestHeader(value = "X-Shutdown-Token", required = false) String token) {
        String expectedToken = System.getenv("ST_CUTE_SHUTDOWN_TOKEN");

        if (!StringUtils.hasText(expectedToken) || !expectedToken.equals(token)) {
            log.warn("[Lifecycle] 收到未授权的停机请求，Header Token: {}", token);
            return Result.error(403, "Forbidden: 无效的停机凭证");
        }

        log.info("[Lifecycle] 收到受信任的停机指令，准备执行优雅停机...");

        // 异步延迟执行，确保能够先向客户端返回 200 OK 响应，避免客户端发生网络重置异常
        CompletableFuture.runAsync(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            log.info("[Lifecycle] 开始关闭 Spring ApplicationContext 并退出进程...");
            try {
                applicationContext.close();
            } catch (Exception e) {
                log.error("[Lifecycle] 关闭 Spring 上下文时发生异常", e);
            } finally {
                System.exit(0);
            }
        });

        return Result.success("st-cute 服务正在退出...");
    }

}
