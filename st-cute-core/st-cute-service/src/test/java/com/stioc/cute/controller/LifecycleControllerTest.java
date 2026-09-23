package com.stioc.cute.controller;

import com.stioc.cute.platform.security.DesktopTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link LifecycleController} 切片测试。
 * <p>
 * <b>范围声明</b>：shutdown 的 happy path 含 {@code System.exit(0)}（JUnit 5 无法拦截，
 * SecurityManager 方案已随 JDK 废弃），一旦触发会直接杀掉 surefire JVM——因此本测试
 * <b>仅覆盖凭证复验失败的 403 拒绝分支</b>，并验证凭证未被销毁、上下文未被触碰。
 * happy path 的覆盖需先将退出动作重构为可注入单元，另行讨论。
 * </p>
 */
@WebMvcTest(controllers = LifecycleController.class)
@Import(LifecycleController.class)
class LifecycleControllerTest extends AbstractControllerSliceTest {

    @MockitoBean
    private Environment environment;
    @MockitoBean
    private ConfigurableApplicationContext applicationContext;
    @MockitoBean
    private DesktopTokenStore desktopTokenStore;

    @BeforeEach
    void pinMode() {
        ensurePasswordlessMode();
    }

    @Test
    @DisplayName("ping：开放探测（无凭证）回传 desktopAuth=open 与版本号")
    void pingOpenMode() throws Exception {
        when(environment.getProperty("st-cute.version", "unknown")).thenReturn("0.2.5");

        mockMvc.perform(localGet("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.app").value("st-cute"))
                .andExpect(jsonPath("$.data.version").value("0.2.5"))
                .andExpect(jsonPath("$.data.desktopAuth").value("open"));
    }

    @Test
    @DisplayName("shutdown：无凭证时 403 拒绝（方法内复验失败），凭证不销毁、上下文不动")
    void shutdownWithoutTokenRejected() throws Exception {
        when(desktopTokenStore.matches(null)).thenReturn(false);

        mockMvc.perform(localPost("/api/shutdown"))
                .andExpect(status().isForbidden());

        verify(desktopTokenStore, never()).destroy();
        verify(applicationContext, never()).close();
    }

    @Test
    @DisplayName("shutdown：凭证不匹配时同样 403 拒绝")
    void shutdownWithBadTokenRejected() throws Exception {
        when(desktopTokenStore.matches("bad-token")).thenReturn(false);

        mockMvc.perform(localPost("/api/shutdown")
                        .header(DesktopTokenStore.TOKEN_HEADER, "bad-token"))
                .andExpect(status().isForbidden());

        verify(desktopTokenStore, never()).destroy();
    }
}
