package com.stioc.cute.engine.tool;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.testkit.EngineStubs;
import com.stioc.cute.engine.testkit.FakeTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolRegistry} 工具注册中心单元测试。
 * <p>
 * 覆盖三块：启动期 Schema 卫兵的 fail-fast 语义、静态工具的检索语义（忽略大小写）、
 * 以及 CuteTool 名称/域拼接与默认治理等级约定。
 * </p>
 */
class ToolRegistryTest {

    private final AgentContext context = EngineStubs.agentContext(1L);

    /**
     * 正常注册：合法 Schema 的工具应进入索引并可按名检索
     */
    @Test
    void registersAndResolvesValidTools() {
        ToolRegistry registry = new ToolRegistry(
                List.of(FakeTool.readOnly("read_file"), FakeTool.readOnly("write_file")),
                Optional.empty());
        registry.init();

        assertSame(registry.getTool("read_file", context).getClass(), FakeTool.class);
        assertEquals("read_file", registry.getTool("read_file", context).getName());
        assertEquals(2, registry.getAllTools(context).size());
    }

    /**
     * 检索忽略大小写（模型返回的工具名大小写不总是规范）
     */
    @Test
    void resolvesToolNameIgnoringCase() {
        ToolRegistry registry = new ToolRegistry(List.of(FakeTool.readOnly("read_file")), Optional.empty());
        registry.init();

        assertEquals("read_file", registry.getTool("READ_FILE", context).getName());
        assertEquals("read_file", registry.getTool("Read_File", context).getName());
    }

    /**
     * 未注册工具返回 null（引擎据此判定幻觉工具并累计熔断计数）
     */
    @Test
    void returnsNullForUnknownTool() {
        ToolRegistry registry = new ToolRegistry(List.of(FakeTool.readOnly("read_file")), Optional.empty());
        registry.init();

        assertNull(registry.getTool("not_exist", context));
        assertNull(registry.getTool(null, context));
        assertNull(registry.getTool("   ", context));
    }

    /**
     * Schema 非法的静态工具必须 fail-fast 拒绝注册（带病 Schema 会让模型调用必失败）
     */
    @Test
    void failsFastOnInvalidSchema() {
        FakeTool broken = FakeTool.builder("broken_tool")
                .argumentSchema("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"int\"}}}")
                .build();
        ToolRegistry registry = new ToolRegistry(List.of(broken), Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, registry::init);
        assertTrue(ex.getMessage().contains("broken_tool"),
                "异常信息应指明涉事工具，实际: " + ex.getMessage());
    }

    /**
     * 空 Schema 不属于合法 Schema（会被规范化逻辑接管前就应判错）
     */
    @Test
    void failsFastOnBlankSchema() {
        FakeTool blank = FakeTool.builder("blank_tool").argumentSchema("").build();
        ToolRegistry registry = new ToolRegistry(List.of(blank), Optional.empty());
        assertThrows(IllegalStateException.class, registry::init);
    }

    /**
     * 工具域非空时，对外全名应自动拼接为 domain__rawName
     */
    @Test
    void composesDomainQualifiedName() {
        FakeTool tool = FakeTool.builder("create_issue").domain("mcp__github").build();
        assertEquals("mcp__github__create_issue", tool.getName());

        // 按全名注册与检索
        ToolRegistry registry = new ToolRegistry(List.of(tool), Optional.empty());
        registry.init();
        assertEquals("mcp__github__create_issue", registry.getTool("mcp__github__create_issue", context).getName());
    }

    /**
     * 无域工具的全名即原生名
     */
    @Test
    void keepsRawNameWhenDomainBlank() {
        assertEquals("read_file", FakeTool.readOnly("read_file").getName());
        assertEquals("read_file", FakeTool.builder("read_file").domain("  ").build().getName());
    }

    /**
     * 未显式声明访问等级的工具按最保守的 SENSITIVE 兜底
     */
    @Test
    void defaultsToSensitiveAccessLevel() {
        CuteTool minimal = new CuteTool() {
            @Override
            public String getRawName() {
                return "minimal";
            }

            @Override
            public String getDescription() {
                return "最小实现";
            }

            @Override
            public String getArgumentSchema() {
                return "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}";
            }

            @Override
            public String execute(java.util.Map<String, Object> arguments,
                                  com.stioc.cute.engine.tool.types.ToolExecutionContext context) {
                return "ok";
            }
        };

        assertEquals(com.stioc.cute.engine.tool.types.ToolAccessLevel.SENSITIVE, minimal.getAccessLevel(),
                "未声明访问等级应兜底为 SENSITIVE");
        assertTrue(minimal.isAvailable(context), "默认应对所有上下文可用");
    }

    /**
     * 零静态工具、零动态源时注册中心应安全初始化
     */
    @Test
    void handlesEmptyRegistry() {
        ToolRegistry registry = new ToolRegistry(List.of(), Optional.empty());
        registry.init();

        assertTrue(registry.getAllTools(context).isEmpty());
        assertNull(registry.getTool("any", context));
    }
}
