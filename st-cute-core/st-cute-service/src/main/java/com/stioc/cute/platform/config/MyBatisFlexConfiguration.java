package com.stioc.cute.platform.config;

import com.mybatisflex.core.mybatis.FlexConfiguration;
import com.mybatisflex.spring.boot.ConfigurationCustomizer;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Flex 统一拓展配置，注册全局 SQLite LocalDateTime 类型处理器
 */
@Configuration
public class MyBatisFlexConfiguration implements ConfigurationCustomizer {

    @Override
    public void customize(FlexConfiguration configuration) {
        configuration.getTypeHandlerRegistry().register(LocalDateTime.class, SqliteLocalDateTimeTypeHandler.class);
    }
}
