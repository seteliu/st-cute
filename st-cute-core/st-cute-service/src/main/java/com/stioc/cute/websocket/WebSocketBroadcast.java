package com.stioc.cute.websocket;

import com.alibaba.fastjson2.JSON;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 广播契约定义，用于集中管理全局广播事件
 *
 * @author 61jun.com
 */
@Slf4j
@Component
public class WebSocketBroadcast {

    /**
     * 全局广播事件类型枚举
     */
    @Getter
    public enum EventType {
        PROJECT_CREATED("S2C_PROJECT_CREATED"),
        PROJECT_DELETED("S2C_PROJECT_DELETED"),
        CONFIG_UPDATED("S2C_CONFIG_UPDATED"),
        PROVIDERS_UPDATED("S2C_PROVIDERS_UPDATED"),
        CONVERSATION_CREATED("S2C_CONVERSATION_CREATED"),
        CONVERSATION_UPDATED("S2C_CONVERSATION_UPDATED"),
        CONVERSATION_DELETED("S2C_CONVERSATION_DELETED"),
        MCP_UPDATED("S2C_MCP_UPDATED");

        private final String value;

        EventType(String value) {
            this.value = value;
        }
    }

    /**
     * 发送全局广播事件（无特定会话 ID）
     *
     * @param type    广播事件类型
     * @param payload 携带的数据载荷
     */
    public void broadcast(EventType type, Object payload) {
        broadcast(type, null, payload);
    }

    /**
     * 发送全局广播事件（支持指定关联合话 ID）
     *
     * @param type    广播事件类型
     * @param cid     关联的会话 ID（可为 null）
     * @param payload 携带的数据载荷
     */
    public void broadcast(EventType type, Long cid, Object payload) {
        if (type == null) {
            return;
        }
        try {
            WebSocketEvent wsEvent = WebSocketEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .cid(cid)
                    .timestamp(System.currentTimeMillis())
                    .type(type.getValue())
                    .payload(payload)
                    .build();

            String jsonString = JSON.toJSONString(wsEvent);
            WebSocketSessionManager.broadcast(jsonString);
            log.debug("已发送全局广播事件: type={}, cid={}", type.getValue(), cid);
        } catch (Exception e) {
            log.error("发送全局广播事件失败: type={}, cid={}", type.getValue(), cid, e);
        }
    }
}
