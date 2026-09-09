package com.stioc.cute.job;

import com.stioc.cute.engine.AgentEngine;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 僵死消息时效清理定时调度任务。
 * <p>
 * 薄触发器：定时调度拉起与应用启动事件监听，核心自愈状态机与会话恢复逻辑
 * 已全面收归引擎 LoopFacade.cleanStale，本类零业务逻辑、零锁暴露。
 * </p>
 */
@Slf4j
@Component
public class StaleMessageCleanupJob {

    /**
     * 运行时扫描的超时门限（单位：分钟），超过此时长仍未完结则视为僵死。
     */
    private static final int STALE_THRESHOLD_MINUTES = 60;

    @Resource
    private AgentEngine agentEngine;

    /**
     * 应用启动后立即执行一次全量扫描自愈。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    @Transactional
    public void cleanOnStartup() {
        int fixed = agentEngine.getLoopFacade().cleanStale(null);
        if (fixed > 0) {
            log.warn("[StaleCleanup] 启动扫描：检测到 {} 条残留消息并已完成自愈修复", fixed);
        } else {
            log.info("[StaleCleanup] 启动扫描：未发现残留消息");
        }
    }

    /**
     * 每 60 秒执行一次，清理超过指定时长的僵死消息。
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void cleanOnSchedule() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STALE_THRESHOLD_MINUTES);
        int fixed = agentEngine.getLoopFacade().cleanStale(threshold);
        if (fixed > 0) {
            log.warn("[StaleCleanup] 定时扫描：清理了 {} 条超过 {} 分钟的僵死消息", fixed, STALE_THRESHOLD_MINUTES);
        }
    }
}

