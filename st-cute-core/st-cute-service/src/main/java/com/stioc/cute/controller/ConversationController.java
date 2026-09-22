package com.stioc.cute.controller;

import com.stioc.cute.platform.common.Result;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.conversation.AgentRuntimeQueryService;
import com.stioc.cute.conversation.types.ActiveLlmCallVo;
import com.stioc.cute.conversation.types.ActiveProcessVo;
import com.stioc.cute.conversation.ConversationService;
import com.stioc.cute.conversation.types.UpdateConfigDto;
import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.loop.core.AgentContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 历史会话及消息的 HTTP REST API。
 * <p>
 * 薄接入层：只负责参数接收、业务转发与结果封装；会话运行态的上下文收集、级联更新、
 * 进程存活判定与视图装配下沉至 {@link AgentRuntimeQueryService}，本类不承载业务编排。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/conversation")
public class ConversationController {

    @Resource
    private ConversationService conversationService;
    @Resource
    private AgentRuntimeQueryService agentRuntimeQueryService;
    @Resource
    private AgentEngine agentEngine;

    /**
     * 获取全部会话历史实体列表
     */
    @GetMapping("/list")
    public Result<List<Conversation>> getConversations() {
        List<Conversation> list = conversationService.getConversations();
        return Result.success(list);
    }

    /**
     * 创建物理隔离的新对话会话记录
     */
    @PostMapping("/create")
    public Result<Conversation> createConversation(@RequestBody Conversation conversation) {
        log.info("请求新建对话会话: {}", conversation);
        Conversation created = conversationService.createConversation(conversation);
        return Result.success(created);
    }

    /**
     * 变更会话当前绑定的供应商和具体模型（级联同步至直接子会话）
     */
    @PostMapping("/update-provider")
    public Result<Boolean> updateConversationProvider(
            @RequestParam Long id,
            @RequestParam String providerGroup,
            @RequestParam(required = false, defaultValue = "") String providerModelName) {
        log.info("请求修改对话会话 {} 的供应商分组为: {}, 模型为: {}", id, providerGroup, providerModelName);

        agentRuntimeQueryService.cascadeProviderToChildren(id, providerGroup, providerModelName);
        return Result.success(true);
    }

    /**
     * 修改并应用会话的运行属性（如权限级别），级联同步至直接子会话
     */
    @PostMapping("/config")
    public Result<Boolean> updateConversationConfig(
            @RequestParam Long id,
            @RequestBody UpdateConfigDto body) {
        log.info("请求修改对话会话 {} 的配置: {}", id, body);

        if (body != null && body.getPermissionMode() != null) {
            agentRuntimeQueryService.cascadePermissionModeToChildren(id, body.getPermissionMode());
        }
        return Result.success(true);
    }

    /**
     * 级联物理删除指定的会话及底层的全部消息
     */
    @DeleteMapping(value = "/delete")
    public Result<Boolean> deleteConversation(@RequestParam Long id) {
        log.info("请求物理删除对话会话: {}", id);
        conversationService.deleteConversation(id);
        return Result.success(true);
    }

    /**
     * 批量级联物理删除指定的多个会话及底层消息
     */
    @PostMapping(value = "/batch-delete")
    public Result<Boolean> batchDeleteConversations(@RequestBody List<Long> ids) {
        log.info("请求批量物理删除对话会话: {}", ids);
        if (ids != null && !ids.isEmpty()) {
            conversationService.deleteConversations(ids);
        }
        return Result.success(true);
    }

    /**
     * 停止执行接口。由 AgentLoopCoordinator 进行多线程强行中断抢占与 DB 快照更新
     */
    @PostMapping("/cancel")
    public Result<Boolean> cancelContext(@RequestParam Long id) {
        log.info("请求停止对话会话执行 Loop: id={}", id);
        agentEngine.getLoopFacade().forceStopLoop(id);
        return Result.success(true);
    }

    /**
     * 重命名会话
     */
    @PostMapping("/rename")
    public Result<Void> renameConversation(@RequestParam Long id, @RequestParam String title) {
        log.info("请求修改对话会话 {} 的标题为: {}", id, title);
        AgentContext context = agentEngine.getContextFacade().getOrCreateContext(id);
        if (context != null) {
            ConversationPatch updatePayload = new ConversationPatch(id).title(title);
            agentEngine.getConversationFacade().publishConversationUpdate(context, updatePayload);
        }
        return Result.success();
    }

    /**
     * 查询指定会话（含派生的子代理会话）名下的所有活动子进程
     */
    @GetMapping("/processes")
    public Result<List<ActiveProcessVo>> getActiveProcesses(@RequestParam Long id) {
        return Result.success(agentRuntimeQueryService.listActiveProcesses(id));
    }

    /**
     * 强杀指定会话（含派生的子会话）名下的子进程。
     * 如果传入 toolCallId，则只杀该特定进程；否则全杀该会话下的所有子进程。
     */
    @PostMapping("/processes/kill")
    public Result<Boolean> killProcess(
            @RequestParam Long id,
            @RequestParam(required = false) String toolCallId) {
        agentRuntimeQueryService.killProcesses(id, toolCallId);
        return Result.success(true);
    }

    /**
     * 查询指定会话（含派生的子代理会话）名下的所有活动大模型网络请求
     */
    @GetMapping("/llm-calls")
    public Result<List<ActiveLlmCallVo>> getActiveLlmCalls(@RequestParam Long id) {
        return Result.success(agentRuntimeQueryService.listActiveLlmCalls(id));
    }
}
