package com.stioc.cute.controller;

import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.facade.ContextFacade;
import com.stioc.cute.engine.facade.ConversationFacade;
import com.stioc.cute.engine.facade.LoopFacade;
import com.stioc.cute.engine.facade.ToolFacade;
import com.stioc.cute.platform.contract.ContractProperty;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * controller 切片测试抽象基类。
 * <p>
 * 提供：本机来源请求构建辅助（配合 WebSecurityFilter 的免密四重校验）、
 * 引擎门面统一桩装配、属性模式显式钉住与复位纪律。
 * </p>
 * <p>
 * 鉴权口径：过滤器链在切片中真实生效（@AutoConfigureMockMvc 自动挂载上下文内 Filter bean，
 * DesktopSecurityFilter 先于 WebSecurityFilter），故所有请求必须以本机来源形态发起：
 * Host 头 localhost + remoteAddr 127.0.0.1。非本机来源会被 401 拦截在过滤器层，
 * 到不了 controller——那是 WebSecurityFilterTest 的职责范围，本切片不重复覆盖。
 * </p>
 */
public abstract class AbstractControllerSliceTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ContractProperty contractProperty;

    // ──────────────────────────────────────────────
    // 本机来源请求辅助
    // ──────────────────────────────────────────────

    /**
     * 本机来源 GET（免密模式四重校验全通过）
     */
    protected static MockHttpServletRequestBuilder localGet(String url) {
        return get(url).header(HttpHeaders.HOST, "localhost").remoteAddress("127.0.0.1");
    }

    /**
     * 本机来源 POST
     */
    protected static MockHttpServletRequestBuilder localPost(String url) {
        return post(url).header(HttpHeaders.HOST, "localhost").remoteAddress("127.0.0.1");
    }

    /**
     * 本机来源 DELETE
     */
    protected static MockHttpServletRequestBuilder localDelete(String url) {
        return delete(url).header(HttpHeaders.HOST, "localhost").remoteAddress("127.0.0.1");
    }

    /**
     * 本机来源 POST，携带同源 Origin（密码模式下状态变更请求的同源校验）
     */
    protected static MockHttpServletRequestBuilder originPost(String url, String origin) {
        return post(url).header(HttpHeaders.HOST, "localhost").remoteAddress("127.0.0.1")
                .header(HttpHeaders.ORIGIN, origin);
    }

    /**
     * 本机来源 multipart 上传（返回的 builder 需自行追加 .file() 与 .param()）
     */
    protected static MockMultipartHttpServletRequestBuilder localMultipart(String url) {
        MockMultipartHttpServletRequestBuilder builder = multipart(url);
        builder.header(HttpHeaders.HOST, "localhost").remoteAddress("127.0.0.1");
        return builder;
    }

    // ──────────────────────────────────────────────
    // 引擎门面统一桩
    // ──────────────────────────────────────────────

    /**
     * 引擎门面桩：一次性接好 controller 常用的四条 facade 链。
     * <p>lenient 桩：个别测试类只用到其中一两条链，严格模式会因未消费的桩打失败。</p>
     *
     * @param engine        已 mock 的 AgentEngine（由 @MockitoBean 注入上下文的实例）
     * @param contextFacade 会话上下文门面桩
     * @param loopFacade    循环门面桩
     * @param conversationFacade 会话数据门面桩
     * @param toolFacade    工具审批门面桩
     */
    protected void stubEngineFacades(AgentEngine engine, ContextFacade contextFacade,
                                     LoopFacade loopFacade, ConversationFacade conversationFacade,
                                     ToolFacade toolFacade) {
        lenient().when(engine.getContextFacade()).thenReturn(contextFacade);
        lenient().when(engine.getLoopFacade()).thenReturn(loopFacade);
        lenient().when(engine.getConversationFacade()).thenReturn(conversationFacade);
        lenient().when(engine.getToolFacade()).thenReturn(toolFacade);
    }

    // ──────────────────────────────────────────────
    // 鉴权模式纪律
    // ──────────────────────────────────────────────

    /**
     * 显式切换为免密模式（未配置访问码）。
     * <p>不依赖"默认就是免密"的环境假设——切片内 ContractProperty 的 @PostConstruct
     * 可能读取到测试隔离目录下遗留的 config.json 而进入密码模式。</p>
     */
    protected void ensurePasswordlessMode() {
        contractProperty.setPassword(null);
    }

    /**
     * 显式切换为密码模式（模拟已配置访问码存储摘要）
     */
    protected void enablePasswordMode() {
        contractProperty.setPassword("$2a$fakeStoredDigest");
    }

    /**
     * 复位为免密模式，防止污染共享同一上下文缓存的后续用例
     */
    @AfterEach
    void resetPasswordMode() {
        contractProperty.setPassword(null);
    }
}
