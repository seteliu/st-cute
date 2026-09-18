package com.stioc.cute.engine.event;

import com.stioc.cute.engine.event.types.AgentEvent;
import com.stioc.cute.engine.event.types.ListenerTier;
import com.stioc.cute.engine.common.AgentEngineCommonThread;
import com.stioc.cute.engine.common.EngineLock;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.Lock;

/**
 * 引擎事件分发器：全会话共享一份监听器清单（构造时按「层级 + 顺序号」升序排定），
 * 收编原 AgentContext.publishEvent 的分发逻辑——cid 数据锁覆盖第一、二层同步消费，
 * 第三层异步串行推送通知。
 * <p>
 * 排序键为二元组：先比 {@link ListenerTier}（跨层严格有序，宿主监听器无法插队到
 * 引擎落库/回填之前），同层内再比 {@link AgentEventListener#getPriority()}（值小者先）。
 * 引擎内置监听器统一为 priority=0，故同层内恒先于宿主监听器执行。
 * </p>
 * <p>
 * 事件分发的锁语义与监听器遍历策略随本类走，AgentContext 仅保留薄委托，
 * 引擎内部组件的 context.publishEvent(...) 调用形态不变。
 * </p>
 */
@Slf4j
public class AgentEventDispatcher {

    /**
     * 按「层级升序 + 同层顺序号升序」固化的全局事件监听器链（不可变）
     */
    private final List<AgentEventListener> listeners;

    /**
     * 引擎锁供血（宿主注入，实现见 EngineLock 契约）
     */
    private final EngineLock lockProvider;

    public AgentEventDispatcher(List<AgentEventListener> eventListeners, EngineLock lockProvider) {
        this.lockProvider = lockProvider;
        if (eventListeners == null || eventListeners.isEmpty()) {
            this.listeners = List.of();
        } else {
            List<AgentEventListener> copy = new ArrayList<>(eventListeners);
            copy.sort(Comparator
                    .comparingInt((AgentEventListener listener) -> {
                        ListenerTier tier = listener.getTier();
                        return (tier != null ? tier : ListenerTier.DIRECT).getOrder();
                    })
                    .thenComparingInt(AgentEventListener::getPriority));
            this.listeners = List.copyOf(copy);
        }
    }

    /**
     * 分发事件（AgentContext.publishEvent 的薄委托落点）。
     */
    public void dispatch(Long cid, AgentEvent event) {
        if (event == null) {
            return;
        }
        if (event.getTimestamp() == 0L) {
            event.setTimestamp(System.currentTimeMillis());
        }

        // 穿透型事件：无第一、二层消费，免锁直推第三层异步串行队列
        if (event.getType().isPassThrough()) {
            for (AgentEventListener listener : listeners) {
                if (listener.getTier() == ListenerTier.NOTIFICATION) {
                    try {
                        AgentEngineCommonThread.submitNotify(() -> listener.onEvent(event));
                    } catch (Exception e) {
                        log.error("异步监听器处理事件发生异常: listener={}, type={}, cid={}",
                                listener.getClass().getSimpleName(), event.getType(), cid, e);
                    }
                }
            }
            return;
        }

        // 写命令事件：cid 数据锁覆盖第一、二层同步消费（DIRECT 写盘 + CACHE 回填），
        // 保证「写入 → 回填 → 判定」在单一临界区完成，防止并行批双触发；
        // 锁为可重入锁，与 ConversationServiceImpl.lockUpdateConversation 同源
        Lock cidLock = lockProvider.getConversationDataLock(cid != null ? cid : 0L);
        cidLock.lock();
        try {
            for (AgentEventListener listener : listeners) {
                if (listener.getTier() == ListenerTier.DIRECT || listener.getTier() == ListenerTier.CACHE) {
                    // 第一、二层：同步执行，异常熔断阻断
                    try {
                        listener.onEvent(event);
                    } catch (RuntimeException e) {
                        log.error("同步监听器处理事件发生异常，触发熔断阻断: listener={}, type={}, cid={}",
                                listener.getClass().getSimpleName(), event.getType(), cid, e);
                        throw e; // 硬阻断
                    }
                } else {
                    // 第三层：异步串行推送通知
                    try {
                        AgentEngineCommonThread.submitNotify(() -> listener.onEvent(event));
                    } catch (Exception e) {
                        log.error("异步监听器处理事件发生异常: listener={}, type={}, cid={}",
                                listener.getClass().getSimpleName(), event.getType(), cid, e);
                    }
                }
            }
        } finally {
            cidLock.unlock();
        }
    }
}
