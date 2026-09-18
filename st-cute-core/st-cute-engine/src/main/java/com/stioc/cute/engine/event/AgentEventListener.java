package com.stioc.cute.engine.event;

import com.stioc.cute.engine.event.types.AgentEvent;
import com.stioc.cute.engine.event.types.ListenerTier;

/**
 * 智能体运行事件监听器接口
 */
public interface AgentEventListener {
    /**
     * 响应处理智能体生命周期或业务状态变更事件
     */
    void onEvent(AgentEvent event);

    /**
     * 获取当前监听器的触发执行阶梯级别
     */
    default ListenerTier getTier() {
        return ListenerTier.DIRECT;
    }

    /**
     * 获取同层内的执行顺序号：值越小越先执行。
     * <p>
     * 排序键以层级（{@link #getTier()}）为主、本顺序号为辅——仅在同一层内生效，
     * 不会使宿主监听器跨越层级插队到引擎的落库/回填逻辑之前。
     * 缺省返回 1（中等优先级），宿主侧一般无需覆写本方法；
     * 引擎内置监听器统一返回 0（最先执行）。
     * 允许返回负数：宿主在极端情况下可借此插到引擎内置监听器之前。
     * </p>
     */
    default int getPriority() {
        return 1;
    }
}
