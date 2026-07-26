package com.stioc.cute.agent.event;

import lombok.Getter;

/**
 * 事件分发优先级层级定义。
 * 对应 EDA 扁平三层：直接层 -> 缓存层 -> 通知层
 */
@Getter
public enum ListenerTier {

    /**
     * 第一层：直接层（包含持久化写盘与内部控制命令，同步执行）
     */
    DIRECT(1),

    /**
     * 第二层：缓存同步层（同步执行）
     */
    CACHE(2),

    /**
     * 第三层：通知推送层（异步执行）
     */
    NOTIFICATION(3);

    /**
     * 执行优先级顺序序号
     */
    private final int order;

    /**
     * 构造层级枚举成员
     */
    ListenerTier(int order) {
        this.order = order;
    }
}
