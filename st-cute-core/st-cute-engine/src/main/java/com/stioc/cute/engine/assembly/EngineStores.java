package com.stioc.cute.engine.assembly;

import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.MessageStore;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 引擎存储对聚合：会话存储与消息存储总是一起注入、一起传递，打包为单一参数。
 * <p>
 * 引擎内组件构造器统一接收本聚合，并在构造器内解包为各自实际需要的存储字段，
 * 组件方法体不感知本聚合的存在。
 * </p>
 */
@Getter
@RequiredArgsConstructor
public class EngineStores {

    /**
     * 会话存储：会话实体的持久化读写出口
     */
    private final ConversationStore conversations;

    /**
     * 消息存储：消息实体的持久化读写出口
     */
    private final MessageStore messages;
}
