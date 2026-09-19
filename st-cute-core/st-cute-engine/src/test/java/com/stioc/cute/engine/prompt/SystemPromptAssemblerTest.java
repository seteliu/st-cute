package com.stioc.cute.engine.prompt;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.testkit.EngineStubs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SystemPromptAssembler} 系统提示词组装单元测试。
 * <p>
 * 关注三件事：贡献者按 order 升序拼装、空白段落被过滤、单贡献者异常不拖垮整体。
 * 第三点尤其重要——宿主贡献者可能读文件/读配置，其在组装期抛异常不得导致整轮推理不可用。
 * </p>
 */
class SystemPromptAssemblerTest {

    /**
     * 假贡献者：按构造参数返回固定内容或抛异常
     */
    private record FakeContributor(int order, String content, boolean throwOnContribute)
            implements SystemPromptContributor {

        @Override
        public int order() {
            return order;
        }

        @Override
        public String contribute(AgentContext context) {
            if (throwOnContribute) {
                throw new IllegalStateException("模拟贡献者执行异常");
            }
            return content;
        }
    }

    private final AgentContext context = EngineStubs.agentContext(1L);

    /**
     * 贡献者应按 order 升序拼接（与传入顺序无关），段落间以空行分隔
     */
    @Test
    void assemblesByAscendingOrder() {
        List<SystemPromptContributor> contributors = List.of(
                new FakeContributor(300, "第三段", false),
                new FakeContributor(100, "第一段", false),
                new FakeContributor(200, "第二段", false));

        String result = new SystemPromptAssembler(contributors).assemble(context);
        assertEquals("第一段\n\n第二段\n\n第三段", result);
    }

    /**
     * 空白与 null 段落必须被过滤，且不产生多余空行
     */
    @Test
    void filtersBlankSegments() {
        List<SystemPromptContributor> contributors = List.of(
                new FakeContributor(100, "有效段", false),
                new FakeContributor(200, null, false),
                new FakeContributor(300, "   ", false),
                new FakeContributor(400, "", false),
                new FakeContributor(500, "尾段", false));

        String result = new SystemPromptAssembler(contributors).assemble(context);
        assertEquals("有效段\n\n尾段", result);
    }

    /**
     * 单贡献者抛异常时本段跳过，其余段落正常组装（异常隔离）
     */
    @Test
    void isolatesContributorFailure() {
        List<SystemPromptContributor> contributors = List.of(
                new FakeContributor(100, "前段", false),
                new FakeContributor(200, null, true),
                new FakeContributor(300, "后段", false));

        String result = new SystemPromptAssembler(contributors).assemble(context);
        assertEquals("前段\n\n后段", result, "异常贡献者应被跳过，不得影响其余段落");
    }

    /**
     * 全部贡献者异常时返回空串（不得抛出，保证推理链路可继续）
     */
    @Test
    void returnsEmptyWhenAllFail() {
        List<SystemPromptContributor> contributors = List.of(
                new FakeContributor(100, null, true),
                new FakeContributor(200, null, true));

        assertEquals("", new SystemPromptAssembler(contributors).assemble(context));
    }

    /**
     * 无贡献者与构造入参为 null 时均安全降级为空串
     */
    @Test
    void handlesEmptyContributors() {
        assertEquals("", new SystemPromptAssembler(List.of()).assemble(context));
        assertEquals("", new SystemPromptAssembler(null).assemble(context));
    }

    /**
     * 同 order 的贡献者应保持传入顺序（稳定排序，保证提示词内容可预期）
     */
    @Test
    void keepsStableOrderForEqualOrders() {
        List<SystemPromptContributor> contributors = new ArrayList<>(List.of(
                new FakeContributor(100, "A", false),
                new FakeContributor(100, "B", false),
                new FakeContributor(100, "C", false)));

        String result = new SystemPromptAssembler(contributors).assemble(context);
        assertEquals("A\n\nB\n\nC", result, "同 order 应保持传入顺序");
    }

    /**
     * 首尾空白应被裁剪（贡献者常以换行结尾，避免提示词首尾冗余）
     */
    @Test
    void trimsLeadingAndTrailingWhitespace() {
        List<SystemPromptContributor> contributors = List.of(
                new FakeContributor(100, "\n  开头带空白  \n", false));

        String result = new SystemPromptAssembler(contributors).assemble(context);
        assertFalse(result.startsWith("\n"), "结果不应以换行开头");
        assertTrue(result.startsWith("开头带空白"), "首部空白应被裁剪，实际: [" + result + "]");
    }
}
