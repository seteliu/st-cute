package com.stioc.cute.engine.llm;

import com.stioc.cute.engine.llm.types.CuteChatOptions;
import com.stioc.cute.engine.llm.types.CuteToolDefinition;
import com.stioc.cute.engine.llm.types.Provider;
import com.stioc.cute.engine.testkit.FakeTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatOptionsFactory} 大模型调用参数组装单元测试。
 * <p>
 * 关注三类行为：配置缺省兜底（temperature）、工具 Schema 空值规范化、
 * 以及配置项到请求参数的一比一透传。
 * </p>
 */
class ChatOptionsFactoryTest {

    private final ChatOptionsFactory factory = new ChatOptionsFactory();

    /**
     * temperature 未配置时兜底 0.7（OpenAI 侧调用契约）
     */
    @Test
    void defaultsTemperatureWhenAbsent() {
        Provider config = Provider.builder().modelName("m").build();
        CuteChatOptions options = factory.buildOptions(config, List.of());
        assertEquals(0.7d, options.getTemperature(), 0.0001d);
    }

    /**
     * temperature 显式配置时原样透传（含 0.0 这一合法取值，不得被误兜底）
     */
    @Test
    void passesThroughExplicitTemperature() {
        CuteChatOptions zero = factory.buildOptions(
                Provider.builder().modelName("m").temperature(0.0d).build(), List.of());
        assertEquals(0.0d, zero.getTemperature(), 0.0001d);

        CuteChatOptions high = factory.buildOptions(
                Provider.builder().modelName("m").temperature(1.8d).build(), List.of());
        assertEquals(1.8d, high.getTemperature(), 0.0001d);
    }

    /**
     * 模型名、最大 token、思考级别与工具清单应一比一透传
     */
    @Test
    void passesThroughProviderFields() {
        Provider config = Provider.builder()
                .modelName("deepseek-chat")
                .maxTokens(4096)
                .reasoningEffort("high")
                .build();
        FakeTool tool = FakeTool.builder("read_file").description("读取文件").build();

        CuteChatOptions options = factory.buildOptions(config, List.of(tool));

        assertEquals("deepseek-chat", options.getModel());
        assertEquals(4096, options.getMaxTokens());
        assertEquals("high", options.getReasoningEffort());
        assertEquals(1, options.getTools().size());

        CuteToolDefinition def = options.getTools().get(0);
        assertEquals("read_file", def.getName());
        assertEquals("读取文件", def.getDescription());
    }

    /**
     * 工具 Schema 为空 / 空白 / "{}" 时统一规范化，避免把空 Schema 送进模型请求体
     */
    @Test
    void normalizesBlankToolSchema() {
        List<String> blankSchemas = List.of("", "   ", "{}");

        for (String blank : blankSchemas) {
            FakeTool tool = FakeTool.builder("t").argumentSchema(blank).build();
            CuteChatOptions options = factory.buildOptions(
                    Provider.builder().modelName("m").build(), List.of(tool));

            String schema = options.getTools().get(0).getInputSchema();
            assertEquals("{\"type\":\"object\",\"properties\":{}}", schema,
                    "空白 Schema 应被规范化为空对象 Schema，输入为: [" + blank + "]");
        }
    }

    /**
     * 非空 Schema 原样保留，不得被规范化改写
     */
    @Test
    void preservesNonBlankToolSchema() {
        String schema = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}";
        FakeTool tool = FakeTool.builder("t").argumentSchema(schema).build();

        CuteChatOptions options = factory.buildOptions(
                Provider.builder().modelName("m").build(), List.of(tool));

        assertEquals(schema, options.getTools().get(0).getInputSchema());
    }

    /**
     * 无可调用工具时工具清单为空
     */
    @Test
    void handlesEmptyToolList() {
        CuteChatOptions options = factory.buildOptions(
                Provider.builder().modelName("m").build(), List.of());
        assertTrue(options.getTools().isEmpty());
    }

    /**
     * 模型名未配置时应为空（由底层客户端回退到构造期模型名）
     */
    @Test
    void leavesModelNullWhenUnset() {
        CuteChatOptions options = factory.buildOptions(Provider.builder().build(), List.of());
        assertNull(options.getModel());
    }
}
