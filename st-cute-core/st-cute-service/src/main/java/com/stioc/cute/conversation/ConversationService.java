package com.stioc.cute.conversation;

import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationQuery;
import com.stioc.cute.engine.store.types.SortDirection;
import com.stioc.cute.service.FileStorageService;
import com.stioc.cute.permission.types.PermissionMode;
import com.stioc.cute.provider.ProviderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private ProviderService providerService;
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

        // 级联清理该会话存储的物理附件文件及 cid 文件夹
        fileStorageService.deleteConversationFiles(id);
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
     */
    public Conversation createConversation(Conversation conversation) {
        if (conversation.getCreateTime() == null) {
            conversation.setCreateTime(LocalDateTime.now());
        }
        conversation.setUpdateTime(LocalDateTime.now());

        // 自动取最近更新会话的 permissionMode 以及 providerGroup 和 providerModelName（缺省继承）
        List<Conversation> existing = conversationStore.listByQuery(ConversationQuery.builder()
                .sortField("updateTime")
                .sortDirection(SortDirection.DESC)
                .build());
        if (!existing.isEmpty()) {
            Conversation latestConv = existing.getFirst();
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
                conversation.setPermissionMode(PermissionMode.READ_ONLY.name());
            }
            String defaultGroup = providerService.getProviderGroupForContext(null);
            if (conversation.getProviderGroup() == null && defaultGroup != null) {
                conversation.setProviderGroup(defaultGroup);
                conversation.setProviderModelName(providerService.getModelNameForContext(null, defaultGroup));
            }
        }

        Conversation saved = agentEngine.getConversationFacade().createConversation(conversation);
        log.info("新建对话会话成功: {}", saved.getId());
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
