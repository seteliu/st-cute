package com.stioc.cute.controller;

import com.stioc.cute.engine.llm.types.Provider;
import com.stioc.cute.provider.ProviderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ProviderController} 切片测试。
 * <p>list/save/delete 的参数透传与 Result 封装。</p>
 */
@WebMvcTest(controllers = ProviderController.class)
@Import(ProviderController.class)
class ProviderControllerTest extends AbstractControllerSliceTest {

    @MockitoBean
    private ProviderService providerService;

    @BeforeEach
    void pinMode() {
        ensurePasswordlessMode();
    }

    @Test
    @DisplayName("list：返回全部供应商配置")
    void listProviders() throws Exception {
        Provider provider = new Provider();
        provider.setGroup("openrouter");
        provider.setModelName("claude-4");
        when(providerService.getAllProviders()).thenReturn(List.of(provider));

        mockMvc.perform(localGet("/api/provider/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].group").value("openrouter"))
                .andExpect(jsonPath("$.data[0].modelName").value("claude-4"));
    }

    @Test
    @DisplayName("save：请求体反序列化 + originalGroup/originalModelName 可选参数透传")
    void saveProvider() throws Exception {
        Provider saved = new Provider();
        saved.setGroup("openrouter");
        saved.setModelName("claude-4");
        when(providerService.saveProvider(any(Provider.class),
                eq("deepseek"), eq("ds-v3")))
                .thenReturn(saved);

        mockMvc.perform(localPost("/api/provider/save")
                        .param("originalGroup", "deepseek")
                        .param("originalModelName", "ds-v3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"group\":\"openrouter\",\"modelName\":\"claude-4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.group").value("openrouter"));

        verify(providerService).saveProvider(any(Provider.class),
                eq("deepseek"), eq("ds-v3"));
    }

    @Test
    @DisplayName("delete：按分组与模型名物理删除")
    void deleteProvider() throws Exception {
        mockMvc.perform(localDelete("/api/provider/delete")
                        .param("group", "openrouter")
                        .param("modelName", "claude-4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(providerService).deleteProvider("openrouter", "claude-4");
    }
}
