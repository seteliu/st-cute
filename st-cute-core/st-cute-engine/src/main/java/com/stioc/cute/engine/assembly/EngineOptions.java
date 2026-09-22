package com.stioc.cute.engine.assembly;

import lombok.Getter;

/**
 * 引擎配置项聚合：开关、调优值与默认文案的收纳处。
 * <p>
 * Builder 收集期为平铺散字段（IDE 友好、便于单项设置），build 时冻结为本对象传给组件，
 * 组件统一接收本对象，避免配置项增长穿透构造链。
 * 新增配置项时需两处同步：{@code AgentEngineBuilder} 平铺 setter 与本类字段。
 * </p>
 */
@Getter
public class EngineOptions {

    /**
     * 新会话默认标题
     */
    private final String defaultConversationTitle;

    /**
     * 通知层并行车道数：同会话事件恒落同车道保序，不同会话分车道并行
     */
    private final int notifyLaneCount;

    /**
     * 极速模式：开启（默认）时流式事件全部异步投递车道，吞吐优先；
     * 关闭时思考流/正文流改同步直调，换取端到端背压（慢客户端让产出一并变慢）
     */
    private final boolean notifyFastMode;

    /**
     * 全参构造（由 AgentEngineBuilder 在 build 时调用，冻结收集期散字段）
     */
    EngineOptions(String defaultConversationTitle, int notifyLaneCount, boolean notifyFastMode) {
        this.defaultConversationTitle = defaultConversationTitle;
        this.notifyLaneCount = notifyLaneCount;
        this.notifyFastMode = notifyFastMode;
    }
}
