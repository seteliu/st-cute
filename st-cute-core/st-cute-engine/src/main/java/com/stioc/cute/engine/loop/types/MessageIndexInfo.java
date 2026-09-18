package com.stioc.cute.engine.loop.types;

import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import lombok.Builder;
import lombok.Getter;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 历史重建消息角色索引信息：承载各角色消息在本次重建集合中的首/末位置（以消息 ID 标识），
 * 供拦截器感知消息序位（如仅对最后一条用户消息完整装载附件）。
 * <p>
 * 由引擎在历史重建前基于 dbMsgs 一次性构建；key 为角色，value 为该角色（按渲染规则过滤后）
 * 首条/末条消息的 ID。某角色无消息时对应 map 不含该 key。
 * </p>
 */
@Getter
@Builder
public class MessageIndexInfo {

    /**
     * 各角色首条消息 ID（按渲染规则过滤后）
     */
    private final Map<MessageRole, Long> firstIndexMap;

    /**
     * 各角色末条消息 ID（按渲染规则过滤后）
     */
    private final Map<MessageRole, Long> lastIndexMap;

    /**
     * 基于 dbMsgs 构建角色索引信息（排除当前轮 ASSISTANT 占位符与按角色渲染规则不参与渲染的消息）
     *
     * @param dbMsgs                重建集合（已完成 visibleToModel 过滤与 COMPRESSED 前置排序）
     * @param excludeAssistantMsgId 当前轮活跃 ASSISTANT 占位消息 ID（可为 null）
     * @return 角色索引信息
     */
    public static MessageIndexInfo buildFrom(List<Message> dbMsgs, Long excludeAssistantMsgId) {
        Map<MessageRole, Long> firstIndexMap = new EnumMap<>(MessageRole.class);
        Map<MessageRole, Long> lastIndexMap = new EnumMap<>(MessageRole.class);
        for (Message msg : dbMsgs) {
            // 排除当前轮 ASSISTANT 占位符
            if (excludeAssistantMsgId != null && excludeAssistantMsgId.equals(msg.getId())) {
                continue;
            }
            if (!renders(msg)) {
                continue;
            }
            firstIndexMap.putIfAbsent(msg.getRole(), msg.getId());
            lastIndexMap.put(msg.getRole(), msg.getId());
        }
        return MessageIndexInfo.builder()
                .firstIndexMap(firstIndexMap)
                .lastIndexMap(lastIndexMap)
                .build();
    }

    /**
     * 判定消息是否参与渲染（与 MessageHistoryAligner 各角色分支的过滤规则一致）
     */
    private static boolean renders(Message msg) {
        MessageStatus status = msg.getStatus() == null ? MessageStatus.SUCCESS : msg.getStatus();
        return switch (msg.getRole()) {
            // 用户消息：取消的不渲染
            case USER -> MessageStatus.CANCELED != status;
            // 助手消息：失败/取消的不渲染
            case ASSISTANT -> MessageStatus.FAILED != status && MessageStatus.CANCELED != status;
            // 子代理汇报：取消的不渲染
            case BRANCH -> MessageStatus.CANCELED != status;
            // 压缩摘要：仅成功的渲染
            case COMPRESSED -> MessageStatus.SUCCESS == status;
            // TOOL 等其余角色全状态渲染
            default -> true;
        };
    }
}
