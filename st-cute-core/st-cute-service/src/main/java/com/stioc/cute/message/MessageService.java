package com.stioc.cute.message;

import com.stioc.cute.message.types.MessageVo;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.types.SortDirection;
import com.stioc.cute.message.types.LimitMessageDto;
import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.engine.tool.ToolCallCodec;
import com.stioc.cute.engine.store.MessageStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.annotation.Resource;

/**
 * 消息处理与数据存取服务
 */
@Slf4j
@Service
public class MessageService {

    @Resource
    private MessageStore messageStore;
    @Resource
    private ContractProperty contractProperty;

    /**
     * 获取会话的消息列表（带最大数量限制及 USER 首发逻辑）。
     * folded=true 时按折叠算法返回；folded=false 且指定 minId/maxId 时为折叠详情范围查询（原始平铺）。
     *
     * 性能设计（两段式查询，避免长会话下大字段溢出页 I/O）：
     * 1) 折叠边界判定（R1~R4）只依赖小字段（id/parentMessageId/role/status），先做轻查询仅取小列，
     *    SQLite 对未 SELECT 的 TEXT 大字段（content/reasoningContent/toolCalls 等）不读取溢出页；
     * 2) 依据折叠计划仅对外露消息 + 折叠区内 FAILED 工具做第二次全字段 IN 回捞。
     */
    public LimitMessageDto getConversationMessages(Long cid, boolean folded, Long minId, Long maxId) {
        int limit = contractProperty.getMaxViewHistoryLimit();
        if (limit <= 0) {
            limit = 2000;
        }

        // 折叠详情范围查询：闭区间平铺返回，不做 limit 截断（区间本身即折叠块边界，天然有界）。
        // 但 minId/maxId 来自 HTTP 参数，调用方可能传入极宽区间（如 minId=1&maxId=Long.MAX_VALUE）
        // 从而绕过 maxViewHistoryLimit 全量拉取。故对区间跨度设硬上限，超限则按 id 升序截取前 N 条
        boolean rangeQuery = !folded && minId != null && maxId != null;
        if (rangeQuery) {
            long span = maxId - minId;
            if (span > limit) {
                log.warn("折叠详情范围查询跨度 {} 超过上限 {}，已截断至区间前 {} 条: cid={}, minId={}, maxId={}",
                        span, limit, limit, cid, minId, maxId);
                maxId = minId + limit;
            }
            MessageQuery query = MessageQuery.builder()
                    .cid(cid)
                    .minId(minId)
                    .maxId(maxId)
                    .visibleToUser(true)
                    .sortField("id")
                    .sortDirection(SortDirection.ASC)
                    .limit(limit)
                    .build();
            List<Message> list = messageStore.listByQuery(query);
            List<MessageVo> dtos = new ArrayList<>();
            for (Message m : list) {
                if (MessageRole.SYSTEM == m.getRole()) {
                    continue;
                }
                dtos.add(MessageVo.fromEntity(m));
            }
            return new LimitMessageDto(dtos, false);
        }

        // ---------- 第一段：轻查询，仅取折叠边界判定所需小字段 ----------
        MessageQuery lightQuery = MessageQuery.builder()
                .cid(cid)
                .visibleToUser(true)
                .light(true)
                .sortField("id")
                .sortDirection(SortDirection.DESC)
                .limit(limit + 100)
                .build();
        List<Message> descList = messageStore.listByQuery(lightQuery);
        if (descList == null || descList.isEmpty()) {
            return new LimitMessageDto(new ArrayList<>(), false);
        }
        // 常规查询为倒序，反转为升序
        List<Message> lightList = new ArrayList<>(descList);
        Collections.reverse(lightList);

        int total = lightList.size();
        int startIndex = 0;
        boolean truncated = false;

        if (total > limit) {
            truncated = true;
            int targetIndex = total - limit;
            while (targetIndex >= 0) {
                Message m = lightList.get(targetIndex);
                if (MessageRole.USER == m.getRole()) {
                    startIndex = targetIndex;
                    break;
                }
                targetIndex--;
            }
            if (targetIndex < 0) {
                startIndex = 0;
            }
        }

        if (!folded) {
            // 平铺视图（未指定范围）：全字段回捞后按 id 升序原样映射（兼容旧调用语义）
            List<Long> allIds = new ArrayList<>();
            for (int i = startIndex; i < total; i++) {
                Message m = lightList.get(i);
                if (MessageRole.SYSTEM != m.getRole() && m.getId() != null) {
                    allIds.add(m.getId());
                }
            }
            List<MessageVo> dtos = new ArrayList<>();
            for (Message m : selectFullByIds(cid, allIds)) {
                dtos.add(MessageVo.fromEntity(m));
            }
            return new LimitMessageDto(dtos, truncated);
        }

        // ---------- 折叠视图：轻实体分组 → R1~R4 判定折叠计划 ----------
        List<FoldPlanEntry> plan = buildFoldPlan(lightList, startIndex, total);

        // ---------- 第二段：重查询，仅回捞外露消息 + 折叠区内 FAILED 工具 ----------
        Set<Long> fetchIds = new LinkedHashSet<>();
        for (FoldPlanEntry entry : plan) {
            if (entry.exposedGroup != null) {
                Message head = entry.exposedGroup.assistant;
                if (head != null && head.getId() != null) {
                    fetchIds.add(head.getId());
                }
                for (Message tool : entry.exposedGroup.tools) {
                    if (tool.getId() != null) {
                        fetchIds.add(tool.getId());
                    }
                }
            } else {
                // 折叠区仅需 FAILED 工具的全字段实体，用于解析 toolCalls 生成 errorDetails
                for (FoldGroup g : entry.foldedGroups) {
                    for (Message tool : g.tools) {
                        if (tool.getId() != null && MessageStatus.FAILED == tool.getStatus()) {
                            fetchIds.add(tool.getId());
                        }
                    }
                }
            }
        }
        Map<Long, Message> fullById = new LinkedHashMap<>();
        for (Message m : selectFullByIds(cid, fetchIds)) {
            if (m.getId() != null) {
                fullById.put(m.getId(), m);
            }
        }

        // ---------- 按计划渲染输出 ----------
        List<MessageVo> dtos = new ArrayList<>();
        for (FoldPlanEntry entry : plan) {
            if (entry.exposedGroup != null) {
                appendExposedGroup(dtos, entry.exposedGroup, fullById);
            } else {
                dtos.add(buildFoldedVo(entry.foldedGroups, fullById));
            }
        }
        return new LimitMessageDto(dtos, truncated);
    }

    /**
     * 按主键集合回捞全字段消息（限定会话归属，id 升序）。空集合返回空列表。
     */
    private List<Message> selectFullByIds(Long cid, Collection<Long> ids) {
        if (cid == null || ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(messageStore.listByQuery(MessageQuery.builder()
                .cid(cid)
                .ids(ids)
                .sortField("id")
                .sortDirection(SortDirection.ASC)
                .build()));
    }

    /**
     * 【折叠算法 - 与前端严格对齐】
     * 对齐锚：前端 st-cute-web/src/utils/foldEngine.ts -> buildFoldedView()（规则编号 R1~R4 双侧一致）
     * 修改本算法任一规则时，必须同步修改前端对齐锚实现，禁止单侧变更！
     *
     * R1 预处理：SYSTEM 跳过；TOOL 按 parentMessageId 并入父助手成原子小组（助手+其工具整体，永不拆开折叠）；
     *    空助手（无 content + 无 reasoningContent + SUCCESS + 名下无 TOOL 消息）跳过——
     *    纯工具调用助手（名下有 TOOL）不在跳过之列；无父孤儿工具独立成组，同样为原子小组
     * R2 分段：按 USER 消息切段，段内独立折叠；BRANCH/COMPRESSED 不切段，按助手角色参与终态判定
     * R3 待审批：首个含 WAITING_APPROVAL 的小组（助手自身或其任一工具待审批）起全部外露，之前的小组折叠
     * R4 终态：最后一条终态助手（SUCCESS/FAILED/CANCELED）小组外露，之前的小组折叠；段内无终态助手则全段外露
     *
     * 本方法基于轻实体（仅 id/parentMessageId/role/status 小字段）产出"折叠计划"：
     * 待外露小组 / 待折叠小组集合，全字段内容延后按需回捞（性能关键：轻查询不读大字段溢出页）。
     * 注意：R1 的"五无"助手过滤依赖 content/reasoningContent，轻实体阶段无法判定，
     * 统一延后到回捞后（appendExposedGroup）执行，保证过滤语义与旧实现完全一致。
     *
     * 折叠块产出 FOLDED 虚拟 MessageVo（id=foldedMinId，不落库），携带 foldedMinId/foldedMaxId/assistantCount/toolCount/errorDetails
     */
    private List<FoldPlanEntry> buildFoldPlan(List<Message> lightList, int startIndex, int total) {
        // ---------- R1 预处理：组装原子小组（基于轻实体） ----------
        List<FoldGroup> groups = new ArrayList<>();
        Map<Long, FoldGroup> groupByAssistantId = new LinkedHashMap<>();
        List<FoldGroup> orphanGroups = new ArrayList<>();

        for (int i = startIndex; i < total; i++) {
            Message m = lightList.get(i);
            if (MessageRole.SYSTEM == m.getRole()) {
                continue;
            }
            if (MessageRole.TOOL == m.getRole()) {
                // R1: TOOL 按 parentMessageId 归入父助手小组；找不到父则进孤儿组
                FoldGroup parent = m.getParentMessageId() != null ? groupByAssistantId.get(m.getParentMessageId()) : null;
                if (parent != null) {
                    parent.tools.add(m);
                } else {
                    FoldGroup orphan = new FoldGroup();
                    orphan.tools.add(m);
                    orphanGroups.add(orphan);
                }
                continue;
            }
            FoldGroup group = new FoldGroup();
            group.assistant = m;
            groups.add(group);
            if (m.getId() != null) {
                groupByAssistantId.put(m.getId(), group);
            }
        }
        // 孤儿组追加到末尾（与前端"孤儿工具兜底渲染在段尾"对齐）
        groups.addAll(orphanGroups);

        // ---------- R2 分段：按 USER 消息切段，R3/R4 判定折叠边界，产出计划 ----------
        List<FoldPlanEntry> plan = new ArrayList<>();
        int segStart = 0;
        while (segStart < groups.size()) {
            int segEnd = segStart + 1;
            while (segEnd < groups.size() && !isGroupUser(groups.get(segEnd))) {
                segEnd++;
            }
            emitSegmentPlan(plan, groups, segStart, segEnd);
            segStart = segEnd;
        }
        return plan;
    }

    /** 小组头是否为 USER 消息 */
    private boolean isGroupUser(FoldGroup group) {
        return group.assistant != null && MessageRole.USER == group.assistant.getRole();
    }

    /**
     * 处理单个分段：R3/R4 规则判定折叠边界并产出计划条目。
     * 段内结构：[USER 小组(可选)] + 助手小组序列
     */
    private void emitSegmentPlan(List<FoldPlanEntry> plan, List<FoldGroup> groups, int segStart, int segEnd) {
        int bodyStart = segStart;
        // 段头 USER 小组直接外露
        if (isGroupUser(groups.get(segStart))) {
            plan.add(FoldPlanEntry.exposed(groups.get(segStart)));
            bodyStart = segStart + 1;
        }
        if (bodyStart >= segEnd) {
            return;
        }

        List<FoldGroup> body = groups.subList(bodyStart, segEnd);

        // ---------- R3 待审批规则 ----------
        int firstWaitingIndex = -1;
        for (int k = 0; k < body.size(); k++) {
            if (isGroupWaitingApproval(body.get(k))) {
                firstWaitingIndex = k;
                break;
            }
        }

        int foldEnd; // 折叠边界（body 下标，不含）
        if (firstWaitingIndex != -1) {
            // R3: 首个待审批小组之前全部折叠，其自身及之后全部外露
            foldEnd = firstWaitingIndex;
        } else {
            // R4: 查找最后一条终态助手小组，其之前折叠、自身及之后外露；无终态则全外露
            foldEnd = -1;
            for (int k = body.size() - 1; k >= 0; k--) {
                if (isGroupTerminalAssistant(body.get(k))) {
                    foldEnd = k;
                    break;
                }
            }
        }

        if (foldEnd > 0) {
            // 折叠 [0, foldEnd) 的小组为 FOLDED 虚拟消息计划
            plan.add(FoldPlanEntry.folded(new ArrayList<>(body.subList(0, foldEnd))));
            // 外露 [foldEnd, body.size())
            for (int k = foldEnd; k < body.size(); k++) {
                plan.add(FoldPlanEntry.exposed(body.get(k)));
            }
        } else {
            // 无可折叠前缀（foldEnd<=0）：全段外露
            for (FoldGroup g : body) {
                plan.add(FoldPlanEntry.exposed(g));
            }
        }
    }

    /**
     * 小组是否处于待审批状态：助手自身 WAITING_APPROVAL 或其任一工具 WAITING_APPROVAL（R3 判定以小组为原子单位）
     */
    private boolean isGroupWaitingApproval(FoldGroup group) {
        if (group.assistant != null && MessageStatus.WAITING_APPROVAL == group.assistant.getStatus()) {
            return true;
        }
        for (Message tool : group.tools) {
            if (MessageStatus.WAITING_APPROVAL == tool.getStatus()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 小组是否为终态助手：助手角色（ASSISTANT/BRANCH/COMPRESSED）且状态为 SUCCESS/FAILED/CANCELED
     * （与前端 isTerminalAssistant 对齐）
     */
    private boolean isGroupTerminalAssistant(FoldGroup group) {
        if (group.assistant == null) {
            return false;
        }
        MessageRole role = group.assistant.getRole();
        if (role != MessageRole.ASSISTANT && role != MessageRole.BRANCH && role != MessageRole.COMPRESSED) {
            return false;
        }
        MessageStatus status = group.assistant.getStatus();
        return status == MessageStatus.SUCCESS || status == MessageStatus.FAILED || status == MessageStatus.CANCELED;
    }

    /**
     * 将外露小组渲染为 VO 输出：以回捞的全字段实体为准（保持原列表顺序）。
     * R1 "五无"助手过滤在此执行（依赖 content/reasoningContent，轻实体阶段无法判定）。
     */
    private void appendExposedGroup(List<MessageVo> result, FoldGroup group, Map<Long, Message> fullById) {
        if (group.assistant != null) {
            Message full = group.assistant.getId() != null ? fullById.get(group.assistant.getId()) : null;
            if (full == null) {
                // 回捞缺失防御：直接以轻实体兜底输出（不携带 content，状态结构完整）
                full = group.assistant;
            }
            if (isBlankAssistant(full, group)) {
                // R1 五无助手过滤：无正文 + 无思考 + SUCCESS + 名下无工具
                return;
            }
            result.add(MessageVo.fromEntity(full));
        }
        for (Message tool : group.tools) {
            Message fullTool = tool.getId() != null ? fullById.get(tool.getId()) : null;
            result.add(MessageVo.fromEntity(fullTool != null ? fullTool : tool));
        }
    }

    /**
     * R1 "五无"助手判定：无 content + 无 reasoningContent + SUCCESS + 名下无 TOOL。
     * 纯工具调用助手（有 TOOL）不在此列，不会被过滤。
     */
    private boolean isBlankAssistant(Message m, FoldGroup group) {
        if (m == null || MessageRole.ASSISTANT != m.getRole()) {
            return false;
        }
        if (MessageStatus.SUCCESS != m.getStatus()) {
            return false;
        }
        if (StringUtils.hasText(m.getContent()) || StringUtils.hasText(m.getReasoningContent())) {
            return false;
        }
        return group.tools.isEmpty();
    }

    /**
     * 将被折叠的小组序列聚合为一条 FOLDED 虚拟消息。
     * FAILED 工具名从回捞的全字段实体解析 toolCalls 获得（轻实体无该字段）。
     */
    private MessageVo buildFoldedVo(List<FoldGroup> groups, Map<Long, Message> fullById) {
        long minId = Long.MAX_VALUE;
        long maxId = Long.MIN_VALUE;
        int assistantCount = 0;
        int toolCount = 0;
        List<MessageVo.ErrorDetail> errors = new ArrayList<>();

        for (FoldGroup group : groups) {
            if (group.assistant != null) {
                assistantCount++;
                if (group.assistant.getId() != null) {
                    minId = Math.min(minId, group.assistant.getId());
                    maxId = Math.max(maxId, group.assistant.getId());
                }
                if (MessageStatus.FAILED == group.assistant.getStatus()) {
                    errors.add(new MessageVo.ErrorDetail("assistant", null));
                }
            }
            for (Message tool : group.tools) {
                toolCount++;
                if (tool.getId() != null) {
                    minId = Math.min(minId, tool.getId());
                    maxId = Math.max(maxId, tool.getId());
                }
                if (MessageStatus.FAILED == tool.getStatus()) {
                    String toolName = null;
                    Message full = tool.getId() != null ? fullById.get(tool.getId()) : null;
                    if (full != null && StringUtils.hasText(full.getToolCalls())) {
                        // 解析失败时 toolName 为空串，前端兜底显示通用文案
                        toolName = ToolCallCodec.parseSingle(full.getToolCalls()).getName();
                    }
                    errors.add(new MessageVo.ErrorDetail("tool", toolName));
                }
            }
        }

        // FOLDED 虚拟消息：id 取折叠区间最小 ID（前端以此作为稳定 key），不落库
        MessageVo vo = new MessageVo();
        vo.setId(minId == Long.MAX_VALUE ? null : minId);
        vo.setRole(MessageRole.FOLDED);
        vo.setContent("");
        vo.setStatus(MessageStatus.SUCCESS);
        vo.setFoldedMinId(minId == Long.MAX_VALUE ? null : minId);
        vo.setFoldedMaxId(maxId == Long.MIN_VALUE ? null : maxId);
        vo.setAssistantCount(assistantCount);
        vo.setToolCount(toolCount);
        vo.setErrorDetails(errors.isEmpty() ? null : errors);
        return vo;
    }

    /**
     * 折叠计划条目：外露小组 或 待折叠小组集合（渲染阶段按需回捞全字段）
     */
    private static class FoldPlanEntry {
        /** 外露小组（非空时本条目为外露输出） */
        final FoldGroup exposedGroup;
        /** 待折叠小组集合（非空时本条目聚合为 FOLDED 虚拟消息） */
        final List<FoldGroup> foldedGroups;

        private FoldPlanEntry(FoldGroup exposedGroup, List<FoldGroup> foldedGroups) {
            this.exposedGroup = exposedGroup;
            this.foldedGroups = foldedGroups;
        }

        static FoldPlanEntry exposed(FoldGroup group) {
            return new FoldPlanEntry(group, null);
        }

        static FoldPlanEntry folded(List<FoldGroup> groups) {
            return new FoldPlanEntry(null, groups);
        }
    }

    /**
     * 折叠小组：助手消息 + 名下工具明细（原子折叠单位；基于轻实体，仅含边界判定所需小字段）
     */
    private static class FoldGroup {
        Message assistant;
        final List<Message> tools = new ArrayList<>();
    }

    /**
     * 根据主键查询指定消息实体
     */
    public Optional<Message> findById(Long id) {
        return Optional.ofNullable(messageStore.getById(id));
    }
}
