package com.stioc.cute.message;

import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.message.types.LimitMessageDto;
import com.stioc.cute.message.types.MessageVo;
import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.runtime.store.AbstractStoreIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 折叠算法（R1~R4）后端契约测试。
 * <p>
 * 该算法在 {@link MessageService} 中明文标注「与前端 foldEngine.ts 严格对齐，禁止单侧变更」，
 * 属事实上的跨端契约，此前后端侧零回归保护。本测试以真实 SQLite 落库驱动
 * {@link MessageService#getConversationMessages} 端到端运行，钉住四条规则与 FOLDED 产物的完整语义。
 * </p>
 * <p>
 * 覆盖方式为黑盒：只经公开入口 assert 输出，不触碰私有实现，因此两段式查询（轻查询边界判定
 * + 全字段回捞）的任何重构都不会让用例失真。
 * </p>
 */
class MessageServiceTest extends AbstractStoreIntegrationTest {

    private static final Long CID = 9001L;

    private MessageService messageService;

    @BeforeEach
    void setUpService() {
        messageService = new MessageService();
        ReflectionTestUtils.setField(messageService, "messageStore", messageStore);
        ContractProperty contractProperty = new ContractProperty();
        ReflectionTestUtils.setField(messageService, "contractProperty", contractProperty);
    }

    // ──────────────────────────────────────────────
    // 消息落库辅助
    // ──────────────────────────────────────────────

    /**
     * 插入一条消息并回填自增 id（父引用需在插入后回填，故按需传 parent）
     */
    private Message insert(MessageRole role, MessageStatus status, String content, Long parentId) {
        Message m = Message.builder()
                .cid(CID)
                .role(role)
                .status(status)
                .content(content)
                .reasoningContent("")
                .visibleToUser(true)
                .visibleToModel(true)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .parentMessageId(parentId)
                .build();
        messageStore.insert(m);
        return m;
    }

    private Message user(String content) {
        return insert(MessageRole.USER, MessageStatus.SUCCESS, content, null);
    }

    private Message assistant(MessageStatus status, String content) {
        return insert(MessageRole.ASSISTANT, status, content, null);
    }

    /**
     * 插入一条工具消息（toolCalls 用 JSON 承载工具名，供 errorDetails 解析）
     */
    private Message tool(Long parentId, MessageStatus status, String toolCallId, String toolName) {
        Message m = Message.builder()
                .cid(CID)
                .role(MessageRole.TOOL)
                .status(status)
                .content("工具输出")
                .reasoningContent("")
                .toolCalls("{\"id\":\"" + toolCallId + "\",\"name\":\"" + toolName + "\",\"arguments\":\"{}\"}")
                .callId(toolCallId)
                .visibleToUser(true)
                .visibleToModel(true)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .parentMessageId(parentId)
                .build();
        messageStore.insert(m);
        return m;
    }

    /**
     * 执行折叠查询
     */
    private List<MessageVo> folded() {
        LimitMessageDto dto = messageService.getConversationMessages(CID, true, null, null);
        return dto.getMessages();
    }

    /**
     * 扁平查询（folded=false）
     */
    private List<MessageVo> flat() {
        LimitMessageDto dto = messageService.getConversationMessages(CID, false, null, null);
        return dto.getMessages();
    }

    private MessageVo firstFold(List<MessageVo> vos) {
        return vos.stream().filter(v -> MessageRole.FOLDED == v.getRole()).findFirst().orElse(null);
    }

    private long countFold(List<MessageVo> vos) {
        return vos.stream().filter(v -> MessageRole.FOLDED == v.getRole()).count();
    }

    // ──────────────────────────────────────────────
    // R1 原子小组与过滤
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("R1 预处理：原子小组与五无助手过滤")
    class R1Tests {

        @Test
        @DisplayName("工具消息按 parentMessageId 归入父助手，不在列表顶层单独出现")
        void toolNestsIntoParentAssistant() {
            user("问题");
            Message a1 = assistant(MessageStatus.SUCCESS, "回答一");
            tool(a1.getId(), MessageStatus.SUCCESS, "call_1", "read_file");
            assistant(MessageStatus.SUCCESS, "最终回答");

            List<MessageVo> vos = folded();

            // 工具消息不应作为独立条目出现在输出中（已被并入小组随助手一起折叠或外露）
            assertTrue(vos.stream().noneMatch(v -> MessageRole.TOOL == v.getRole()),
                    "折叠视图下工具消息应随小组归并，不应顶层平铺");
        }

        @Test
        @DisplayName("五无助手（无正文无思考 + SUCCESS + 名下无工具）在外露路径被过滤")
        void blankAssistantIsFiltered() {
            user("问题");
            // 唯一助手且为终态：无折叠前缀（foldEnd<=0 全段外露），必然走外露路径，
            // 从而精确命中 appendExposedGroup 里的五无过滤逻辑
            assistant(MessageStatus.SUCCESS, "");

            List<MessageVo> vos = folded();
            assertTrue(vos.stream().noneMatch(v -> MessageRole.ASSISTANT == v.getRole()),
                    "空助手应在外露路径被过滤，不出现在输出中");
            assertEquals(1, vos.size(), "过滤后应仅剩 USER 一条");
        }

        @Test
        @DisplayName("平铺视图不做五无过滤（原样映射语义，供折叠详情完整回显）")
        void flatViewKeepsBlankAssistant() {
            user("问题");
            assistant(MessageStatus.SUCCESS, "");
            assistant(MessageStatus.SUCCESS, "有效回答");

            List<MessageVo> vos = flat();
            // 平铺视图为「兼容旧调用语义 + 折叠详情明细回显」，按 id 升序原样映射，不施加折叠期过滤
            assertEquals(3, vos.size(), "平铺视图应原样返回 USER + 两条助手（含空助手）");
        }

        @Test
        @DisplayName("纯工具调用助手（有 TOOL）不被五无过滤")
        void pureToolAssistantIsNotFiltered() {
            user("问题");
            Message a1 = assistant(MessageStatus.SUCCESS, "");
            tool(a1.getId(), MessageStatus.SUCCESS, "call_1", "read_file");

            List<MessageVo> vos = folded();

            // 唯一助手为终态且名下无折叠前缀：必然外露。
            // 若五无过滤误判（把纯工具助手当空助手丢弃），助手与工具都会消失，故断言其必须存在
            assertTrue(vos.stream().anyMatch(v -> MessageRole.ASSISTANT == v.getRole()),
                    "名下带工具的助手不得被五无过滤丢弃");
            assertTrue(vos.stream().anyMatch(v -> MessageRole.TOOL == v.getRole()),
                    "外露小组应连带其工具消息");
        }

        @Test
        @DisplayName("SYSTEM 消息不参与折叠视图输出")
        void systemMessageSkipped() {
            insert(MessageRole.SYSTEM, MessageStatus.SUCCESS, "系统提示", null);
            user("问题");
            assistant(MessageStatus.SUCCESS, "回答");

            List<MessageVo> vos = folded();
            assertTrue(vos.stream().noneMatch(v -> MessageRole.SYSTEM == v.getRole()),
                    "SYSTEM 不应出现在折叠视图");
        }
    }

    // ──────────────────────────────────────────────
    // R2 分段
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("R2 分段：按 USER 切段，段内独立折叠")
    class R2Tests {

        @Test
        @DisplayName("两个 USER 段落各自独立折叠，产生两个折叠块")
        void eachUserSegmentFoldsIndependently() {
            // 第一段：助手A1 + 终态A2（A1 应折叠，A2 外露）
            user("第一问");
            assistant(MessageStatus.SUCCESS, "A1");
            assistant(MessageStatus.SUCCESS, "A2");
            // 第二段：助手B1 + 终态B2
            user("第二问");
            assistant(MessageStatus.SUCCESS, "B1");
            assistant(MessageStatus.SUCCESS, "B2");

            List<MessageVo> vos = folded();

            assertEquals(2, countFold(vos), "两个 USER 段落各应产生一个折叠块");
            // 两个 USER 均应外露
            assertEquals(2, vos.stream().filter(v -> MessageRole.USER == v.getRole()).count(),
                    "USER 消息应外露作为段头");
            // 每段最后一条终态助手外露
            assertTrue(vos.stream().anyMatch(v -> "A2".equals(v.getContent())), "第一段终态助手应外露");
            assertTrue(vos.stream().anyMatch(v -> "B2".equals(v.getContent())), "第二段终态助手应外露");
        }

        @Test
        @DisplayName("BRANCH / COMPRESSED 不切段，按助手角色参与终态判定")
        void branchAndCompressedDoNotSplitSegment() {
            user("问题");
            insert(MessageRole.BRANCH, MessageStatus.SUCCESS, "子代理汇报", null);
            assistant(MessageStatus.SUCCESS, "收尾回答");

            List<MessageVo> vos = folded();

            // BRANCH 不切段：全列表仅一个 USER 段。段内 BRANCH 与 ASSISTANT 均为终态助手，
            // R4 取最后一条（ASSISTANT），故 BRANCH 归入折叠块、ASSISTANT 外露
            assertEquals(1, countFold(vos), "BRANCH 所在段应只产生一个折叠块（未被切成两段）");
            assertEquals(MessageRole.FOLDED, vos.get(1).getRole(),
                    "BRANCH 应折叠于 USER 之后，而非作为段头外露");
            assertTrue(vos.stream().anyMatch(v -> "收尾回答".equals(v.getContent())),
                    "最后一条终态助手应外露");
        }
    }

    // ──────────────────────────────────────────────
    // R3 待审批
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("R3 待审批：首个待审批小组起全部外露")
    class R3Tests {

        @Test
        @DisplayName("首个含 WAITING_APPROVAL 的小组之前折叠，其自身及之后外露")
        void exposesFromFirstWaitingApprovalOnwards() {
            user("问题");
            assistant(MessageStatus.SUCCESS, "早期步骤");     // 应折叠
            Message a2 = assistant(MessageStatus.SUCCESS, "申请命令"); // 其工具待审批 → 外露
            tool(a2.getId(), MessageStatus.WAITING_APPROVAL, "call_1", "execute_command");
            assistant(MessageStatus.RUNNING, "后续运行中");

            List<MessageVo> vos = folded();

            MessageVo foldVo = firstFold(vos);
            assertNotNull(foldVo, "首个待审批之前的已完成步骤应折叠");
            assertTrue(foldVo.getToolCount() != null, "折叠块应携带计数");
            // 待审批工具所在小组必须外露（助手与工具均可见）
            assertTrue(vos.stream().anyMatch(v -> "申请命令".equals(v.getContent())),
                    "待审批所在小组的助手应外露，保证审批卡片可见");
            assertTrue(vos.stream().anyMatch(v -> MessageStatus.WAITING_APPROVAL == v.getStatus()),
                    "待审批工具应外露");
        }

        @Test
        @DisplayName("助手自身 WAITING_APPROVAL 同样触发该小组起外露")
        void assistantItselfWaitingApprovalAlsoExposes() {
            user("问题");
            assistant(MessageStatus.SUCCESS, "早期步骤");
            assistant(MessageStatus.WAITING_APPROVAL, "等待审批的助手");

            List<MessageVo> vos = folded();

            assertNotNull(firstFold(vos), "早期步骤应折叠");
            assertTrue(vos.stream().anyMatch(v -> MessageStatus.WAITING_APPROVAL == v.getStatus()),
                    "待审批助手应外露");
        }
    }

    // ──────────────────────────────────────────────
    // R4 终态
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("R4 终态：最后一条终态助手小组外露")
    class R4Tests {

        @Test
        @DisplayName("最后终态助手之前的小组折叠，自身外露")
        void foldsBeforeLastTerminalAssistant() {
            user("问题");
            assistant(MessageStatus.SUCCESS, "步骤一");
            assistant(MessageStatus.SUCCESS, "步骤二");
            assistant(MessageStatus.SUCCESS, "最终答案");

            List<MessageVo> vos = folded();

            MessageVo foldVo = firstFold(vos);
            assertNotNull(foldVo, "终态之前应有折叠块");
            assertEquals(2, foldVo.getAssistantCount().intValue(), "前两个助手应折叠");
            assertTrue(vos.stream().anyMatch(v -> "最终答案".equals(v.getContent())),
                    "最后终态助手应外露");
        }

        @Test
        @DisplayName("段内无终态助手时全段外露，不产生折叠块")
        void noTerminalMeansAllExposed() {
            user("问题");
            assistant(MessageStatus.RUNNING, "运行中一");
            assistant(MessageStatus.RUNNING, "运行中二");

            List<MessageVo> vos = folded();

            assertEquals(0, countFold(vos), "无终态助手时不应折叠");
        }

        @Test
        @DisplayName("待审批阶段无终态助手：待审批之前的小组仍按 R3 折叠")
        void waitingApprovalFoldsEvenWithoutTerminal() {
            user("问题");
            assistant(MessageStatus.RUNNING, "运行中");
            Message a2 = assistant(MessageStatus.SUCCESS, "申请");
            tool(a2.getId(), MessageStatus.WAITING_APPROVAL, "call_1", "write_file");

            List<MessageVo> vos = folded();

            assertNotNull(firstFold(vos), "R3 优先级高于 R4，待审批前的小组应折叠");
        }
    }

    // ──────────────────────────────────────────────
    // FOLDED 产物
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("FOLDED 虚拟消息产物：区间、计数与异常明细")
    class FoldedVoTests {

        @Test
        @DisplayName("折叠块携带正确的最小/最大 ID 与助手、工具计数")
        void foldedCarriesRangeAndCounts() {
            user("问题");
            Message a1 = assistant(MessageStatus.SUCCESS, "步骤一");
            tool(a1.getId(), MessageStatus.SUCCESS, "call_1", "read_file");
            Message a2 = assistant(MessageStatus.SUCCESS, "步骤二");
            tool(a2.getId(), MessageStatus.SUCCESS, "call_2", "grep_search");
            assistant(MessageStatus.SUCCESS, "最终答案");

            List<MessageVo> vos = folded();
            MessageVo foldVo = firstFold(vos);

            assertNotNull(foldVo, "应产生折叠块");
            assertEquals(2, foldVo.getAssistantCount().intValue(), "折叠的助手数应为 2");
            assertEquals(2, foldVo.getToolCount().intValue(), "折叠的工具数应为 2");
            assertEquals(a1.getId(), foldVo.getFoldedMinId(), "折叠区间下界应为第一个折叠消息 id");
            assertEquals(a2.getId() + 1, foldVo.getFoldedMaxId(), "折叠区间上界应为最后一条工具 id");
            assertEquals(foldVo.getFoldedMinId(), foldVo.getId(), "FOLDED 的 id 应取区间最小 id 作为稳定 key");
            assertNull(foldVo.getErrorDetails(), "无失败时应无异常明细");
        }

        @Test
        @DisplayName("折叠区内的 FAILED 工具解析出工具名与异常明细")
        void failedToolProducesErrorDetailWithName() {
            user("问题");
            Message a1 = assistant(MessageStatus.SUCCESS, "步骤一");
            tool(a1.getId(), MessageStatus.FAILED, "call_1", "execute_command");
            assistant(MessageStatus.SUCCESS, "最终答案");

            List<MessageVo> vos = folded();
            MessageVo foldVo = firstFold(vos);

            assertNotNull(foldVo, "应产生折叠块");
            assertNotNull(foldVo.getErrorDetails(), "含失败工具时应有异常明细");
            assertEquals(1, foldVo.getErrorDetails().size(), "应有一条异常明细");
            assertEquals("tool", foldVo.getErrorDetails().getFirst().getKind());
            assertEquals("execute_command", foldVo.getErrorDetails().getFirst().getToolName(),
                    "工具名应从 toolCalls 解析得出，供前端映射展示");
        }

        @Test
        @DisplayName("折叠区内的 FAILED 助手产生 assistant 类型异常明细")
        void failedAssistantProducesAssistantErrorDetail() {
            user("问题");
            assistant(MessageStatus.FAILED, "失败的步骤");
            assistant(MessageStatus.SUCCESS, "最终答案");

            List<MessageVo> vos = folded();
            MessageVo foldVo = firstFold(vos);

            assertNotNull(foldVo, "应产生折叠块");
            assertNotNull(foldVo.getErrorDetails(), "含失败助手时应有异常明细");
            assertEquals("assistant", foldVo.getErrorDetails().getFirst().getKind());
        }

        @Test
        @DisplayName("FOLDED 保持在后端下发的原始位置（不堆到列表顶部）")
        void foldedKeepsOriginalPosition() {
            user("第一问");
            assistant(MessageStatus.SUCCESS, "A1");
            assistant(MessageStatus.SUCCESS, "A2");
            user("第二问");
            assistant(MessageStatus.SUCCESS, "B1");
            assistant(MessageStatus.SUCCESS, "B2");

            List<MessageVo> vos = folded();

            // 结构应为：[USER1, FOLDED, A2, USER2, FOLDED, B2]
            assertEquals(MessageRole.USER, vos.get(0).getRole(), "首位应为第一段 USER");
            assertEquals(MessageRole.FOLDED, vos.get(1).getRole(), "折叠块应紧随其段头 USER 之后");
            long firstUserIdx = vos.indexOf(vos.stream().filter(v -> "第一问".equals(v.getContent())).findFirst().orElseThrow());
            long secondUserIdx = vos.indexOf(vos.stream().filter(v -> "第二问".equals(v.getContent())).findFirst().orElseThrow());
            long firstFoldIdx = vos.indexOf(firstFold(vos));
            assertTrue(firstFoldIdx > firstUserIdx && firstFoldIdx < secondUserIdx,
                    "第一段折叠块应位于两段 USER 之间，而非堆在列表顶部");
        }
    }

    // ──────────────────────────────────────────────
    // 两段式查询一致性
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("两段式查询一致性：折叠视图与平铺视图不漂移")
    class TwoPhaseConsistencyTests {

        @Test
        @DisplayName("折叠视图所外露的助手内容与平铺视图完全一致")
        void exposedAssistantsMatchFlatView() {
            user("问题");
            assistant(MessageStatus.SUCCESS, "步骤一");
            Message a2 = assistant(MessageStatus.SUCCESS, "步骤二");
            tool(a2.getId(), MessageStatus.SUCCESS, "call_1", "read_file");
            assistant(MessageStatus.RUNNING, "运行中");

            List<MessageVo> foldVos = folded();
            List<MessageVo> flatVos = flat();

            // 折叠视图中所有外露的助手（非 FOLDED）都应能在平铺视图中找到同 id 同内容
            for (MessageVo fv : foldVos) {
                if (MessageRole.ASSISTANT != fv.getRole()) {
                    continue;
                }
                MessageVo match = flatVos.stream()
                        .filter(v -> fv.getId() != null && fv.getId().equals(v.getId()))
                        .findFirst().orElse(null);
                assertNotNull(match, "折叠视图外露的助手 id=" + fv.getId() + " 应存在于平铺视图（防轻/重回捞口径漂移）");
                assertEquals(match.getContent(), fv.getContent(), "同 id 消息内容必须一致");
            }
        }

        @Test
        @DisplayName("范围查询（folded=false + minId/maxId）返回区间内平铺明细")
        void rangeQueryReturnsFlatDetail() {
            user("问题");
            Message a1 = assistant(MessageStatus.SUCCESS, "步骤一");
            Message t1 = tool(a1.getId(), MessageStatus.SUCCESS, "call_1", "read_file");
            assistant(MessageStatus.SUCCESS, "最终答案");

            LimitMessageDto dto = messageService.getConversationMessages(CID, false, a1.getId(), t1.getId());

            assertFalse(dto.isTruncated(), "范围查询不做截断");
            assertEquals(2, dto.getMessages().size(), "区间内应返回助手与工具两条明细");
            assertTrue(dto.getMessages().stream().anyMatch(v -> MessageRole.TOOL == v.getRole()),
                    "折叠详情范围查询应平铺包含工具消息");
        }
    }

    // ──────────────────────────────────────────────
    // 边界
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("边界与防御")
    class BoundaryTests {

        @Test
        @DisplayName("空会话返回空列表且不报错")
        void emptyConversationReturnsEmpty() {
            List<MessageVo> vos = folded();
            assertTrue(vos.isEmpty(), "无消息时应返回空列表");
        }

        @Test
        @DisplayName("仅单条 USER 时不产生折叠块")
        void singleUserProducesNoFold() {
            user("孤零零一问");
            List<MessageVo> vos = folded();
            assertEquals(0, countFold(vos), "无助手可折叠时不应产生折叠块");
            assertEquals(1, vos.size(), "应仅返回该 USER 消息");
        }

        @Test
        @DisplayName("孤儿工具（父助手缺失）独立成组，不导致异常")
        void orphanToolDoesNotBreakFolding() {
            user("问题");
            // 父 id 指向不存在的助手
            tool(999999L, MessageStatus.SUCCESS, "orphan_1", "read_file");
            assistant(MessageStatus.SUCCESS, "最终答案");

            List<MessageVo> vos = folded();
            assertNotNull(vos, "孤儿工具不应导致折叠异常");
        }
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
