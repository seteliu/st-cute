package com.stioc.cute.entrypoint.websocket;

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
