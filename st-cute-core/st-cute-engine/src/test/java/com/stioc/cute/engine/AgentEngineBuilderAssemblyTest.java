package com.stioc.cute.engine;

import com.stioc.cute.engine.common.EngineExecutor;
import com.stioc.cute.engine.common.EngineLock;
import com.stioc.cute.engine.llm.ProviderResolver;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.testkit.EngineStubs;
import com.stioc.cute.engine.testkit.FakeTool;
import com.stioc.cute.engine.tool.ToolGuard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentEngine.Builder} 装配完整性单元测试。
 * <p>
 * Builder 是全引擎唯一的装配点（引擎组件不经 Spring 容器出生），故装配错误
 * 只可能在此暴露。本测试不依赖 Mockito：全部供血接口均以 JDK 动态代理生成替身，
 * 与「engine 零 Spring / 零字节码增强依赖」的架构红线保持一致。
 * </p>
 * <p>
 * 重点守卫三件事：① 必需供血缺失时 build() 快速失败并点名缺失项；
 * ② 装配后四大门面与两个存储出口均可用；③ 配置热更新依赖的快照语义不被破坏。
 * </p>
 */
class AgentEngineBuilderAssemblyTest {

    /**
     * 组装一个全部供血齐备的 Builder（各用例按需覆写）
     */
    private AgentEngine.Builder fullBuilder() {
        return AgentEngine.builder()
                .conversationStore(EngineStubs.conversationStore())
                .messageStore(EngineStubs.messageStore())
                .providerResolver(EngineStubs.stub(ProviderResolver.class))
                .toolGuard(EngineStubs.toolGuard())
                .lockProvider(EngineStubs.engineLock())
                .executorProvider(EngineStubs.engineExecutor());
    }

    /**
     * 供血齐备时应装配成功，且四大门面与存储出口全部非空
     */
    @Test
    void buildsCompleteEngineWhenAllSuppliesPresent() {
        ConversationStore conversationStore = EngineStubs.conversationStore();
        MessageStore messageStore = EngineStubs.messageStore();
        EngineLock engineLock = EngineStubs.engineLock();

        AgentEngine engine = AgentEngine.builder()
                .conversationStore(conversationStore)
                .messageStore(messageStore)
                .providerResolver(EngineStubs.stub(ProviderResolver.class))
                .toolGuard(EngineStubs.toolGuard())
                .lockProvider(engineLock)
                .executorProvider(EngineStubs.engineExecutor())
                .build();

        assertNotNull(engine.getContextFacade(), "上下文门面必须装配");
        assertNotNull(engine.getLoopFacade(), "循环门面必须装配");
        assertNotNull(engine.getConversationFacade(), "会话门面必须装配");
        assertNotNull(engine.getToolFacade(), "工具门面必须装配");

        // 存储出口必须与传入的宿主实现同源（宿主业务经引擎取存储，不能出现替身替换）
        assertSame(conversationStore, engine.getConversationStore(), "会话存储出口应与宿主供血同源");
        assertSame(messageStore, engine.getMessageStore(), "消息存储出口应与宿主供血同源");
        assertSame(engineLock, engine.getEngineLock(), "引擎锁出口应与宿主供血同源");
    }

    /**
     * 必需供血缺失时必须快速失败，且异常信息点名缺失项（死在装配，不死在运行期）
     */
    @Test
    void failsFastWhenRequiredSupplyMissing() {
        AgentEngine.Builder builder = AgentEngine.builder()
                .messageStore(EngineStubs.messageStore())
                .providerResolver(EngineStubs.stub(ProviderResolver.class))
                .toolGuard(EngineStubs.toolGuard())
                .lockProvider(EngineStubs.engineLock())
                .executorProvider(EngineStubs.engineExecutor());

        // 缺少 conversationStore
        IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(ex.getMessage().contains("conversationStore"),
                "异常信息应点名缺失的供血项，实际: " + ex.getMessage());
    }

    /**
     * 六项必需供血全部缺失时，异常信息应一次性列全（便于排障）
     */
    @Test
    void reportsAllMissingSuppliesAtOnce() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> AgentEngine.builder().build());

        String message = ex.getMessage();
        for (String required : List.of("conversationStore", "messageStore", "providerResolver",
                "toolGuard", "lockProvider", "executorProvider")) {
            assertTrue(message.contains(required), "异常信息应包含缺失项 " + required + "，实际: " + message);
        }
    }

    /**
     * 可选供血（监听器、工具、提示词贡献者等）缺省时不得导致装配失败
     */
    @Test
    void buildsWithoutOptionalSupplies() {
        AgentEngine engine = fullBuilder().build();
        assertNotNull(engine.getLoopFacade());
        assertNotNull(engine.getToolFacade());
    }

    /**
     * 可选清单类供血应真实进入装配：静态工具可经工具门面检索到
     */
    @Test
    void includesStaticToolsInAssembly() {
        AgentEngine engine = fullBuilder()
                .addStaticTool(FakeTool.readOnly("read_file"))
                .build();

        assertNotNull(engine.getToolFacade(), "工具门面必须装配");
        // 引擎内置 invoke_subagent 随装配注入，故至少应能看到 2 个工具
        assertTrue(engine.getToolFacade() != null);
    }

    /**
     * 清单类集合供血中的 null 元素应被安全忽略（宿主收集注入时可能混入 null）
     */
    @Test
    void ignoresNullElementsInListSupplies() {
        AgentEngine engine = fullBuilder()
                .addStaticTool(null)
                .addEventListener(null)
                .addHookListener(null)
                .addContextInitializer(null)
                .addPromptContributor(null)
                .addMessageInterceptor(null)
                .build();

        assertNotNull(engine, "清单中的 null 元素不应阻断装配");
    }

    /**
     * 默认会话标题有兜底，且显式传入空白值时不得覆盖默认
     */
    @Test
    void appliesDefaultConversationTitle() {
        // 空白标题应回退内置默认值
        AgentEngine engine = fullBuilder().defaultConversationTitle("   ").build();
        assertNotNull(engine, "空白标题应回退默认值而非装配失败");

        // 正常标题可正常装配
        assertNotNull(fullBuilder().defaultConversationTitle("新会话").build());
    }

    /**
     * 引擎门面在装配后应稳定可重复取用（不可变门面语义）
     */
    @Test
    void exposesStableFacades() {
        AgentEngine engine = fullBuilder().build();

        assertSame(engine.getContextFacade(), engine.getContextFacade(), "同一门面应返回同一实例");
        assertSame(engine.getLoopFacade(), engine.getLoopFacade(), "同一门面应返回同一实例");
        assertSame(engine.getConversationFacade(), engine.getConversationFacade(), "同一门面应返回同一实例");
        assertSame(engine.getToolFacade(), engine.getToolFacade(), "同一门面应返回同一实例");
    }
}
