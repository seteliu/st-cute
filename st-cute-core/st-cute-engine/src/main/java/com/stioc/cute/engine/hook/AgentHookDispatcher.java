package com.stioc.cute.engine.hook;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.common.AgentEngineLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

/**
 * 引擎生命周期挂点分发器：全会话共享一份 Hook 监听器清单，
 * 收编原 AgentContext.triggerHook 的直调逻辑——cid 数据锁内同步直调、异常即阻断。
 * <p>
 * Hook 是同步回调扩展点而非广播事实，不经事件总线；与事件分发器（AgentEventDispatcher）相互独立。
 * 字段使用 {@link Optional} 显式声明其为可选扩展配置。
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class AgentHookDispatcher {

    private final Optional<List<HookListener>> hookListeners;

    /**
     * 触发生命周期挂点（AgentContext.triggerHook 的薄委托落点）：
     * 在 cid 数据锁内同步直调全部宿主 Hook 监听器。
     * <p>
     * 阻断语义：任一监听器抛出异常即停止后续遍历并向上传播，
     * 由引擎调用点（工具前置/后置挂点）的 try-catch 捕获后执行拦截或结果改写。
     * 与原 HOOK 镜像事件走 DIRECT 同步层的锁语义完全等价（ContractLock 可重入，无死锁风险）。
     * </p>
     *
     * @param context 当前会话上下文（HookListener.onHook 契约签名所需）
     * @param type    挂点类型
     * @param payload 强类型载荷（生命周期挂点为 null）
     * @throws Exception 宿主侧 Hook 抛出时原样向上传播，由引擎调用点捕获执行阻断
     */
    public void dispatch(AgentContext context, HookType type, HookPayload payload) throws Exception {
        if (type == null || hookListeners.isEmpty() || hookListeners.get().isEmpty()) {
            return;
        }

        Lock cidLock = AgentEngineLock.CID_DATA_STRIPED.get(context.getCid());
        cidLock.lock();
        try {
            for (HookListener hookListener : hookListeners.get()) {
                // 同步直调：宿主 Hook 异常即阻断，向上传播由引擎调用点捕获
                hookListener.onHook(type, payload, context);
            }
        } finally {
            cidLock.unlock();
        }
    }
}
