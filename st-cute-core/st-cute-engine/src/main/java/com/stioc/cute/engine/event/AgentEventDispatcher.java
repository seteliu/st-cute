package com.stioc.cute.engine.event;

import com.stioc.cute.engine.event.types.AgentEvent;
import com.stioc.cute.engine.event.types.ListenerTier;
import com.stioc.cute.engine.common.NotifyExecutor;
import com.stioc.cute.engine.common.EngineLock;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.Lock;

/**
 * 引擎事件分发器：全会话共享一份监听器清单（构造时按「层级 + 顺序号」升序排定），
 * cid 数据锁覆盖第一、二层同步消费，第三层按会话投递通知执行器。
 * <p>
 * 排序键为二元组：先比 {@link ListenerTier}（跨层严格有序，宿主监听器无法插队到
 * 引擎落库/回填之前），同层内再比 {@link AgentEventListener#getPriority()}（值小者先）。
 * 引擎内置监听器统一为 priority=0，故同层内恒先于宿主监听器执行。
 * </p>
 * <p>
 * <b>第三层的顺序与并发模型</b>：第三层只要求「同一会话内严格有序」，会话之间无因果依赖。
 * 顺序与并发由此解耦——顺序由事件发布方与 {@link NotifyExecutor} 的同车道串行共同保证，
 * 并发由多条车道的互不干扰提供。第一、二层则原地同步消费（跨会话本就由 cid 分片锁并行）。
 * </p>
 * <p>
 * <b>网络型流式事件的双模式</b>：仅思考流与正文流（{@code isNetworkStream()}）具备背压通道，
 * 是否启用由极速模式决定：
 * <ul>
 *   <li><b>极速模式（默认）</b>：不放行背压通道，二者与其他穿透型事件同样投递车道线程池，
 *       循环线程产出不被下游消费阻塞，吞吐优先；积压超限时由队列的调用线程执行策略兜底形成背压；</li>
 *   <li><b>非极速模式</b>：由当前线程同步直调第三层，循环线程在客户端消费完成后才继续产出，
 *       速率适配由 TCP 层天然完成，服务端不积压。外部消费失败由单个监听器的 try-catch 隔离，
 *       不会反向阻塞或中断事件分发。</li>
 * </ul>
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

    /**
     * 通知层按会话保序的并行执行器（引擎自建，第三层异步投递目的地）
     */
    private final NotifyExecutor notifyExecutor;

    /**
     * 网络通知事件极速模式：开启时思考流/正文流不走同步直调，而是投递车道线程池并发消化
     */
    private final boolean notifyFastMode;

    public AgentEventDispatcher(List<AgentEventListener> eventListeners, EngineLock lockProvider,
                                NotifyExecutor notifyExecutor, boolean notifyFastMode) {
        this.lockProvider = lockProvider;
        this.notifyExecutor = notifyExecutor;
        this.notifyFastMode = notifyFastMode;
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

        // 网络型流式事件（仅思考流/正文流）的背压通道：仅在关闭极速模式时走同步直调。
        // 开启极速模式时故意不放行，使其落入下方穿透型分支投递车道线程池——产出不被
        // 下游消费阻塞，吞吐优先；此时背压由车道队列积压超限时的「调用线程执行」兜底承担。
        if (!notifyFastMode && event.getType().isNetworkStream()) {
            dispatchNetworkStream(event);
            return;
        }

        // 穿透型事件：无第一、二层消费，免锁直投通知执行器
        if (event.getType().isPassThrough()) {
            for (AgentEventListener listener : listeners) {
                if (listener.getTier() == ListenerTier.NOTIFICATION) {
                    submitAsync(cid, listener, event);
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
                    // 第三层：投递通知执行器按会话保序异步消费
                    submitAsync(cid, listener, event);
                }
            }
        } finally {
            cidLock.unlock();
        }
    }

    /**
     * 网络型流式事件专用通道：由调用线程同步直调全部第三层监听器。
     * <p>
     * 逐个监听器独立 try-catch 隔离：任一外部消费方（如 WebSocket 外推）失败或阻塞，
     * 都不会影响同层其他监听器的执行（典型为引擎内置的流式缓存维护）。
     * 缓存维护恒为纯内存操作且 priority=0 排在宿主监听器之前，故即便后续宿主监听器抛错，
     * 缓存已先行完成更新。
     * </p>
     */
    private void dispatchNetworkStream(AgentEvent event) {
        for (AgentEventListener listener : listeners) {
            if (listener.getTier() == ListenerTier.NOTIFICATION) {
                try {
                    listener.onEvent(event);
                } catch (Exception e) {
                    log.error("网络型流式事件同步消费失败（已隔离，不影响同层其他监听器）: listener={}, type={}",
                            listener.getClass().getSimpleName(), event.getType(), e);
                }
            }
        }
    }

    /**
     * 异步投递第三层任务：按会话投至通知执行器的对应车道，保持同会话内的投递顺序。
     */
    private void submitAsync(Long cid, AgentEventListener listener, AgentEvent event) {
        try {
            notifyExecutor.execute(cid, () -> listener.onEvent(event));
        } catch (Exception e) {
            log.error("异步监听器投递失败: listener={}, type={}, cid={}",
                    listener.getClass().getSimpleName(), event.getType(), cid, e);
        }
    }
}
