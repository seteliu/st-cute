package com.stioc.cute.entrypoint.http;

import com.stioc.cute.platform.common.Result;
import com.stioc.cute.message.access.MessageService;
import com.stioc.cute.message.access.MessageVo;
import com.stioc.cute.message.access.MessageEntity;
import com.stioc.cute.message.access.SendMessageDto;
import com.stioc.cute.message.access.LimitMessageDto;
import com.stioc.cute.conversation.access.ApproveToolDto;
import com.stioc.cute.conversation.access.ConversationService;
import com.stioc.cute.agent.access.ToolApprovalService;
import com.stioc.cute.agent.access.AgentContextManager;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.agent.access.AgentLoopCoordinator;
import com.stioc.cute.agent.event.AgentEventFactory;
import com.stioc.cute.agent.ChatNamingHelper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import com.stioc.cute.platform.common.BusinessException;

import java.util.List;

/**
 * 消息与执行控制 API 层
 */
@Slf4j
@RestController
@RequestMapping("/api/message")
public class MessageApi {

    @Resource
    private MessageService messageService;
    @Resource
    private ToolApprovalService toolApprovalService;
    @Resource
    private ConversationService conversationService;
    @Resource
    private AgentContextManager agentContextManager;
    @Resource
    private AgentLoopCoordinator agentLoopCoordinator;
    @Resource
    private ChatNamingHelper chatNamingHelper;

    /**
     * 获取会话的全部消息历史列表
     */
    @GetMapping("/list")
    public Result<LimitMessageDto> getConversationMessages(@RequestParam Long cid) {
        LimitMessageDto list = messageService.getConversationMessages(cid);
        return Result.success(list);
    }

    /**
     * 清空当前会话的上下文消息历史
     */
    @PostMapping("/clear")
    public Result<Void> clearConversationMessages(@RequestParam Long cid) {
        log.info("请求清空会话历史: cid={}", cid);
        messageService.clearConversationMessages(cid);
        AgentContext ctx = agentContextManager.getOrCreateContext(cid);
        conversationService.clearConversationStatus(ctx);

        // 刷新重置运行时内存 Context 状态指标
        if (ctx != null) {
            ctx.setInputTokens(0);
            ctx.setOutputTokens(0);
            ctx.setCachedTokens(0);
            ctx.setIterationCount(0);
            ctx.getReadFiles().clear();
        }
        return Result.success();
    }

    /**
     * 回退并重置到指定消息节点
     */
    @PostMapping("/reset")
    public Result<Void> resetConversationMessages(@RequestParam Long cid, @RequestParam Long messageId) {
        log.info("请求重置会话消息历史: cid={}, messageId={}", cid, messageId);
        AgentContext context = agentContextManager.getOrCreateContext(cid);
        messageService.resetConversationMessages(context, messageId);
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
        AgentContext context = agentContextManager.getOrCreateContext(cid);
        // 用户主动发送新消息，清除 canceled 标记，开启新一轮推理
        context.setCanceled(false);
        messageService.sendMessage(context, text, body.getAttachments());
        agentLoopCoordinator.executeLoopAsync(cid, () -> chatNamingHelper.autoRenameChatIfNew(cid));
        return Result.success();
    }

    /**
     * 重试指定消息
     */
    @PostMapping("/retry")
    public Result<Void> retryMessage(
            @RequestParam Long cid,
            @RequestParam Long messageId) {
        AgentContext context = agentContextManager.getOrCreateContext(cid);
        // 用户主动重试，清除 canceled 标记，开启新一轮推理
        context.setCanceled(false);
        messageService.retryMessage(context, messageId);
        agentLoopCoordinator.executeLoopAsync(cid);
        return Result.success();
    }

    /**
     * 人在回路审批决策提交
     */
    @PostMapping("/approve")
    public Result<Boolean> approveTool(@RequestParam Long cid, @RequestBody ApproveToolDto body) {
        String toolCallId = body.getId();
        String decision = body.getDecision();
        boolean alwaysAllow = body.getAlwaysAllow() != null ? body.getAlwaysAllow() : false;
        String toolName = body.getToolName();
        String contentPattern = body.getContentPattern();
        String customArgOverride = body.getCustomArgOverride();

        boolean approved = toolApprovalService.approveTool(
                cid, toolCallId, decision, alwaysAllow, toolName, contentPattern, customArgOverride);
        return Result.success(approved);
    }

    /**
     * 获取指定消息的详细信息 (前端点击日志时按需调用，支持带回大日志)
     */
    @GetMapping("/detail")
    public Result<MessageVo> getMessageDetail(@RequestParam Long messageId) {
        log.info("请求查询消息详情: messageId={}", messageId);
        MessageEntity entity = messageService.findById(messageId)
                .orElseThrow(() -> new BusinessException("未找到指定的消息，ID: " + messageId));

        // 显式传入 includeRawContent = true，查询并带回大日志
        MessageVo vo = MessageVo.fromEntity(entity, true);
        return Result.success(vo);
    }
}
