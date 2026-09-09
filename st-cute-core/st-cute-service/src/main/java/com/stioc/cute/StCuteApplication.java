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
        SpringApplication.run(StCuteApplication.class, args);
    }

}
