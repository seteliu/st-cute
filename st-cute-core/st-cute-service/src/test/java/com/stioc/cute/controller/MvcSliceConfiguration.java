package com.stioc.cute.controller;

import com.stioc.cute.platform.config.GlobalExceptionHandler;
import com.stioc.cute.platform.config.WebMvcConfig;
import com.stioc.cute.platform.security.DesktopSecurityFilter;
import com.stioc.cute.platform.security.WebSecurityFilter;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Import;

/**
 * controller 切片测试的引导根（位于 controller 包，向上搜索第一站即命中）。
 * <p>
 * 屏蔽主启动类 {@code StCuteApplication} 上的 {@code @MapperScan}（切片无 MyBatis 环境，
 * MapperFactoryBean 缺 SqlSessionFactory 会直接启动失败）与 {@code @EnableScheduling}
 * （避免拉起定时任务基建）。
 * </p>
 * <p>
 * 本类<b>不做组件扫描</b>：Boot 4 切片的 controller 注册与排除时序在不同扫描形态下
 * 表现不一致（显式 includeFilters 会绕过切片排除、默认过滤器下非选中 controller
 * 仍可能涌入）。故采用完全显式的注册模型——被测 controller 由各测试类自行
 * {@code @Import} 显式注册，公共 MVC 组件（全局异常映射、日期 converter、
 * 两条安全过滤器链与属性装配）经本类统一收编。
 * </p>
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@Import({GlobalExceptionHandler.class, WebMvcConfig.class,
        WebSecurityFilter.class, DesktopSecurityFilter.class, SlicePropertyConfig.class})
public class MvcSliceConfiguration {
}
