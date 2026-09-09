package com.stioc.cute.controller;

import com.stioc.cute.platform.common.Result;
import com.stioc.cute.message.MessageService;
import com.stioc.cute.message.types.MessageVo;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.message.types.SendMessageDto;
import com.stioc.cute.message.types.LimitMessageDto;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import com.stioc.cute.conversation.types.ApproveToolDto;
import com.stioc.cute.conversation.ConversationService;
import com.stioc.cute.runtime.loop.RuntimeContext;
import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.types.ToolApprovalRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import com.stioc.cute.platform.common.BusinessException;

/**
 * 消息与执行控制 API 层
 */
@Slf4j
@RestController
@RequestMapping("/api/message")
public class MessageController {

    @Resource
    private MessageService messageService;
    @Resource
    private AgentEngine agentEngine;

    /**
     * 获取会话的消息历史列表
     *
     * @param folded 是否折叠视图（缺省 true）：true=被折叠的中间步骤聚合为 FOLDED 虚拟消息；
     *               false=原始平铺（配合 minId/maxId 供前端点开折叠详情时范围查询完整明细）
     * @param minId  折叠详情范围查询：闭区间最小消息 ID（仅 folded=false 生效）
     * @param maxId  折叠详情范围查询：闭区间最大消息 ID（仅 folded=false 生效）
     */
    @GetMapping("/list")
    public Result<LimitMessageDto> getConversationMessages(
            @RequestParam Long cid,
            @RequestParam(required = false, defaultValue = "true") boolean folded,
            @RequestParam(required = false) Long minId,
            @RequestParam(required = false) Long maxId) {
        LimitMessageDto list = messageService.getConversationMessages(cid, folded, minId, maxId);

        // 附加回填：流式输出期间刷新页面时，RUNNING 状态的 ASSISTANT 消息在 DB 中尚是空壳，
        // 从内存流式缓存中补齐已累积的思考/正文内容，避免过程中内容丢失带来的困惑。
        // 竞态窗口内快照可能落后 1~2 个 chunk，属可接受语义竞态，流结束后的终态全量刷新保证最终一致
        // 折叠视图下折叠块永不含 RUNNING 消息（RUNNING 属于外露尾部），回填逻辑天然无冲突
        AgentContext ctx = agentEngine.getContextFacade().getActiveContext(cid);
        if (ctx != null) {
            for (MessageVo vo : list.getMessages()) {
                if (MessageRole.ASSISTANT != vo.getRole() || MessageStatus.RUNNING != vo.getStatus()) {
                    continue;
                }
                String content = ctx.snapshotStreamText(false, vo.getId());
                if (content != null && !content.isEmpty()) {
                    vo.setContent(content);
                }
                String thought = ctx.snapshotStreamText(true, vo.getId());
                if (thought != null && !thought.isEmpty()) {
                    vo.setThought(thought);
                }
            }
        }

        return Result.success(list);
    }

    /**
     * 清空当前会话的上下文消息历史
     */
    @PostMapping("/clear")
    public Result<Void> clearConversationMessages(@RequestParam Long cid) {
        log.info("请求清空会话历史: cid={}", cid);
        agentEngine.getConversationFacade().clearConversation(cid);

        // 仅保留宿主专属伴生上下文中的读取哈希门禁清理
        AgentContext ctx = agentEngine.getContextFacade().getActiveContext(cid);
        if (ctx != null) {
            RuntimeContext runtimeCtx = ctx.extra(RuntimeContext.class);
            if (runtimeCtx != null) {
                runtimeCtx.getReadFiles().clear();
            }
        }
        return Result.success();
    }

    /**
     * 回退并重置到指定消息节点
     */
    @PostMapping("/reset")
    public Result<Void> resetConversationMessages(@RequestParam Long cid, @RequestParam Long messageId) {
        log.info("请求重置会话消息历史: cid={}, messageId={}", cid, messageId);
        agentEngine.getConversationFacade().resetConversationMessages(cid, messageId);
        return Result.success();
    }

    /**
     * 发送新用户消息，触发 ReAct 执行循环
     */
    @PostMapping("/send")
    public Result<Void> sendMessage(@RequestParam Long cid, @RequestBody SendMessageDto body) {
        String text = body.getText();
        if (!StringUtils.hasText(text)) {
            throw new BusinessException("消息内容不能为空");
        }
        agentEngine.getLoopFacade().submitUserMessage(cid, text, body.getAttachments());
        return Result.success();
    }

    /**
     * 重试指定消息
     */
    @PostMapping("/retry")
    public Result<Void> retryMessage(
            @RequestParam Long cid,
            @RequestParam Long messageId) {
        agentEngine.getLoopFacade().retryMessage(cid, messageId);
        return Result.success();
    }

    /**
     * 人在回路审批决策提交
     */
    @PostMapping("/approve")
    public Result<Boolean> approveTool(@RequestParam Long cid, @RequestBody ApproveToolDto body) {
        ToolApprovalRequest request = ToolApprovalRequest.builder()
                .cid(cid)
                .toolCallId(body.getId())
                .decision(body.getDecision())
                .alwaysAllow(Boolean.TRUE.equals(body.getAlwaysAllow()))
                .toolName(body.getToolName())
                .contentPattern(body.getContentPattern())
                .customArgOverride(body.getCustomArgOverride())
                .build();

        boolean approved = agentEngine.getToolFacade().approveTool(request);
        return Result.success(approved);
    }

    /**
     * 获取指定消息的详细信息 (前端点击日志时按需调用)
     */
    @GetMapping("/detail")
    public Result<MessageVo> getMessageDetail(@RequestParam Long messageId) {
        log.info("请求查询消息详情: messageId={}", messageId);
        Message entity = messageService.findById(messageId)
                .orElseThrow(() -> new BusinessException("未找到指定的消息，ID: " + messageId));

        return Result.success(MessageVo.fromEntity(entity));
    }
}
