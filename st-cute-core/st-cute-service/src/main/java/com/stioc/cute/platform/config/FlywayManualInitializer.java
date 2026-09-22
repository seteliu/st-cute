package com.stioc.cute.platform.config;

import com.stioc.cute.platform.contract.ContractFile;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import java.io.File;

/**
 * 激活并驱动 Flyway 数据库版本迁移
 * 1. 在容器启动早期（所有 Bean 实例化前）确保全局配置及数据目录 ~/.st-cute 存在
 * 2. 绕过 Spring Boot 在特定环境下对 SQLite 自动装配失效或冲突的问题，确保建表一定在业务启动前执行
 */
@Configuration
@Slf4j
public class FlywayManualInitializer {

    @Resource
    private DataSource dataSource;

    /**
     * 在所有 Bean（包括数据源）实例化前，确保全局 ~/.st-cute 目录已被创建
     */
    @Bean
    public static BeanFactoryPostProcessor globalDirInitializer() {
        return beanFactory -> {
            File globalDir = ContractFile.getGlobalDir();
            if (!globalDir.exists()) {
                if (globalDir.mkdirs()) {
                    System.out.println(">>>> [System] 自动创建全局配置与数据目录: " + globalDir.getAbsolutePath());
                }
            }
        };
    }

    @PostConstruct
    public void init() {
        log.info(">>>> [Flyway] 开始执行数据库版本迁移...");
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("db/migration")
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .cleanDisabled(true)
                    .load();
            flyway.migrate();
            log.info(">>>> [Flyway] 执行数据库版本迁移成功！");
        } catch (Exception e) {
            // fail-fast：迁移失败意味着库表结构与代码预期不一致，带着旧结构继续启动会在运行期
            // 以更难定位的方式炸开（缺列、缺表引发的 SQL 异常）。此处直接终止启动，让问题在启动期暴露。
            // 原始异常作为 cause 保留，便于排查具体是哪个迁移脚本或连接问题
            log.error(">>>> [Flyway] 执行数据库版本迁移失败，启动中止", e);
            throw new IllegalStateException("数据库版本迁移失败，应用无法安全启动: " + e.getMessage(), e);
        }
    }
}
