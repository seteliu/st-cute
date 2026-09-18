package com.stioc.cute.engine.event;

import com.stioc.cute.engine.common.SFunction;
import com.stioc.cute.engine.event.types.AgentEvent;
import com.stioc.cute.engine.event.types.ListenerTier;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.loop.core.AgentContextManager;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessagePatch;
import com.stioc.cute.engine.store.types.MessageQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 引擎第一层：Direct 监听器（DIRECT，同步写库）。
 * <p>
 * 引擎自闭环的核心：事件 → 物理落库的翻译层内置引擎。差量载荷（Patch）
 * 在此翻译为 Store 原子方法调用完成写盘，宿主不再实现任何落库逻辑。
 * 执行失败抛出 RuntimeException 熔断阻断后续层流转。
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class EngineDirectListener implements AgentEventListener {

    private static final List<SFunction<Conversation, String>> DELTA_SET_FIELDS = List.of(
            Conversation::getWaitingToolIds,
            Conversation::getWaitingSubCids
    );

    private final ConversationStore conversationStore;
    private final MessageStore messageStore;
    private AgentContextManager contextManager;

    /**
     * 两段式绑定上下文管理器（解耦 Builder 构造循环依赖）
     */
    public void bindContextManager(AgentContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @Override
    public ListenerTier getTier() {
        return ListenerTier.DIRECT;
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (event == null || event.getType() == null) {
            return;
        }

        switch (event.getType()) {
            // 持久化写命令
            case CONVERSATION_CREATE -> handleConversationCreate(event);
            case CONVERSATION_UPDATE -> handleConversationUpdate(event);
            case CONVERSATION_DELETE -> handleConversationDelete(event);
            case MESSAGE_CREATE -> handleMessageCreate(event);
            case MESSAGE_UPDATE -> handleMessageUpdate(event);
            case MESSAGE_DELETE -> handleMessageDelete(event);

            default -> {
                // 流式思考等非持久化事件在此层静默过路
            }
        }
    }

    private void handleConversationCreate(AgentEvent event) {
        if (event.getPayload() instanceof Conversation entity) {
            log.debug("EnginePersistence: 持久化新建会话: cid={}", entity.getId());
            conversationStore.insert(entity);
            if (contextManager != null) {
                AgentContext context = contextManager.getOrCreateContext(entity.getId());
                event.setAgentContext(context);
            }
        }
    }

    private void handleConversationUpdate(AgentEvent event) {
        if (!(event.getPayload() instanceof ConversationPatch patch)) {
            return;
        }
        Long cid = patch.getId();
        log.debug("EnginePersistence: 更新会话数据持久化: cid={}", cid);

        // 1. 非 null 的差量集合字段委托 Store 原子方法扣减（"+id"/"-id"/全量直写语义，原子性由引擎事件锁保证）；
        //    get 返回 null（key 不存在，或显式置 null 表达清空）时不剥离，留给 updateByPatch 处置
        for (SFunction<Conversation, String> fieldGetter : DELTA_SET_FIELDS) {
            String delta = patch.get(fieldGetter);
            if (delta != null) {
                conversationStore.updateByDelta(cid, fieldGetter, delta);
                patch.remove(fieldGetter);
            }
        }
        // 2. 剩余字段以差量载荷整体下推：普通字段含 null 值清空语义，
        //    值为 null 的集合字段同样留在载荷中经 updateByPatch 置 null 落库（null = 清空）
        if (patch.isEmpty()) {
            return;
        }
        conversationStore.updateByPatch(patch);
    }

    private void handleConversationDelete(AgentEvent event) {
        if (event.getPayload() instanceof Long cid) {
            log.debug("EnginePersistence: 物理删除会话及其级联消息: cid={}", cid);
            messageStore.deleteByQuery(MessageQuery.builder().cid(cid).build());
            conversationStore.deleteById(cid);
        }
    }

    private void handleMessageCreate(AgentEvent event) {
        if (event.getPayload() instanceof Message entity) {
            log.debug("EnginePersistence: 持久化新消息: id={}, role={}", entity.getId(), entity.getRole());
            messageStore.insert(entity);
        }
    }

    private void handleMessageUpdate(AgentEvent event) {
        if (event.getPayload() instanceof MessagePatch patch) {
            // 更新失败（影响 0 行）说明目标消息不存在或无字段变更，ERROR 留痕便于发现静默失败
            int updated = messageStore.updateByPatch(patch);
            if (updated <= 0) {
                log.error("MESSAGE_UPDATE 落库影响行数为 0: msgId={}", patch.getId());
            }
        }
    }

    private void handleMessageDelete(AgentEvent event) {
        if (event.getPayload() instanceof Message entity) {
            log.debug("EnginePersistence: 删除会话 {} 中 ID 大于 {} 的消息", entity.getCid(), entity.getId());
            messageStore.deleteByQuery(MessageQuery.builder()
                    .cid(entity.getCid())
                    .greaterThanId(entity.getId())
                    .build());
        }
    }
}
