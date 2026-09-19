package com.stioc.cute.conversation;

import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationQuery;
import com.stioc.cute.engine.store.types.SortDirection;
import com.stioc.cute.file.FileStorageService;
import com.stioc.cute.permission.types.PermissionMode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 宿主会话管理服务（纯宿主业务 CRUD 与查询面）。
 * <p>
 * 存储读写路径已全面收敛至 {@link ConversationStore} 供血接口。
 * 循环业务编排（屏障扣减、循环收口、子代理联动、差量原子更新）已收口引擎
 * LoopDataReporter / EngineDirectListener，本类不再承载。
 * </p>
 */
@Slf4j
@Service
public class ConversationService {

    @Resource
    private ConversationStore conversationStore;
    @Resource
    private FileStorageService fileStorageService;
    @Resource
    @Lazy
    private AgentEngine agentEngine;

    /**
     * 获取全部会话列表
     */
    public List<Conversation> getConversations() {
        return conversationStore.listByQuery(ConversationQuery.builder()
                .sortField("updateTime")
                .sortDirection(SortDirection.DESC)
                .build());
    }

    /**
     * 删除指定会话（级联子会话与消息）
     * <p>文件清理在事务提交后执行：数据库回滚无法恢复已删除的物理附件文件，
     * 事务内做文件 IO 既拖长事务又破坏原子性语义。</p>
     */
    @Transactional
    public void deleteConversation(Long id) {
        log.info("物理删除对话会话: {}", id);
        // 级联查询并递归删除所有子会话
        List<Conversation> subSessions = conversationStore.listByQuery(ConversationQuery.builder()
                .parentCid(id)
                .build());
        if (!subSessions.isEmpty()) {
            for (Conversation sub : subSessions) {
                deleteConversation(sub.getId());
            }
        }

        agentEngine.getConversationFacade().deleteConversation(id);

        // 事务提交后再清理该会话存储的物理附件文件及 cid 文件夹，
        // 保证 DB 删除成功落定后文件才被物理清除（回滚场景文件不丢）
        Long fileId = id;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    fileStorageService.deleteConversationFiles(fileId);
                } catch (Exception e) {
                    log.error("事务提交后清理会话 {} 的物理附件文件失败（不影响已完成的数据库删除）", fileId, e);
                }
            }
        });
    }

    /**
     * 批量级联物理删除指定会话列表及关联消息
     */
    @Transactional
    public void deleteConversations(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        log.info("批量物理删除对话会话: {}", ids);
        for (Long id : ids) {
            deleteConversation(id);
        }
    }

    /**
     * 创建并持久化新会话
     * <p>供应商绑定（providerGroup / providerModelName）完全信任前端传值，后端不自动填充默认值；
     * 供应商选择正确性由前端在创建前保证。</p>
     */
    public Conversation createConversation(Conversation conversation) {
        if (conversation.getCreateTime() == null) {
            conversation.setCreateTime(LocalDateTime.now());
        }
        conversation.setUpdateTime(LocalDateTime.now());

        // 自动取最近更新会话的 permissionMode（缺省继承；供应商字段不做继承，由前端传值）
        // limit(1)：仅需最近一条的权限模式，避免全表会话实体（含大字段）整体载入内存
        List<Conversation> existing = conversationStore.listByQuery(ConversationQuery.builder()
                .sortField("updateTime")
                .sortDirection(SortDirection.DESC)
                .limit(1)
                .build());
        if (!existing.isEmpty()) {
            Conversation latestConv = existing.getFirst();
            if (conversation.getPermissionMode() == null) {
                conversation.setPermissionMode(latestConv.getPermissionMode());
            }
        } else {
            // 如果库里没有任何会话，默认读取默认权限模式（供应商字段同样不做默认填充）
            if (conversation.getPermissionMode() == null) {
                conversation.setPermissionMode(PermissionMode.READ_ONLY.name());
            }
        }

        Conversation saved = agentEngine.getConversationFacade().createConversation(conversation);
        log.info("新建对话会话成功: {} (供应商绑定: {}/{})",
                saved.getId(), conversation.getProviderGroup(), conversation.getProviderModelName());
        return saved;
    }

    /**
     * 根据主键 ID 查询会话实体
     */
    public Optional<Conversation> findById(Long cid) {
        return Optional.ofNullable(conversationStore.getById(cid));
    }

    /**
     * 级联物理删除关联到指定项目的所有会话及消息
     */
    @Transactional
    public void deleteConversationsByProjectId(Long projectId) {
        log.debug("级联物理删除属于项目 {} 的所有会话", projectId);
        List<Conversation> conversations = conversationStore.listByQuery(ConversationQuery.builder()
                .workspaceId(String.valueOf(projectId))
                .build());
        for (Conversation c : conversations) {
            deleteConversation(c.getId());
        }
    }

    /**
     * 根据父会话 ID 查询所有直接关联的子智能体会话实体
     */
    public List<Conversation> findByParentCid(Long parentCid) {
        return conversationStore.listByQuery(ConversationQuery.builder()
                .parentCid(parentCid)
                .build());
    }
}
