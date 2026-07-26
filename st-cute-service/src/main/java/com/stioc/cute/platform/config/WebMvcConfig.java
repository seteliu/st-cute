package com.stioc.cute.platform.config;

import org.apache.commons.lang3.time.DateUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Date;

/**
 * @author 61jun.com
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new StringToDateConverter());
    }

    static class StringToDateConverter implements Converter<String, Date> {
        @Override
        public Date convert(String source) {
            if (source == null || source.isBlank()) {
                return null;
            }
            try {
                if (source.matches("^\\d+$")) {
                    return new Date(Long.parseLong(source));
                }
                String[] patterns = {
                    "yyyy-MM-dd HH:mm:ss",
                    "yyyy-MM-dd",
                    "yyyy/MM/dd HH:mm:ss",
                    "yyyy/MM/dd",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
                };
                return DateUtils.parseDate(source, patterns);
            } catch (Exception e) {
                throw new IllegalArgumentException("日期格式错误: " + source, e);
            }
        }
    }

}
