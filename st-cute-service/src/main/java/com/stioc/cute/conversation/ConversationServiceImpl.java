package com.stioc.cute.conversation;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.util.UpdateEntity;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.agent.access.AgentContextManager;
import com.stioc.cute.agent.event.AgentEventFactory;
import com.stioc.cute.conversation.access.ConversationEntity;
import com.stioc.cute.conversation.access.ConversationService;
import com.stioc.cute.message.access.MessageEntity;
import com.stioc.cute.message.access.MessageRole;
import com.stioc.cute.message.access.MessageService;
import com.stioc.cute.message.access.MessageStatus;
import com.stioc.cute.platform.contract.ContractLock;
import com.stioc.cute.project.access.ProjectService;
import com.stioc.cute.provider.ProviderService;
import com.stioc.cute.file.access.FileStorageService;
import com.stioc.cute.repository.ConversationMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

/**
 * 会话管理与状态调度服务实现类
 */
@Slf4j
@Service
public class ConversationServiceImpl implements ConversationService {

    @Resource
    private ConversationMapper conversationMapper;
    @Resource
    private MessageService messageService;
    @Resource
    private ProjectService projectService;
    @Resource
    private ProviderService providerService;
    @Resource
    private FileStorageService fileStorageService;
    @Resource
    @Lazy
    private AgentContextManager agentContextManager;


    public List<ConversationEntity> getConversations() {
        QueryWrapper query = QueryWrapper.create()
                .orderBy(ConversationEntity::getUpdatedAt, false);
        return conversationMapper.selectListByQuery(query);
    }

    @Transactional
    public void deleteConversation(Long id) {
        log.info("物理删除对话会话: {}", id);
        // 级联查询并递归删除所有子会话
        QueryWrapper subQuery = QueryWrapper.create()
                .where(ConversationEntity::getParentCid).eq(id);
        List<ConversationEntity> subSessions = conversationMapper.selectListByQuery(subQuery);
        if (subSessions != null && !subSessions.isEmpty()) {
            for (ConversationEntity sub : subSessions) {
                deleteConversation(sub.getId());
            }
        }

        AgentContext context = agentContextManager.getActiveContext(id);
        if (context != null) {
            try {
                context.publishEvent(AgentEventFactory.createConversationDelete(context, id));
            } catch (Exception e) {
                log.warn("发送删除会话事件失败, id={}", id, e);
            }
            agentContextManager.removeContext(id);
        } else {
            conversationMapper.deleteById(id);
            messageService.deleteByCid(id);
        }

        // 级联清理该会话存储的物理附件文件及 cid 文件夹
        fileStorageService.deleteConversationFiles(id);
    }

    @Override
    @Transactional
    public void deleteConversations(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        log.info("批量物理删除对话会话: {}", ids);
        for (Long id : ids) {
            deleteConversation(id);
        }
    }

    public ConversationEntity createConversation(ConversationEntity conversation) {
        if (conversation.getCreatedAt() == null) {
            conversation.setCreatedAt(LocalDateTime.now());
        }
        conversation.setUpdatedAt(LocalDateTime.now());

        // 自动取最近 update 会话的 permissionMode 以及 providerGroup 和 providerModelName
        QueryWrapper latestQuery = QueryWrapper.create()
                .orderBy(ConversationEntity::getUpdatedAt, false)
                .limit(1);
        ConversationEntity latestConv = conversationMapper.selectOneByQuery(latestQuery);
        if (latestConv != null) {
            if (conversation.getPermissionMode() == null) {
                conversation.setPermissionMode(latestConv.getPermissionMode());
            }
            if (conversation.getProviderGroup() == null) {
                conversation.setProviderGroup(latestConv.getProviderGroup());
            }
            if (conversation.getProviderModelName() == null) {
                conversation.setProviderModelName(latestConv.getProviderModelName());
            }
        } else {
            // 如果库里没有任何会话，默认读取第一个大模型供应商的 group 和默认模式
            if (conversation.getPermissionMode() == null) {
                conversation.setPermissionMode("READ_ONLY");
            }
            String defaultGroup = providerService.getProviderGroupForContext(null);
            if (conversation.getProviderGroup() == null && defaultGroup != null) {
                conversation.setProviderGroup(defaultGroup);
                conversation.setProviderModelName(providerService.getModelNameForContext(null, defaultGroup));
            }
        }

        ConversationEntity saved = insertConversation(conversation);
        log.info("新建对话会话成功: {}", saved.getId());
        return saved;
    }

    public String getProjectPath(Long cid) {
        ConversationEntity conv = conversationMapper.selectOneById(cid);
        if (conv != null && conv.getProjectId() != null) {
            return projectService.findById(conv.getProjectId())
                    .map(proj -> proj.getPath())
                    .orElse("");
        }
        return "";
    }

    @Override
    public void initRoundTools(AgentContext context, List<String> toolCallIds) {
        Long cid = context.getCid();
        String waitingIdsStr = toolCallIds.isEmpty() ? null : String.join(",", toolCallIds);

        // 1. 同步更新内存（循环轮次 loopCount 的推进已迁移至触发点 CAS，此处不再负责计数）
        context.setCallToolCount(toolCallIds.size());

        // 2. 上报更新事件，委托直接层写盘
        ConversationEntity updatePayload = UpdateEntity.of(ConversationEntity.class);
        updatePayload.setId(cid);
        updatePayload.setCallToolCount(toolCallIds.size());
        updatePayload.setWaitingToolIds(waitingIdsStr);
        updatePayload.setUpdatedAt(LocalDateTime.now());
        context.publishEvent(AgentEventFactory.createConversationUpdate(context, updatePayload));

        log.debug("已通过事件总线上报下一轮工具轮次初始化: cid={}, count={}", cid, toolCallIds.size());
    }

    @Override
    public boolean onToolCompleted(AgentContext context, String toolCallId) {
        Long cid = context.getCid();
        log.debug("onToolCompleted 开始执行: cid={}, toolCallId={}", cid, toolCallId);

        // 会话级排他锁：将「扣减屏障 → 判定」收敛进单一临界区，锁与 publishEvent 内部使用的
        // 为同一把可重入锁（同 key 同源），此处外层加锁后事件链内重入无死锁。
        // 修复：并行批多个完成回调交错执行时，多线程都可能读到"屏障已空"而重复拉起下一轮（双触发竞态）。
        // 锁内临界区串行保证只有真实扣掉最后一项的线程能看到空集合，天然唯一触发。
        Lock cidLock = ContractLock.CID_DATA_STRIPED.get(cid);
        cidLock.lock();
        try {
            // 1. 入口验证：回调对应的 id 必须仍在等待集合中。
            //    重复通知、迟到的滞留回调（如会话已被 forceReset 清空屏障后醒来）不携带扣减语义，
            //    直接丢弃，从入口切断"空窗期内不带扣减的回调看到空集合"的幻影触发路径
            if (!context.getWaitingToolIds().contains(toolCallId)) {
                log.warn("[触发守卫] 回调 id 不在等待集合中（重复/迟到/清理后滞留），丢弃本次回调: cid={}, toolCallId={}",
                        cid, toolCallId);
                return false;
            }

            // 2. 上报差量剔除工具事件（事件链在锁内同步完成写盘与内存回填）
            ConversationEntity updatePayload = UpdateEntity.of(ConversationEntity.class);
            updatePayload.setId(cid);
            updatePayload.setWaitingToolIds("-" + toolCallId);
            context.publishEvent(AgentEventFactory.createConversationUpdate(context, updatePayload));

            // 3. 判定：锁内临界区串行，只有真实扣掉最后一项的线程才能观察到空集合（唯一触发者）
            return context.getWaitingToolIds().isEmpty() && context.getWaitingSubCids().isEmpty();
        } finally {
            cidLock.unlock();
        }
    }

    @Override
    @Transactional
    public void updateWaitingToolIds(Long cid, String delta) {
        log.debug("updateWaitingToolIds 开始处理: cid={}, delta={}", cid, delta);
        lockUpdateConversation(cid, conv -> {
            String oldVal = conv.getWaitingToolIds();
            String newVal = processDeltaString(oldVal, delta);
            log.debug("updateWaitingToolIds 物理扣减前后变化: cid={}, oldVal={}, newVal={}", cid, oldVal, newVal);

            ConversationEntity updater = UpdateEntity.of(ConversationEntity.class);
            updater.setId(cid);
            updater.setWaitingToolIds(newVal);
            updater.setUpdatedAt(LocalDateTime.now());
            return updater;
        });
    }

    @Override
    @Transactional
    public void updateWaitingSubCids(Long cid, String delta) {
        log.debug("updateWaitingSubCids 开始处理: cid={}, delta={}", cid, delta);
        lockUpdateConversation(cid, conv -> {
            String oldVal = conv.getWaitingSubCids();
            String newVal = processDeltaString(oldVal, delta);
            log.debug("updateWaitingSubCids 物理扣减前后变化: cid={}, oldVal={}, newVal={}", cid, oldVal, newVal);

            ConversationEntity updater = UpdateEntity.of(ConversationEntity.class);
            updater.setId(cid);
            updater.setWaitingSubCids(newVal);
            updater.setUpdatedAt(LocalDateTime.now());
            return updater;
        });
    }

    @Override
    @Transactional
    public void updateUnlockedToolNames(Long cid, String delta) {
        log.debug("updateUnlockedToolNames 开始处理: cid={}, delta={}", cid, delta);
        lockUpdateConversation(cid, conv -> {
            String oldVal = conv.getUnlockedToolNames();
            String newVal = processDeltaString(oldVal, delta);
            log.debug("updateUnlockedToolNames 物理扣减前后变化: cid={}, oldVal={}, newVal={}", cid, oldVal, newVal);

            ConversationEntity updater = UpdateEntity.of(ConversationEntity.class);
            updater.setId(cid);
            updater.setUnlockedToolNames(newVal);
            updater.setUpdatedAt(LocalDateTime.now());
            return updater;
        });
    }

    @Override
    public boolean onSubAgentCompleted(AgentContext parentContext, Long subCid) {
        Long parentCid = parentContext.getCid();

        // 会话级排他锁：与 onToolCompleted 对称的子 Agent 完成判定路径，
        // 防止多子 Agent 完成回调交错时重复拉起父会话下一轮
        Lock cidLock = ContractLock.CID_DATA_STRIPED.get(parentCid);
        cidLock.lock();
        try {
            // 1. 入口验证：子会话 id 必须仍在等待集合中（对称 onToolCompleted 的触发守卫）。
            //    僵死清理、forceReset 等路径已扣减过该 id 时，迟到的完成汇报直接丢弃
            if (!parentContext.getWaitingSubCids().contains(subCid)) {
                log.warn("[触发守卫] 子会话 id 不在等待集合中（重复/迟到/清理后滞留），丢弃本次汇报: parentCid={}, subCid={}",
                        parentCid, subCid);
                return false;
            }

            // 2. 上报差量剔除子会话事件（事件链在锁内同步完成写盘与内存回填）
            ConversationEntity updatePayload = UpdateEntity.of(ConversationEntity.class);
            updatePayload.setId(parentCid);
            updatePayload.setWaitingSubCids("-" + subCid);
            parentContext.publishEvent(AgentEventFactory.createConversationUpdate(parentContext, updatePayload));

            // 3. 判定：锁内临界区串行，只有真实扣掉最后一项的线程才能观察到空集合（唯一触发者）
            return parentContext.getWaitingToolIds().isEmpty() && parentContext.getWaitingSubCids().isEmpty();
        } finally {
            cidLock.unlock();
        }
    }

    @Override
    public void forceResetLoopState(AgentContext context) {
        Long cid = context.getCid();
        log.debug("强制重置 Loop 状态（通过事件上报）: cid={}", cid);

        ConversationEntity updatePayload = UpdateEntity.of(ConversationEntity.class);
        updatePayload.setId(cid);
        updatePayload.setCallToolCount(0);
        // loopCount 重置为 0（无活动循环）：滞留的旧回调携带的 observed ≥1 与 0 不等，自动被 CAS 令牌拒绝
        updatePayload.setLoopCount(0);
        // 内存同步归零：防止内存残留轮次与 DB 漂移（下次用户发消息时 alignForNextStep 会置 1）
        context.setLoopCount(0);
        updatePayload.setWaitingToolIds(null);
        updatePayload.setWaitingSubCids(null);
        updatePayload.setLoopRunning(0);
        updatePayload.setUpdatedAt(LocalDateTime.now());
        context.publishEvent(AgentEventFactory.createConversationUpdate(context, updatePayload));
    }

    public boolean isLoopRunning(Long cid) {
        ConversationEntity conv = conversationMapper.selectOneById(cid);
        return conv != null && conv.getLoopRunning() != null && conv.getLoopRunning() == 1;
    }

    @Transactional
    public void resetAllConversationsLoopRunning() {
        try {
            conversationMapper.resetAllLoopRunning();
            log.info("成功全量重置数据库中所有会话的 loopRunning 为 0");
        } catch (Exception e) {
            log.error("全量重置数据库 loopRunning 发生异常", e);
        }
    }

    @Override
    public void updateLoopRunningStateIfFinished(AgentContext context, boolean hasException) {
        Long cid = context.getCid();
        ConversationEntity conv = conversationMapper.selectOneById(cid);
        if (conv == null) {
            return;
        }
        boolean allWaitingEmpty = isToolIdsEmpty(conv.getWaitingToolIds()) && isSubCidsEmpty(conv.getWaitingSubCids());
        boolean trulyFinished = hasException || allWaitingEmpty;
        if (trulyFinished) {
            ConversationEntity updatePayload = UpdateEntity.of(ConversationEntity.class);
            updatePayload.setId(cid);
            updatePayload.setLoopRunning(0);
            updatePayload.setUpdatedAt(LocalDateTime.now());
            context.publishEvent(AgentEventFactory.createConversationUpdate(context, updatePayload));
        }
    }

    private String processDeltaString(String currentVal, String deltaInput) {
        if (deltaInput == null) {
            return currentVal;
        }
        if (deltaInput.isEmpty()) {
            return null;
        }

        Set<String> items = new LinkedHashSet<>();
        if (currentVal != null && !currentVal.isBlank()) {
            for (String part : currentVal.split(",")) {
                if (!part.isBlank()) {
                    items.add(part.trim());
                }
            }
        }

        if (deltaInput.startsWith("+")) {
            String toAddStr = deltaInput.substring(1).trim();
            for (String item : toAddStr.split(",")) {
                if (!item.isBlank()) {
                    items.add(item.trim());
                }
            }
        } else if (deltaInput.startsWith("-")) {
            String toRemoveStr = deltaInput.substring(1).trim();
            for (String item : toRemoveStr.split(",")) {
                if (!item.isBlank()) {
                    items.remove(item.trim());
                }
            }
        } else {
            return deltaInput;
        }

        return items.isEmpty() ? null : String.join(",", items);
    }

    private boolean isToolIdsEmpty(String waitingToolIds) {
        return waitingToolIds == null || waitingToolIds.isBlank();
    }

    private boolean isSubCidsEmpty(String waitingSubCids) {
        return waitingSubCids == null || waitingSubCids.isBlank();
    }

    public Optional<ConversationEntity> findById(Long cid) {
        return Optional.ofNullable(conversationMapper.selectOneById(cid));
    }

    @Transactional
    public void deleteConversationsByProjectId(Long projectId) {
        log.debug("级联物理删除属于项目 {} 的所有会话", projectId);
        List<ConversationEntity> conversations = conversationMapper.selectAll();
        for (ConversationEntity c : conversations) {
            if (projectId.equals(c.getProjectId())) {
                deleteConversation(c.getId());
            }
        }
    }

    /**
     * 会话实体原子更新原语：在 cid 数据锁内完成「查最新实体 → modifier 变换 → 落库」。
     * <p>
     * 正常路径下本方法由 publishEvent 的 cid 锁覆盖，内部加锁为可重入防御（无额外开销）；
     * 该防护同时保证任何绕过事件总线的直接调用依然具备原子性——差量运算（如 waiting 字段的
     * -id 剔除）必须基于最新 DB 值，读改写过程不可被并发写穿透。
     * </p>
     *
     * @param cid      会话 ID
     * @param modifier 基于最新实体计算更新内容的变换函数，返回 null 时跳过持久化
     */
    private void lockUpdateConversation(Long cid, Function<ConversationEntity, ConversationEntity> modifier) {
        Lock lock = ContractLock.CID_DATA_STRIPED.get(cid);
        lock.lock();
        try {
            ConversationEntity latest = conversationMapper.selectOneById(cid);
            if (latest == null) {
                log.warn("updateConversation: 会话不存在, cid={}", cid);
                return;
            }
            ConversationEntity modified = modifier.apply(latest);
            if (modified == null) {
                log.debug("updateConversation: modifier 返回 null，跳过持久化, cid={}", cid);
                return;
            }
            conversationMapper.update(modified);
            log.debug("updateConversation: 持久化成功, cid={}", cid);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clearConversationStatus(AgentContext context) {
        Long cid = context.getCid();
        ConversationEntity updatePayload = UpdateEntity.of(ConversationEntity.class);
        updatePayload.setId(cid);
        updatePayload.setInputTokens(0L);
        updatePayload.setOutputTokens(0L);
        updatePayload.setCachedTokens(0L);
        updatePayload.setCallToolCount(0);
        updatePayload.setLoopCount(0);
        updatePayload.setWaitingToolIds(null);
        updatePayload.setWaitingSubCids(null);
        updatePayload.setLoopRunning(0);
        updatePayload.setUpdatedAt(LocalDateTime.now());
        context.publishEvent(AgentEventFactory.createConversationUpdate(context, updatePayload));
        log.debug("已上报清空会话数据事件: cid={}", cid);
    }

    public ConversationEntity insertConversation(ConversationEntity entity) {
        conversationMapper.insert(entity);
        log.debug("insertConversation: 持久化成功, id={}", entity.getId());
        return entity;
    }

    @Override
    public boolean handleSubAgentFinishedHook(AgentContext parentContext, Long subCid, String errorDetail) {
        Long parentCid = parentContext.getCid();
        String reportContent;
        if (errorDetail != null) {
            reportContent = buildReport(subCid, null, new RuntimeException(errorDetail));
        } else {
            Optional<MessageEntity> lastMsgOpt = messageService.findLastMessage(subCid);
            reportContent = buildReport(subCid, lastMsgOpt.map(MessageEntity::getContent).orElse(null), null);
        }

        MessageEntity branchMsg = MessageEntity.builder()
                .cid(parentCid)
                .role(MessageRole.BRANCH)
                .content(reportContent)
                .status(MessageStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        parentContext.publishEvent(AgentEventFactory.createMessageCreate(parentContext, branchMsg));

        // 扣减父智能体的子会话等待屏障并检查是否全部完成
        return onSubAgentCompleted(parentContext, subCid);
    }

    private String buildReport(Long subCid, String assistantOutput, Throwable runThrowable) {
        StringBuilder report = new StringBuilder();
        report.append("[子 Agent 工作报告]\n");
        report.append("子 Agent 会话 ID: ").append(subCid).append("\n");
        if (runThrowable != null) {
            report.append("运行状态: 失败 (").append(runThrowable.getClass().getSimpleName()).append(")\n");
            report.append("错误信息: ").append(runThrowable.getMessage()).append("\n");
        } else {
            report.append("运行状态: 已完成\n");
        }
        report.append("结果摘要:\n");
        report.append(assistantOutput != null && !assistantOutput.isEmpty() ? assistantOutput : "(无输出内容)");
        return report.toString();
    }

    @Override
    public List<ConversationEntity> findByParentCid(Long parentCid) {
        QueryWrapper childQuery = QueryWrapper.create()
                .where(ConversationEntity::getParentCid).eq(parentCid);
        return conversationMapper.selectListByQuery(childQuery);
    }
}
