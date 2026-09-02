package com.stioc.cute.message.access;

import com.stioc.cute.agent.access.AgentContext;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 消息处理与数据存取接口
 */
public interface MessageService {

    /**
     * 查询指定会话的历史消息 VO 列表（支持最大数量截断）
     */
    LimitMessageDto getConversationMessages(Long cid);

    /**
     * 物理清空指定会话的全部历史消息记录
     */
    void clearConversationMessages(Long cid);

    /**
     * 将会话历史截断并回滚至指定的历史消息节点（由事件上报机制处理）
     */
    void resetConversationMessages(AgentContext loopContext, Long messageId);

    /**
     * 用户发送并拉起新一轮推理循环
     */
    void sendMessage(AgentContext loopContext, String text);

    /**
     * 用户发送带附件的新消息并拉起新一轮推理循环
     */
    void sendMessage(AgentContext loopContext, String text, String attachments);

    /**
     * 针对指定历史消息节点进行重新尝试推理生成
     */
    void retryMessage(AgentContext loopContext, Long messageId);

    /**
     * 精确根据 callId 锁定会话中特定的工具消息实体
     */
    MessageEntity findToolMessage(Long cid, String toolCallId);

    /**
     * 根据主键查询消息当前状态，供写路径做「CANCELED 终态守卫」等轻量校验
     */
    MessageStatus findMessageStatus(Long messageId);

    /**
     * 获取指定会话中仍在后台运行中的工具消息行列表
     */
    List<MessageEntity> findInflightToolMessages(Long cid);

    /**
     * 获取指定会话中指定角色下仍处于过渡态（PENDING/RUNNING）的消息行列表。
     * 供用户中断等链路做终态兜底回写，避免消息因线程未响应中断而永久悬空。
     */
    List<MessageEntity> findInflightMessagesByRoles(Long cid, List<MessageRole> roles);

    /**
     * 获取指定会话按 ID 升序排列的全部物理消息行
     */
    List<MessageEntity> findByCidOrderByIdAsc(Long cid);

    /**
     * 获取指定会话中按 ID 升序且大模型可见的历史物理消息行
     */
    List<MessageEntity> findByCidAndVisibleToModelTrueOrderByIdAsc(Long cid);

    /**
     * 根据主键查询消息实体
     */
    Optional<MessageEntity> findById(Long id);

    /**
     * 修改指定消息的大模型可见性属性
     */
    void updateMessageVisibleToModel(Long cid, Long messageId, boolean visibleToModel);

    /**
     * 一键重置该会话所有历史消息的大模型可见性
     */
    void updateAllMessagesVisibleToModel(Long cid, boolean visibleToModel);

    /**
     * 获取会话中最后一条消息实体
     */
    Optional<MessageEntity> findLastMessage(Long cid);

    /**
     * 物理删除指定会话的所有关联消息行
     */
    void deleteByCid(Long cid);

    /**
     * 删除会话中 ID 大于特定值的历史消息记录
     */
    void deleteByCidAndIdGreaterThan(Long cid, Long messageId);

    /**
     * 基础消息插入（直转 Mapper）
     */
    void insert(MessageEntity entity);

    /**
     * 基础消息更新（直转 Mapper，根据 ID 更新非空字段）
     *
     * @return 是否实际更新到行（影响行数 > 0）
     */
    boolean updateById(MessageEntity entity);

    /**
     * 判断当前会话是否存在任何处于等待消费状态的 PENDING 消息。
     */
    boolean hasPendingMessages(Long cid);
}
