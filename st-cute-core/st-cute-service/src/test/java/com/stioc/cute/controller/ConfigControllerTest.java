package com.stioc.cute.controller;

import com.stioc.cute.provider.ProviderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ConfigController} 切片测试。
 * <p>
 * 重点：list 接口只回传 passwordSet 布尔标记（绝不泄漏 password 字段）、
 * save 接口全字段缺省回退表、password 接口的清除优先级语义。
 * </p>
 */
@WebMvcTest(controllers = ConfigController.class)
@Import(ConfigController.class)
class ConfigControllerTest extends AbstractControllerSliceTest {

    @MockitoBean
    private ProviderService providerService;

    @BeforeEach
    void pinMode() {
        ensurePasswordlessMode();
    }

    @Test
    @DisplayName("list：回传基础配置且 passwordSet 为布尔标记，响应不含 password 明文字段")
    void listConfig() throws Exception {
        // 显式钉住断言依据的字段值：@PostConstruct 可能合并测试隔离目录遗留的 config.json，
        // 不依赖"默认值恰未被翻转"的环境假设
        contractProperty.setLanguage("zh-CN");

        mockMvc.perform(localGet("/api/config/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.language").value("zh-CN"))
                .andExpect(jsonPath("$.data.passwordSet").value(false))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    @DisplayName("list：密码模式下无登录态访问被 401 拦截（passwordSet 语义仅在登录后可见）")
    void listConfigWithPasswordSet() throws Exception {
        enablePasswordMode();

        mockMvc.perform(localGet("/api/config/list"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("save：全字段缺省时按默认值回退落盘")
    void saveConfigWithDefaults() throws Exception {
        mockMvc.perform(localPost("/api/config/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<String> language = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> newline = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> httpLog = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Integer> httpLogDays = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> sandbox = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> minimal = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> loadAll = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Integer> maxHistory = ArgumentCaptor.forClass(Integer.class);
        verify(providerService).saveSettings(language.capture(), newline.capture(), httpLog.capture(),
                httpLogDays.capture(), sandbox.capture(), minimal.capture(), loadAll.capture(), maxHistory.capture());

        assertEquals("zh-CN", language.getValue());
        assertEquals("enter", newline.getValue());
        assertFalse(httpLog.getValue());
        assertEquals(7, httpLogDays.getValue());
        assertTrue(sandbox.getValue());
        assertFalse(minimal.getValue());
        assertTrue(loadAll.getValue());
        assertNull(maxHistory.getValue());
    }

    @Test
    @DisplayName("save：显式字段全量透传")
    void saveConfigWithExplicitValues() throws Exception {
        mockMvc.perform(localPost("/api/config/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"en-US\",\"newlineKey\":\"ctrl+enter\",\"httpLog\":true," +
                                "\"httpLogDays\":30,\"pathSandboxEnabled\":false,\"minimalSkillMode\":true," +
                                "\"loadAllUserAttachments\":false,\"maxViewHistoryLimit\":500}"))
                .andExpect(status().isOk());

        verify(providerService).saveSettings("en-US", "ctrl+enter", true, 30,
                false, true, false, 500);
    }

    @Test
    @DisplayName("password：传输摘要与原文长度透传（不带清除标记）")
    void savePasswordDigest() throws Exception {
        mockMvc.perform(localPost("/api/config/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"digest-abc\",\"passwordLength\":12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(providerService).savePassword("digest-abc", 12, false);
    }

    @Test
    @DisplayName("password：passwordClear=true 优先于 password 字段（清除语义）")
    void savePasswordClearTakesPrecedence() throws Exception {
        mockMvc.perform(localPost("/api/config/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"digest-abc\",\"passwordClear\":true}"))
                .andExpect(status().isOk());

        verify(providerService).savePassword("digest-abc", null, true);
    }
}
