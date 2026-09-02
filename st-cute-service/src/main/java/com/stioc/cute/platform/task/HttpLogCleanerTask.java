package com.stioc.cute.platform.task;

import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.platform.contract.ContractProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
public class HttpLogCleanerTask {

    @Resource
    private ContractProperty contractProperty;

    /**
     * 系统启动时使用虚拟线程异步执行一次清理，避免过期日志堆积
     */
    @PostConstruct
    public void initClean() {
        Thread.startVirtualThread(this::cleanExpiredLogs);
    }

    /**
     * 每 15 分钟执行一次，扫描并清理过期的 http 交互日志
     */
    @Scheduled(cron = "0 */15 * * * ?")
    public void cleanExpiredLogs() {
        if (contractProperty == null || contractProperty.getLlmLog() == null) {
            return;
        }
        int keepDays = contractProperty.getLlmLog().getHttpLogDays();
        if (keepDays <= 0) {
            log.info("HTTP 日志清理：httpLogDays 设置为 {}，跳过自动清理", keepDays);
            return;
        }

        log.info("开始扫描并清理 {} 天前的过期 HTTP 交互日志...", keepDays);
        File logDir = new File(ContractFile.getGlobalDir(), "logs");
        if (!logDir.exists() || !logDir.isDirectory()) {
            return;
        }

        File[] files = logDir.listFiles((dir, name) -> name.startsWith("http_") && name.endsWith(".log"));
        if (files == null || files.length == 0) {
            return;
        }

        LocalDate thresholdDate = LocalDate.now().minusDays(keepDays);
        int deletedCount = 0;

        for (File file : files) {
            String name = file.getName();
            // 文件名格式为 "http_yyyy-MM-dd.log"，"http_" 前缀长为 5，日期部分长为 10
            if (name.length() < 15) {
                continue;
            }
            String datePart = name.substring(5, 15);
            try {
                LocalDate fileDate = LocalDate.parse(datePart, DateTimeFormatter.ISO_LOCAL_DATE);
                if (fileDate.isBefore(thresholdDate)) {
                    if (file.delete()) {
                        deletedCount++;
                        log.info("已成功清理过期日志文件: {}", file.getName());
                    } else {
                        log.warn("无法删除日志文件: {}", file.getName());
                    }
                }
            } catch (Exception e) {
                log.warn("解析日志文件日期失败: {}, 忽略该文件", name);
            }
        }
        log.info("HTTP 日志清理完成，共删除 {} 个过期文件。", deletedCount);
    }
}
