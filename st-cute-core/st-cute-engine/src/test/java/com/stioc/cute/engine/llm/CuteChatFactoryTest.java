package com.stioc.cute.engine.llm;

import com.stioc.cute.engine.llm.types.Provider;
import com.stioc.cute.engine.llm.types.ProviderProtocol;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.testkit.EngineStubs;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CuteChatFactory} 客户端组装与缓存单元测试。
 * <p>
 * 该工厂是供应商配置热更新的唯一落点：宿主改配置后，下一次解析出的新配置快照
 * 与缓存中记录的旧快照不一致即自动重建客户端。若指纹比对失效，用户改配置后
 * 必须重启应用才能生效。故此处重点覆盖缓存复用、指纹自失效与快速失败三条语义。
 * </p>
 * <p>
 * 注：本测试只验证客户端「组装与缓存」行为，不发起任何真实网络调用。
 * </p>
 */
class CuteChatFactoryTest {

    /**
     * 可编程的供应商配置解析器（模拟宿主 ProviderService 行为）
     */
    private static final class MutableResolver implements ProviderResolver {

        private Provider provider;

        private MutableResolver(Provider provider) {
            this.provider = provider;
        }

        @Override
        public Provider getProviderConfigForContext(AgentContext context) {
            return provider;
        }

        private void update(Provider next) {
            this.provider = next;
        }
    }

    private static Provider provider(String model, int contextSize) {
        return Provider.builder()
                .group("test-group")
                .protocol("OPENAI")
                .baseUrl("http://127.0.0.1:1/v1")
                .apiKey("test-key")
                .modelName(model)
                .contextSize(contextSize)
                .build();
    }

    private final AgentContext context = EngineStubs.agentContext(1L);

    /**
     * 同配置重复取用应命中缓存，返回同一客户端实例
     */
    @Test
    void reusesClientForSameConfig() {
        MutableResolver resolver = new MutableResolver(provider("m1", 100000));
        CuteChatFactory factory = new CuteChatFactory(resolver, Optional.empty(), Optional.empty());

        CuteChat first = factory.getCuteChat(context);
        CuteChat second = factory.getCuteChat(context);

        assertSame(first, second, "配置未变时必须复用同一客户端实例");
    }

    /**
     * 配置指纹变化（同 group/model 但其余字段改动）时应自动重建客户端
     */
    @Test
    void rebuildsClientWhenConfigFingerprintChanges() {
        MutableResolver resolver = new MutableResolver(provider("m1", 100000));
        CuteChatFactory factory = new CuteChatFactory(resolver, Optional.empty(), Optional.empty());

        CuteChat before = factory.getCuteChat(context);
        // 同 group + model，仅 contextSize 变化 → 快照指纹不同 → 必须重建
        resolver.update(provider("m1", 200000));
        CuteChat after = factory.getCuteChat(context);

        assertNotSame(before, after, "配置快照变化后必须重建客户端，否则配置热更新失效");
    }

    /**
     * model 不同应使用不同缓存条目，互不干扰
     */
    @Test
    void cachesPerModelSeparately() {
        MutableResolver resolver = new MutableResolver(provider("m1", 100000));
        CuteChatFactory factory = new CuteChatFactory(resolver, Optional.empty(), Optional.empty());

        CuteChat forM1 = factory.getCuteChat(context);
        resolver.update(provider("m2", 100000));
        CuteChat forM2 = factory.getCuteChat(context);

        assertNotSame(forM1, forM2, "不同模型应各自持有独立客户端");

        // 切回 m1 应命中原缓存（而非重建）
        resolver.update(provider("m1", 100000));
        assertSame(forM1, factory.getCuteChat(context), "切回原模型应复用其缓存实例");
    }

    /**
     * 三种协议均应能正确组装为对应的客户端实现。
     * <p>
     * 工厂统一以重试装饰器收尾（透明重试），故此处解包装饰器后校验底层协议实现类型，
     * 确保「配置协议 → 客户端实现」的映射未被写错（写错会导致请求体格式与供应商不匹配）。
     * </p>
     */
    @Test
    void assemblesClientPerProtocol() {
        assertInstanceOf(CuteChatForOpenAi.class, unwrap(assembleWithProtocol("OPENAI")));
        assertInstanceOf(CuteChatForOpenAiResponse.class, unwrap(assembleWithProtocol("OPENAI_RESPONSE")));
        assertInstanceOf(CuteChatForAnthropic.class, unwrap(assembleWithProtocol("ANTHROPIC")));
    }

    /**
     * 解包重试装饰器，取出底层协议客户端实现
     */
    private static CuteChat unwrap(CuteChat chat) {
        try {
            java.lang.reflect.Field field = CuteChatRetryWrapper.class.getDeclaredField("delegate");
            field.setAccessible(true);
            return (CuteChat) field.get(chat);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("解包重试装饰器失败", e);
        }
    }

    private CuteChat assembleWithProtocol(String protocol) {
        Provider config = Provider.builder()
                .group("g")
                .protocol(protocol)
                .baseUrl("http://127.0.0.1:1/v1")
                .apiKey("k")
                .modelName("m")
                .build();
        CuteChatFactory factory = new CuteChatFactory(
                new MutableResolver(config), Optional.empty(), Optional.empty());
        // 工厂统一以重试装饰器收尾，故解包校验底层实现
        return factory.getCuteChat(context);
    }

    /**
     * 未知协议必须快速失败（而非静默降级到某个默认协议）
     */
    @Test
    void failsFastOnUnknownProtocol() {
        Provider config = Provider.builder()
                .group("g").protocol("NOT_A_PROTOCOL").apiKey("k").modelName("m").build();
        CuteChatFactory factory = new CuteChatFactory(
                new MutableResolver(config), Optional.empty(), Optional.empty());

        assertThrows(UnsupportedOperationException.class, () -> factory.getCuteChat(context));
    }

    /**
     * 缺少 API Key 必须快速失败（拒绝带着空 Key 发起真实请求）
     */
    @Test
    void failsFastOnMissingApiKey() {
        Provider config = Provider.builder()
                .group("g").protocol("OPENAI").modelName("m").build();
        CuteChatFactory factory = new CuteChatFactory(
                new MutableResolver(config), Optional.empty(), Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.getCuteChat(context));
        assertTrue(ex.getMessage().contains("未配置 API Key"),
                "异常信息应指明缺少 API Key，实际: " + ex.getMessage());
    }

    /**
     * 解析器返回 null（会话未绑定或供应商已失效）必须快速失败
     */
    @Test
    void failsFastWhenProviderUnresolvable() {
        CuteChatFactory factory = new CuteChatFactory(
                new MutableResolver(null), Optional.empty(), Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> factory.getCuteChat(context));
        assertTrue(ex.getMessage().contains("供应商不存在或已失效"),
                "异常信息应指明供应商不可解析，实际: " + ex.getMessage());
    }

    /**
     * 配置快照读取入口不触发客户端组装，且与客户端工厂共用同一解析结果
     */
    @Test
    void exposesConfigSnapshotWithoutAssembling() {
        Provider config = provider("m1", 100000);
        CuteChatFactory factory = new CuteChatFactory(
                new MutableResolver(config), Optional.empty(), Optional.empty());

        assertEquals(config, factory.getProviderConfigForContext(context));
    }

    /**
     * 配置快照不可解析时，快照读取入口同样快速失败
     */
    @Test
    void failsFastOnSnapshotWhenUnresolvable() {
        CuteChatFactory factory = new CuteChatFactory(
                new MutableResolver(null), Optional.empty(), Optional.empty());
        assertThrows(IllegalStateException.class, () -> factory.getProviderConfigForContext(context));
    }

    /**
     * 协议名大小写与首尾空格应被容错解析（配置由用户手工填写）
     */
    @Test
    void toleratesProtocolNameCaseAndSpaces() {
        Provider config = Provider.builder()
                .group("g").protocol("  openai  ").baseUrl("http://127.0.0.1:1/v1")
                .apiKey("k").modelName("m").build();
        CuteChatFactory factory = new CuteChatFactory(
                new MutableResolver(config), Optional.empty(), Optional.empty());

        assertEquals(ProviderProtocol.OPENAI, config.getProtocolEnum(),
                "协议名应忽略大小写与首尾空格");
        assertEquals(CuteChatRetryWrapper.class, factory.getCuteChat(context).getClass(),
                "容错解析后应正常组装出客户端");
    }

    /**
     * baseUrl 缺省时应回退协议默认地址（不抛异常、不产生 null URL）
     */
    @Test
    void fallsBackToDefaultBaseUrl() {
        Provider config = Provider.builder()
                .group("g").protocol("OPENAI").apiKey("k").modelName("m").build();
        CuteChatFactory factory = new CuteChatFactory(
                new MutableResolver(config), Optional.empty(), Optional.empty());

        // 仅验证组装成功（不发起调用），确保缺省 baseUrl 不会导致组装期异常
        assertEquals(CuteChatRetryWrapper.class, factory.getCuteChat(context).getClass());
    }

    /**
     * 工厂对可选供血缺省应安全降级（llmHttpLogger / retryPolicyProvider 均为空）
     */
    @Test
    void toleratesAbsentOptionalSupplies() {
        Provider config = provider("m1", 100000);
        CuteChatFactory factory = new CuteChatFactory(
                new MutableResolver(config), Optional.empty(), Optional.empty());

        assertEquals(CuteChatRetryWrapper.class, factory.getCuteChat(context).getClass(),
                "可选供血缺省时应安全降级并正常组装");
    }
}
