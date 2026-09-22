package com.stioc.cute.websocket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket 双向物理网络传输帧数据包封装类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketEvent {

    /**
     * 该网络帧包事件唯一 UUID
     */
    private String eventId;

    /**
     * 该网络帧包所属的会话 ID
     */
    private Long cid;

    /**
     * 本次绑定时请求顺带解绑的旧会话 ID（可选，前端决策）。
     * <p>
     * 后端天然支持一个物理连接同时绑定多个 cid（多会话并行订阅）；是否在切换时
     * 解绑旧 cid 由前端显式传入：null/缺省表示不解除任何绑定。典型用法：
     * 单会话视图切换会话时 PING 帧携带 unbindCid=旧cid 实现绑定迁移，
     * 未来多会话并行场景省略该字段即可保留多个绑定。
     * </p>
     */
    private Long unbindCid;

    /**
     * 该网络帧包关联的父会话 ID
     */
    private Long parentCid;

    /**
     * 网络帧包事件戳
     */
    private Long timestamp;

    /**
     * 网络传输的事件行为类型名（如 S2C_MESSAGE_CREATED / PING）
     */
    private String type;

    /**
     * 绑定的具体数据载荷
     */
    private Object payload;
}
