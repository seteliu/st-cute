package com.stioc.cute.engine.tool;

import com.stioc.cute.engine.hook.HookPayload;
import com.stioc.cute.engine.hook.HookListener;
import com.stioc.cute.engine.hook.HookType;
import com.stioc.cute.engine.llm.types.CuteToolCall;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.testkit.EngineFixture;
import com.stioc.cute.engine.testkit.EngineTestHarness;
import com.stioc.cute.engine.testkit.FakeLlmServer;
import com.stioc.cute.engine.testkit.FakeTool;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolApprovalRequest;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolPermissionVerdict;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具执行引擎的批量调度、权限门禁与人在回路审批测试。
 * <p>
 * 这是引擎里并发语义最密集的一段：只读工具要能并发跑、有副作用的工具必须串行且按锁键互斥、
 * 审批要能真正挂起等待（而不是占着线程空转），审批结果要能正确收口并推进循环。
 * 任何一处出错都会表现为「工具不执行」「重复执行」或「循环卡死」这类线上难查的问题。
 * </p>
 */
class ToolExecutionEngineTest {

    // ──────────────────────────────────────────────
    // 测试内局部替身
    // ──────────────────────────────────────────────

    /**
     * 并发探针工具：记录执行期间的并发峰值，用于判定只读批是否真的并发
     */
    private static final class ConcurrencyProbeTool implements CuteTool {

        private final String name;
        private final ToolAccessLevel accessLevel;
        private final AtomicInteger inflight;
        private final AtomicInteger maxInflight;
        private final long holdMillis;

        private ConcurrencyProbeTool(String name, ToolAccessLevel accessLevel,
                                     AtomicInteger inflight, AtomicInteger maxInflight, long holdMillis) {
            this.name = name;
            this.accessLevel = accessLevel;
            this.inflight = inflight;
            this.maxInflight = maxInflight;
            this.holdMillis = holdMillis;
        }

        @Override
        public String getRawName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "并发探针工具";
        }

        @Override
        public String getArgumentSchema() {
            return "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}";
        }

        @Override
        public ToolAccessLevel getAccessLevel() {
            return accessLevel;
        }

        @Override
        public String getLockKey(Map<String, Object> arguments, AgentContext context) {
            return "shared-lock-key";
        }

        @Override
        public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
            int now = inflight.incrementAndGet();
            maxInflight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(holdMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inflight.decrementAndGet();
            }
            return "done";
        }
    }

    /**
     * 顺序探针工具：把执行痕迹追加进共享清单，用于断言分批与执行顺序
     */
    private static final class OrderedProbeTool implements CuteTool {

        private final String name;
        private final ToolAccessLevel accessLevel;
        private final List<String> trace;

        private OrderedProbeTool(String name, ToolAccessLevel accessLevel, List<String> trace) {
            this.name = name;
            this.accessLevel = accessLevel;
            this.trace = trace;
        }

        @Override
        public String getRawName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "顺序探针工具";
        }

        @Override
        public String getArgumentSchema() {
            return "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}";
        }

        @Override
        public ToolAccessLevel getAccessLevel() {
            return accessLevel;
        }

        @Override
        public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
            synchronized (trace) {
                trace.add(name);
            }
            return "ok:" + name;
        }
    }

    /**
     * 审批请求快捷构造
     */
    private static ToolApprovalRequest approval(long cid, String toolCallId, String toolName, String decision) {
        return ToolApprovalRequest.builder()
                .cid(cid)
                .toolCallId(toolCallId)
                .toolName(toolName)
                .decision(decision)
                .build();
    }

    // ──────────────────────────────────────────────
    // 批量调度
    // ──────────────────────────────────────────────

    /**
     * 连续只读工具应合并为一个并发批，执行期间并发度大于 1（而非被串行化）
     */
    @Test
    void executesReadOnlyBatchConcurrently() throws IOException {
        AtomicInteger inflight = new AtomicInteger();
        AtomicInteger maxInflight = new AtomicInteger();
        try (EngineFixture fixture = EngineFixture.builder()
                .tool(new ConcurrencyProbeTool("probe_a", ToolAccessLevel.READ, inflight, maxInflight, 200))
                .tool(new ConcurrencyProbeTool("probe_b", ToolAccessLevel.READ, inflight, maxInflight, 200))
                .tool(new ConcurrencyProbeTool("probe_c", ToolAccessLevel.READ, inflight, maxInflight, 200))
                .build()) {
            fixture.llm().enqueue(FakeLlmServer.Turn.text("").addToolCall("c1", "probe_a", "{}")
                            .addToolCall("c2", "probe_b", "{}").addToolCall("c3", "probe_c", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("都跑完了。"));

            fixture.submitAndAwait("并发执行只读工具");

            assertTrue(maxInflight.get() > 1,
                    "只读批必须真正并发执行，实测并发峰值仅: " + maxInflight.get());
            assertEquals(3, EngineTestHarness.messagesOfRole(fixture.engine(),
                    fixture.cid(), MessageRole.TOOL).size());
        }
    }

    /**
     * 保序分批：只读→写→只读 应形成三个批次并严格按声明顺序执行
     */
    @Test
    void preservesModelCallOrderAcrossBatches() throws IOException {
        List<String> trace = Collections.synchronizedList(new ArrayList<>());
        try (EngineFixture fixture = EngineFixture.builder()
                .tool(new OrderedProbeTool("first_read", ToolAccessLevel.READ, trace))
                .tool(new OrderedProbeTool("middle_write", ToolAccessLevel.WRITE, trace))
                .tool(new OrderedProbeTool("last_read", ToolAccessLevel.READ, trace))
                .build()) {
            fixture.llm().enqueue(FakeLlmServer.Turn.text("")
                            .addToolCall("c1", "first_read", "{}")
                            .addToolCall("c2", "middle_write", "{}")
                            .addToolCall("c3", "last_read", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("顺序已完成。"));

            fixture.submitAndAwait("验证分批顺序");

            assertEquals(List.of("first_read", "middle_write", "last_read"), trace,
                    "工具必须保持模型原始调用顺序，写工具单独成串行批");
        }
    }

    /**
     * 同一锁键的写工具必须互斥执行（并发峰值恒为 1）
     */
    @Test
    void serializesWriteToolsOnSameLockKey() throws IOException {
        AtomicInteger inflight = new AtomicInteger();
        AtomicInteger maxInflight = new AtomicInteger();
        try (EngineFixture fixture = EngineFixture.builder()
                .tool(new ConcurrencyProbeTool("writer_a", ToolAccessLevel.WRITE, inflight, maxInflight, 100))
                .tool(new ConcurrencyProbeTool("writer_b", ToolAccessLevel.WRITE, inflight, maxInflight, 100))
                .build()) {
            fixture.llm().enqueue(FakeLlmServer.Turn.text("")
                            .addToolCall("w1", "writer_a", "{}")
                            .addToolCall("w2", "writer_b", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("写入完成。"));

            fixture.submitAndAwait("两个写工具");

            assertEquals(1, maxInflight.get(),
                    "同锁键的写工具必须串行互斥，实测并发峰值: " + maxInflight.get());
        }
    }

    // ──────────────────────────────────────────────
    // 失败与拦截
    // ──────────────────────────────────────────────

    /**
     * 未注册的工具应被引擎直接拦截为失败，且不得进入权限评估
     */
    @Test
    void rejectsUnknownToolWithoutPermissionEvaluation() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            fixture.llm().enqueue(FakeLlmServer.Turn.toolCall("c1", "ghost_tool", "{}"))
                    // 第二轮收尾，避免触发熔断判定干扰本用例
                    .enqueue(FakeLlmServer.Turn.text("已收到失败回执。"));

            fixture.submitAndAwait("调用不存在的工具");

            Message tool = EngineTestHarness.toolMessage(fixture.engine(), fixture.cid(), "c1");
            assertNotNull(tool);
            assertEquals(MessageStatus.FAILED, tool.getStatus());
            assertTrue(tool.getContent().contains("找不到该工具"),
                    "失败原因应指明工具未注册，实际: " + tool.getContent());
            assertEquals(0, fixture.guard().getEvaluateCount(),
                    "未知工具应在权限评估前早失败，不得浪费一次宿主评估");
        }
    }

    /**
     * 权限守卫返回 DENY 时应拦截并落到 FAILED，且携带守卫给出的原因
     */
    @Test
    void honorsDenyVerdict() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder()
                .tool(FakeTool.builder("danger_tool").accessLevel(ToolAccessLevel.SENSITIVE).build())
                .build()) {
            fixture.guard().alwaysReturn(ToolPermissionVerdict.deny("生产环境禁止执行"));
            fixture.llm().enqueue(FakeLlmServer.Turn.toolCall("c1", "danger_tool", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("已收到拒绝回执。"));

            fixture.submitAndAwait("调用敏感工具");

            Message tool = EngineTestHarness.toolMessage(fixture.engine(), fixture.cid(), "c1");
            assertEquals(MessageStatus.FAILED, tool.getStatus());
            assertTrue(tool.getContent().contains("生产环境禁止执行"),
                    "拒绝原因应透传给模型，实际: " + tool.getContent());
        }
    }

    /**
     * 工具返回标准错误契约（顶层含 error）应判为失败；纯文本结果应判为成功
     */
    @Test
    void classifiesToolResultByErrorContract() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder()
                .tool(FakeTool.builder("err_tool").executeResult("{\"error\":\"磁盘已满\"}").build())
                .tool(FakeTool.builder("ok_tool").executeResult("执行成功的纯文本结果").build())
                .build()) {
            fixture.llm().enqueue(FakeLlmServer.Turn.text("")
                            .addToolCall("e1", "err_tool", "{}")
                            .addToolCall("e2", "ok_tool", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("两个结果都收到。"));

            fixture.submitAndAwait("验证结果契约判定");

            assertEquals(MessageStatus.FAILED,
                    EngineTestHarness.toolMessage(fixture.engine(), fixture.cid(), "e1").getStatus(),
                    "顶层含 error 字段应判为失败");
            assertEquals(MessageStatus.SUCCESS,
                    EngineTestHarness.toolMessage(fixture.engine(), fixture.cid(), "e2").getStatus(),
                    "纯文本结果不受 error 字符串嗅探影响，应判为成功");
        }
    }

    /**
     * 不可用工具（上下文判定）应被拦截为失败
     */
    @Test
    void rejectsUnavailableTool() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder()
                .tool(FakeTool.builder("hidden_tool").available(false).build())
                .build()) {
            fixture.llm().enqueue(FakeLlmServer.Turn.toolCall("c1", "hidden_tool", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("知道了。"));

            fixture.submitAndAwait("调用不可用工具");

            Message tool = EngineTestHarness.toolMessage(fixture.engine(), fixture.cid(), "c1");
            assertEquals(MessageStatus.FAILED, tool.getStatus());
            assertTrue(tool.getContent().contains("不可用"), "实际: " + tool.getContent());
        }
    }

    /**
     * 工具调用前置 Hook 抛异常应阻断执行（工具绝不能被真正调用）
     */
    @Test
    void blocksToolExecutionByPreHook() throws IOException {
        FakeTool guarded = FakeTool.builder("hooked_tool").build();
        HookListener blockingHook = (type, payload, context) -> {
            if (type == HookType.ON_TOOL_CALL) {
                throw new IllegalStateException("本地规则禁止该操作");
            }
        };

        try (EngineFixture fixture = EngineFixture.builder()
                .tool(guarded)
                .hookListener(blockingHook)
                .build()) {
            fixture.llm().enqueue(FakeLlmServer.Turn.toolCall("c1", "hooked_tool", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("已被拦截。"));

            fixture.submitAndAwait("触发前置 Hook 阻断");

            Message tool = EngineTestHarness.toolMessage(fixture.engine(), fixture.cid(), "c1");
            assertEquals(MessageStatus.FAILED, tool.getStatus());
            assertTrue(tool.getContent().contains("生命周期 Hook 拦截阻断"),
                    "实际: " + tool.getContent());
            assertEquals(0, guarded.getExecuteCount(), "被阻断的工具绝不能真正执行");
        }
    }

    /**
     * 工具完成后置 Hook 抛异常应把成功结果改写为失败（激发模型自我修复）
     */
    @Test
    void rewritesToolResultByPostHook() throws IOException {
        HookListener postHook = (type, payload, context) -> {
            if (type == HookType.ON_TOOL_COMPLETE) {
                throw new IllegalStateException("返回值校验不通过");
            }
        };

        try (EngineFixture fixture = EngineFixture.builder()
                .tool(FakeTool.builder("checked_tool").executeResult("ok").build())
                .hookListener(postHook)
                .build()) {
            fixture.llm().enqueue(FakeLlmServer.Turn.toolCall("c1", "checked_tool", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("收到改写后的错误。"));

            fixture.submitAndAwait("触发后置 Hook 改写");

            Message tool = EngineTestHarness.toolMessage(fixture.engine(), fixture.cid(), "c1");
            assertEquals(MessageStatus.FAILED, tool.getStatus(), "后置校验失败应改写为失败");
            assertTrue(tool.getContent().contains("返回值校验不通过"), "实际: " + tool.getContent());
        }
    }

    // ──────────────────────────────────────────────
    // 人在回路审批
    // ──────────────────────────────────────────────

    /**
     * 守卫返回 ASK 时应挂起为 WAITING_APPROVAL，且不得推进下一轮推理
     */
    @Test
    void suspendsLoopOnApprovalRequired() throws IOException {
        FakeTool sensitive = FakeTool.builder("sensitive_tool")
                .accessLevel(ToolAccessLevel.SENSITIVE).executeResult("敏感操作已完成").build();

        try (EngineFixture fixture = EngineFixture.builder().tool(sensitive).build()) {
            fixture.guard().alwaysReturn(ToolPermissionVerdict.ask());
            fixture.llm()
                    .enqueue(FakeLlmServer.Turn.toolCall("c1", "sensitive_tool", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("审批后的收尾。"));

            int baseline = EngineTestHarness.allMessages(fixture.engine(), fixture.cid()).size();
            fixture.submit("需要审批的调用");
            EngineTestHarness.awaitToolStatus(fixture.engine(), fixture.cid(), "c1",
                    MessageStatus.WAITING_APPROVAL);

            assertEquals(MessageStatus.WAITING_APPROVAL,
                    EngineTestHarness.toolMessage(fixture.engine(), fixture.cid(), "c1").getStatus());
            assertEquals(0, sensitive.getExecuteCount(), "待审批期间工具绝不能被执行");
            assertEquals(1, fixture.llm().requestCount(),
                    "挂起期间不得发起下一轮 LLM 推理（否则会读出未执行的工具结果）");

            // 收尾：放行以免用例留下挂起状态
            fixture.guard().alwaysReturn(ToolPermissionVerdict.allow());
            assertTrue(fixture.engine().getToolFacade().approveTool(
                    approval(fixture.cid(), "c1", "sensitive_tool", "ALLOW")));
            EngineTestHarness.awaitCondition(() -> EngineTestHarness.isLoopIdle(fixture.engine(), fixture.cid())
                            && EngineTestHarness.allMessages(fixture.engine(), fixture.cid()).size() > baseline + 2,
                    "审批通过后循环继续并收尾");
        }
    }

    /**
     * 审批通过后应恢复执行、落到成功终态，并拉起下一轮推理
     */
    @Test
    void resumesExecutionAfterApproval() throws IOException {
        FakeTool sensitive = FakeTool.builder("sensitive_tool")
                .accessLevel(ToolAccessLevel.SENSITIVE).executeResult("敏感操作已完成").build();

        try (EngineFixture fixture = EngineFixture.builder().tool(sensitive).build()) {
            fixture.guard().alwaysReturn(ToolPermissionVerdict.ask());
            fixture.llm()
                    .enqueue(FakeLlmServer.Turn.toolCall("c1", "sensitive_tool", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("审批后的收尾。"));

            fixture.submit("需要审批的调用");
            EngineTestHarness.awaitToolStatus(fixture.engine(), fixture.cid(), "c1",
                    MessageStatus.WAITING_APPROVAL);

            assertTrue(fixture.engine().getToolFacade().approveTool(
                    approval(fixture.cid(), "c1", "sensitive_tool", "ALLOW")));

            EngineTestHarness.awaitCondition(() -> fixture.llm().requestCount() >= 2,
                    "审批通过后引擎应拉起下一轮推理");

            Message tool = EngineTestHarness.toolMessage(fixture.engine(), fixture.cid(), "c1");
            assertEquals(MessageStatus.SUCCESS, tool.getStatus(), "审批通过后工具应成功执行");
            assertEquals("敏感操作已完成", tool.getContent());
            assertEquals(1, sensitive.getExecuteCount());

            Message lastAssistant = EngineTestHarness.lastMessageOfRole(fixture.engine(),
                    fixture.cid(), MessageRole.ASSISTANT);
            assertEquals("审批后的收尾。", lastAssistant.getContent());
        }
    }

    /**
     * 审批拒绝应将工具置为 REJECTED，并同样拉起下一轮让模型知晓拒绝结果
     */
    @Test
    void continuesAfterApprovalDenied() throws IOException {
        FakeTool sensitive = FakeTool.builder("sensitive_tool")
                .accessLevel(ToolAccessLevel.SENSITIVE).build();

        try (EngineFixture fixture = EngineFixture.builder().tool(sensitive).build()) {
            fixture.guard().alwaysReturn(ToolPermissionVerdict.ask());
            fixture.llm()
                    .enqueue(FakeLlmServer.Turn.toolCall("c1", "sensitive_tool", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("用户拒绝了，我换个方式。"));

            fixture.submit("需要审批的调用");
            EngineTestHarness.awaitToolStatus(fixture.engine(), fixture.cid(), "c1",
                    MessageStatus.WAITING_APPROVAL);

            assertTrue(fixture.engine().getToolFacade().approveTool(
                    approval(fixture.cid(), "c1", "sensitive_tool", "DENY")));

            EngineTestHarness.awaitCondition(() -> fixture.llm().requestCount() >= 2,
                    "拒绝后引擎应拉起下一轮推理");

            Message tool = EngineTestHarness.toolMessage(fixture.engine(), fixture.cid(), "c1");
            assertEquals(MessageStatus.REJECTED, tool.getStatus());
            assertEquals(0, sensitive.getExecuteCount(), "被拒绝的工具绝不能执行");
        }
    }

    /**
     * 僵尸审批：工具已不在待审批态时，审批请求必须被拒绝且不产生任何副作用
     */
    @Test
    void rejectsStaleApprovalRequest() throws IOException {
        FakeTool normal = FakeTool.readOnly("normal_tool");
        try (EngineFixture fixture = EngineFixture.builder().tool(normal).build()) {
            fixture.llm().enqueue(FakeLlmServer.Turn.toolCall("c1", "normal_tool", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("已完成。"));

            fixture.submitAndAwait("正常执行完毕");

            // 工具此刻已是 SUCCESS 终态，补一次审批应被状态校验拦下
            assertFalse(fixture.engine().getToolFacade().approveTool(
                            approval(fixture.cid(), "c1", "normal_tool", "ALLOW")),
                    "非待审批状态的工具不得被事后审批");
            assertEquals(1, normal.getExecuteCount(), "僵尸审批不得导致工具被重复执行");
        }
    }

    /**
     * 审批参数非法（缺 cid / toolCallId）应安全拒绝而不抛异常
     */
    @Test
    void rejectsMalformedApprovalRequest() throws IOException {
        try (EngineFixture fixture = EngineFixture.builder().build()) {
            fixture.llm().enqueue(FakeLlmServer.Turn.text("ok"));
            fixture.submitAndAwait("准备环境");

            assertFalse(fixture.engine().getToolFacade().approveTool(
                    ToolApprovalRequest.builder().cid(fixture.cid()).decision("ALLOW").build()));
            assertFalse(fixture.engine().getToolFacade().approveTool(null));
        }
    }

    /**
     * 审批时改写参数：应以用户提供的参数替代模型原始参数执行
     */
    @Test
    void appliesArgumentOverrideOnApproval() throws IOException {
        FakeTool sensitive = FakeTool.builder("sensitive_tool")
                .accessLevel(ToolAccessLevel.SENSITIVE).build();

        try (EngineFixture fixture = EngineFixture.builder().tool(sensitive).build()) {
            fixture.guard().alwaysReturn(ToolPermissionVerdict.ask());
            fixture.llm()
                    .enqueue(FakeLlmServer.Turn.toolCall("c1", "sensitive_tool", "{\"text\":\"原始参数\"}"))
                    .enqueue(FakeLlmServer.Turn.text("已按你的参数执行。"));

            fixture.submit("需要审批的调用");
            EngineTestHarness.awaitToolStatus(fixture.engine(), fixture.cid(), "c1",
                    MessageStatus.WAITING_APPROVAL);

            assertTrue(fixture.engine().getToolFacade().approveTool(ToolApprovalRequest.builder()
                    .cid(fixture.cid())
                    .toolCallId("c1")
                    .toolName("sensitive_tool")
                    .decision("ALLOW")
                    .customArgOverride("{\"text\":\"人工改写后的参数\"}")
                    .build()));

            EngineTestHarness.awaitCondition(() -> fixture.llm().requestCount() >= 2,
                    "审批后循环继续");

            Message tool = EngineTestHarness.toolMessage(fixture.engine(), fixture.cid(), "c1");
            assertEquals(MessageStatus.SUCCESS, tool.getStatus());
            assertTrue(tool.getToolCalls().contains("人工改写后的参数"),
                    "改写后的参数应被写回工具调用记录，实际: " + tool.getToolCalls());
        }
    }

    /**
     * 「总是放行」勾选时应回调宿主写入授信规则
     */
    @Test
    void writesAllowRuleOnAlwaysAllow() throws IOException {
        List<String> writtenRules = Collections.synchronizedList(new ArrayList<>());
        FakeTool sensitive = FakeTool.builder("sensitive_tool")
                .accessLevel(ToolAccessLevel.SENSITIVE).build();

        try (EngineFixture fixture = EngineFixture.builder()
                .tool(sensitive)
                .approvalRuleWriter((toolName, pattern, workspaceId) ->
                        writtenRules.add(toolName + "|" + pattern + "|" + workspaceId))
                .build()) {
            fixture.guard().alwaysReturn(ToolPermissionVerdict.ask());
            fixture.llm()
                    .enqueue(FakeLlmServer.Turn.toolCall("c1", "sensitive_tool", "{}"))
                    .enqueue(FakeLlmServer.Turn.text("以后都放行了。"));

            fixture.submit("总是放行");
            EngineTestHarness.awaitToolStatus(fixture.engine(), fixture.cid(), "c1",
                    MessageStatus.WAITING_APPROVAL);

            assertTrue(fixture.engine().getToolFacade().approveTool(ToolApprovalRequest.builder()
                    .cid(fixture.cid())
                    .toolCallId("c1")
                    .toolName("sensitive_tool")
                    .decision("ALLOW")
                    .alwaysAllow(true)
                    .contentPattern("sensitive_tool")
                    .build()));

            EngineTestHarness.awaitCondition(() -> !writtenRules.isEmpty(), "授信规则应被写入");
            assertEquals(1, writtenRules.size());
            assertTrue(writtenRules.get(0).startsWith("sensitive_tool|sensitive_tool|"),
                    "规则应携带工具名、匹配模式与工作区，实际: " + writtenRules.get(0));
        }
    }
}
