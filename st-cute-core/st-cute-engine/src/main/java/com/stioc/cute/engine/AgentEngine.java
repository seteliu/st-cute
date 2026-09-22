package com.stioc.cute.engine;

import com.stioc.cute.engine.assembly.AgentEngineBuilder;
import com.stioc.cute.engine.common.EngineLock;
import com.stioc.cute.engine.facade.ContextFacade;
import com.stioc.cute.engine.facade.ConversationFacade;
import com.stioc.cute.engine.facade.LoopFacade;
import com.stioc.cute.engine.facade.ToolFacade;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.MessageStore;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ReAct 纯 Java 智能体引擎根对象（线程安全单例，不可变总门面）。
 * <p>
 * 纯 Java 构建，无任何 Spring/IoC 容器依赖；供血全部经由 {@link AgentEngineBuilder}
 * 显式注入，装配逻辑见 assembly 包。
 * 外部宿主统一通过本类暴露的 4 个门面领域进行业务交互：
 * <ul>
 *   <li>{@link #getContextFacade()}：会话上下文管理、取消/中断与快照</li>
 *   <li>{@link #getLoopFacade()}：ReAct 循环触发（同步/异步/强停重置）与会话智能命名</li>
 *   <li>{@link #getConversationFacade()}：循环运行态数据屏障与子代理汇报联动</li>
 *   <li>{@link #getToolFacade()}：工具注册中心查阅与人在回路审批决策</li>
 * </ul>
 * 另暴露两个存储供血契约直取出口（{@link #getConversationStore()} / {@link #getMessageStore()}），
 * 宿主业务统一经引擎获取存储，无特殊情况不要自行注入 Mapper 裸写存储路径。
 * 以及引擎锁供血契约直取出口（{@link #getEngineLock()}）：宿主存储层等需要与引擎
 * 共享同源锁的场合，统一经引擎取锁，禁止绕过引擎直接持有锁实现的静态引用。
 * </p>
 */
@Getter
@RequiredArgsConstructor
public class AgentEngine {

    /**
     * 会话上下文管理门面
     */
    private final ContextFacade contextFacade;

    /**
     * ReAct 循环触发与控制门面
     */
    private final LoopFacade loopFacade;

    /**
     * 循环运行态数据屏障与子代理汇报门面
     */
    private final ConversationFacade conversationFacade;

    /**
     * 工具注册中心与人在回路审批门面
     */
    private final ToolFacade toolFacade;

    /**
     * 会话存储供血契约直取出口
     */
    private final ConversationStore conversationStore;

    /**
     * 消息存储供血契约直取出口
     */
    private final MessageStore messageStore;

    /**
     * 引擎锁供血契约直取出口
     */
    private final EngineLock engineLock;

    /**
     * 引擎装配入口：返回流式装配构建器（装配逻辑见 assembly 包 AgentEngineBuilder）
     */
    public static AgentEngineBuilder builder() {
        return new AgentEngineBuilder();
    }
}
