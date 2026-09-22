package com.stioc.cute;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 智能体服务后端 Spring Boot 主启动引导类
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.stioc.cute.repository")
public class StCuteApplication {

    public static void main(String[] args) {
        // 桌面壳托管模式（ST_CUTE_DESKTOP_MANAGED=1）时追加 desktop profile：
        // logback-spring.xml 据此关闭控制台 appender，仅写文件（壳无控制台句柄，
        // 控制台输出只会被壳重定向进 desktop 早期报错文件，与 service 日志重复）
        if ("1".equals(System.getenv("ST_CUTE_DESKTOP_MANAGED"))) {
            System.setProperty("spring.profiles.active", "desktop");
        }
        SpringApplication.run(StCuteApplication.class, args);
    }

}
