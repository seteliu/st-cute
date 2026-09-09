package com.stioc.cute.platform.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动就绪日志组件。
 *
 * <p>监听 {@link ApplicationReadyEvent} 并置于最低优先级，
 * 确保在所有其他启动监听器（如 StaleMessageCleanupService 启动扫描）之后执行，
 * 成为启动流程的最后一条日志。
 */
@Slf4j
@Component
public class StartupLogListener {

    /**
     * 应用启动就绪后打印版本号与监听端口的收尾日志。
     *
     * @param event 启动就绪事件（携带应用上下文）
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onReady(ApplicationReadyEvent event) {
        ConfigurableApplicationContext context = event.getApplicationContext();
        String version = context.getEnvironment().getProperty("st-cute.version");
        // WebServerApplicationContext 在 4.x 中已迁移到 org.springframework.boot.web.server.context 包
        int port = context instanceof WebServerApplicationContext webContext ? webContext.getWebServer().getPort() : -1;
        log.info("[StCute] st-cute-service v{} 启动完毕，端口：{}", version, port);
    }

}
