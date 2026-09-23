package com.stioc.cute.controller;

import com.stioc.cute.platform.common.UserInfo;
import com.stioc.cute.platform.security.LoginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AuthController} 切片测试（含过滤器→controller 穿层契约）。
 * <p>
 * 覆盖：challenge 有/无 nonce、login 成功的会话建立与 onLoginSuccess 回调、login 拒绝的
 * code/msg 透传、logout 幂等、免密模式 ThreadLocal 注入 admin 后 /info 直接可用。
 * </p>
 */
@WebMvcTest(controllers = AuthController.class)
@Import(AuthController.class)
class AuthControllerTest extends AbstractControllerSliceTest {

    @MockitoBean
    private LoginService loginService;

    @BeforeEach
    void pinMode() {
        ensurePasswordlessMode();
    }

    @Test
    @DisplayName("challenge：已配置访问码时回传 nonce 与盐")
    void challengeWithNonce() throws Exception {
        when(loginService.issueChallenge()).thenReturn(new LoginService.ChallengeResult("nonce-x", "salt-y"));

        mockMvc.perform(localGet("/api/auth/challenge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nonce").value("nonce-x"))
                .andExpect(jsonPath("$.data.salt").value("salt-y"));
    }

    @Test
    @DisplayName("challenge：未配置访问码时 data 为 null（前端据此跳过登录）")
    void challengeWithoutNonce() throws Exception {
        when(loginService.issueChallenge()).thenReturn(new LoginService.ChallengeResult(null, null));

        mockMvc.perform(localGet("/api/auth/challenge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("login：校验通过建立会话并清除失败计数")
    void loginSuccessEstablishesSession() throws Exception {
        when(loginService.login(eq("127.0.0.1"), eq("digest"), eq("nonce-x"), eq("proof-y")))
                .thenReturn(LoginService.LoginVerdict.allow("127.0.0.1"));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(localPost("/api/auth/login")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"digest\",\"nonce\":\"nonce-x\",\"proof\":\"proof-y\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("admin"));

        // 会话属性写入 UserInfo
        Object user = session.getAttribute("user");
        assertNotNull(user);
        assertEquals("admin", ((UserInfo) user).getUsername());
        verify(loginService).onLoginSuccess("127.0.0.1");
    }

    @Test
    @DisplayName("login：封禁拒绝时 code/msg 透传且不建会话")
    void loginDeniedPropagatesCode() throws Exception {
        when(loginService.login(eq("127.0.0.1"), eq("bad"), eq(null), eq(null)))
                .thenReturn(LoginService.LoginVerdict.deny(429, "尝试次数过多，请稍后再试", "127.0.0.1"));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(localPost("/api/auth/login")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"bad\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.msg").value("尝试次数过多，请稍后再试"));

        assertNull(session.getAttribute("user"));
        verify(loginService, never()).onLoginSuccess(any());
    }

    @Test
    @DisplayName("logout：无会话时幂等成功")
    void logoutWithoutSession() throws Exception {
        mockMvc.perform(localPost("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("logout：有会话时销毁会话")
    void logoutInvalidatesSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(localPost("/api/auth/logout").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // MockMvc 请求处理完成后会话已被 controller 显式 invalidate
        assertTrue(session.isInvalid());
    }

    @Test
    @DisplayName("info：免密模式本机请求经过滤器注入默认管理员，/info 直接返回")
    void userInfoViaFilterThreadLocalInjection() throws Exception {
        // 免密 + 本机来源：WebSecurityFilter 在链路中注入 admin ThreadLocal，controller 读取成功
        mockMvc.perform(localGet("/api/auth/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("admin"));
    }

    @Test
    @DisplayName("info：密码模式无会话时 401（穿层契约：过滤器未注入用户）")
    void userInfoPasswordModeWithoutSession() throws Exception {
        enablePasswordMode();

        mockMvc.perform(localGet("/api/auth/info"))
                .andExpect(status().isUnauthorized());
    }
}
