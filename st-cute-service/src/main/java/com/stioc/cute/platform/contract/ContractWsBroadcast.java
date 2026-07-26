package com.stioc.cute.platform.contract;

import com.alibaba.fastjson2.JSON;
import com.stioc.cute.entrypoint.websocket.WebSocketEvent;
import com.stioc.cute.entrypoint.websocket.WebSocketSessionManager;
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
public class ContractWsBroadcast {

    /**
     * 全局广播事件类型枚举
     */
    @Getter
    public enum EventType {
        PROJECT_CREATED("S2C_PROJECT_CREATED"),
        PROJECT_DELETED("S2C_PROJECT_DELETED"),
        CONFIG_UPDATED("S2C_CONFIG_UPDATED"),
        PROVIDERS_UPDATED("S2C_PROVIDERS_UPDATED");

        private final String value;

        EventType(String value) {
            this.value = value;
        }
    }

    /**
     * 发送全局广播事件
     *
     * @param type    广播事件类型
     * @param payload 携带的数据载荷
     */
    public void broadcast(EventType type, Object payload) {
        if (type == null) {
            return;
        }
        try {
            WebSocketEvent wsEvent = WebSocketEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .cid(0L) // 全局广播无特定会话 ID
                    .timestamp(System.currentTimeMillis())
                    .type(type.getValue())
                    .payload(payload)
                    .build();

            String jsonString = JSON.toJSONString(wsEvent);
            WebSocketSessionManager.broadcast(jsonString);
            log.info("已发送全局广播事件: type={}", type.getValue());
        } catch (Exception e) {
            log.error("发送全局广播事件失败: type={}", type.getValue(), e);
        }
    }
}
