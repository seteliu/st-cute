package com.stioc.cute.conversation.access;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import com.stioc.cute.agent.access.AgentContext;

/**
 * 会话管理与状态调度接口
 */
public interface ConversationService {

    /**
     * 获取全部会话列表
     */
    List<ConversationEntity> getConversations();

    /**
     * 删除指定会话
     */
    void deleteConversation(Long id);

    /**
     * 批量级联物理删除指定会话列表及关联消息
     */
    void deleteConversations(List<Long> ids);

    /**
     * 创建并持久化新会话
     */
    ConversationEntity createConversation(ConversationEntity conversation);

    /**
     * 获取会话绑定的项目物理根路径
     */
    String getProjectPath(Long cid);

    /**
     * 初始化单轮调用的工具列表（通过事件上报）。
     * 循环轮次 loopCount 的推进已迁移至完成回调处的 CAS 消费，此处不再负责计数。
     */
    void initRoundTools(AgentContext context, List<String> toolCallIds);

    /**
     * 标记指定的工具调用已执行完成并剔除等待集合（通过事件上报）
     */
    boolean onToolCompleted(AgentContext context, String toolCallId);


    /**
     * 警告：仅供 EventListenerDirect 物理落库时调用，其它业务场景一律禁止越权直接调用！
     */
    void updateWaitingToolIds(Long cid, String delta);

    /**
     * 警告：仅供 EventListenerDirect 物理落库时调用，其它业务场景一律禁止越权直接调用！
     */
    void updateWaitingSubCids(Long cid, String delta);

    /**
     * 警告：仅供 EventListenerDirect 物理落库时调用，其它业务场景一律禁止越权直接调用！
     */
    void updateUnlockedToolNames(Long cid, String delta);

    /**
     * 子智能体运行完成后释放父会话中对应的等待占位（通过事件上报）
     */
    boolean onSubAgentCompleted(AgentContext parentContext, Long subCid);


    /**
     * 强制重置 Loop 运行状态（DB 侧清空 waitingToolIds/waitingSubCids/计数器），
     * 并中断内存中正在执行的活跃线程（通过事件上报）。
     */
    void forceResetLoopState(AgentContext context);

    /**
     * 查询指定会话的智能体循环是否在运行中
     */
    boolean isLoopRunning(Long cid);


    /**
     * 重置数据库中所有会话的 loopRunning 为 0
     */
    void resetAllConversationsLoopRunning();

    /**
     * 当判定流程结束时更新 loopRunning 状态为停止（通过事件上报）
     */
    void updateLoopRunningStateIfFinished(AgentContext context, boolean hasException);

    /**
     * 根据主键 ID 查询会话实体
     */
    Optional<ConversationEntity> findById(Long cid);

    /**
     * 级联物理删除关联到指定项目的所有会话及消息
     */
    void deleteConversationsByProjectId(Long projectId);

    /**
     * 强行清空会话在数据库中的等待锁指标数据（通过事件上报）
     */
    void clearConversationStatus(AgentContext context);

    /**
     * 强制向数据库插入指定的会话实体行
     */
    ConversationEntity insertConversation(ConversationEntity entity);

    /**
     * 子智能体运行结束后的联动状态 Hook。
     * 负责向父会话写入分支报告消息、扣减屏障，并返回父会话关联的所有子智能体是否均已运行完毕。
     *
     * @param parentContext 父会话运行上下文
     * @param subCid      子会话 ID
     * @param errorDetail 错误描述详情（用于子智能体异常超时挂掉的报告），若正常结束则传 null
     * @return 若该子会话是最后一个完成的，返回 true；否则返回 false
     */
    boolean handleSubAgentFinishedHook(AgentContext parentContext, Long subCid, String errorDetail);

    /**
     * 根据父会话 ID 查询所有直接关联的子智能体会话实体
     */
    List<ConversationEntity> findByParentCid(Long parentCid);
}
