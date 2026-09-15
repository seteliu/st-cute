package com.stioc.cute.controller;

import com.stioc.cute.platform.common.Result;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.conversation.types.ActiveLlmCallVo;
import com.stioc.cute.conversation.types.ActiveProcessVo;
import com.stioc.cute.conversation.ConversationService;
import com.stioc.cute.conversation.types.UpdateConfigDto;
import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.runtime.loop.RuntimeContext;
import com.stioc.cute.tool.types.ActiveProcess;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import okhttp3.Call;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 历史会话及消息的 HTTP REST API
 */
@Slf4j
@RestController
@RequestMapping("/api/conversation")
public class ConversationController {

    @Resource
    private ConversationService conversationService;
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
     * 变更会话当前绑定的供应商和具体模型
     */
    @PostMapping("/update-provider")
    public Result<Boolean> updateConversationProvider(
            @RequestParam Long id,
            @RequestParam String providerGroup,
            @RequestParam(required = false, defaultValue = "") String providerModelName) {
        log.info("请求修改对话会话 {} 的供应商分组为: {}, 模型为: {}", id, providerGroup, providerModelName);

        AgentContext context = agentEngine.getContextFacade().getOrCreateContext(id);
        if (context != null) {
            ConversationPatch updatePayload = new ConversationPatch(id)
                    .providerGroup(providerGroup)
                    .providerModelName(providerModelName);
            agentEngine.getConversationFacade().publishConversationUpdate(context, updatePayload);

            // 联动更新直接子会话的 provider
            List<Conversation> children = conversationService.findByParentCid(id);
            if (children != null) {
                for (Conversation child : children) {
                    AgentContext childContext = agentEngine.getContextFacade().getOrCreateContext(child.getId());
                    ConversationPatch childUpdatePayload = new ConversationPatch(child.getId())
                            .providerGroup(providerGroup)
                            .providerModelName(providerModelName);
                    agentEngine.getConversationFacade().publishConversationUpdate(childContext, childUpdatePayload);
                }
            }
        }
        return Result.success(true);
    }

    /**
     * 修改并应用会话的运行属性（如权限级别）
     */
    @PostMapping("/config")
    public Result<Boolean> updateConversationConfig(
            @RequestParam Long id,
            @RequestBody UpdateConfigDto body) {
        log.info("请求修改对话会话 {} 的配置: {}", id, body);

        AgentContext context = agentEngine.getContextFacade().getOrCreateContext(id);
        if (context != null && body.getPermissionMode() != null) {
            try {
                ConversationPatch updatePayload = new ConversationPatch(id)
                        .permissionMode(body.getPermissionMode());
                agentEngine.getConversationFacade().publishConversationUpdate(context, updatePayload);

                // 联动更新直接子会话的 config
                List<Conversation> children = conversationService.findByParentCid(id);
                if (children != null) {
                    for (Conversation child : children) {
                        AgentContext childContext = agentEngine.getContextFacade().getOrCreateContext(child.getId());
                        ConversationPatch childUpdatePayload = new ConversationPatch(child.getId())
                                .permissionMode(body.getPermissionMode());
                        agentEngine.getConversationFacade().publishConversationUpdate(childContext, childUpdatePayload);
                    }
                }
            } catch (Exception e) {
                log.error("修改权限配置出错: id={}", id, e);
            }
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
            ConversationPatch updatePayload = new ConversationPatch(id)
                    .title(title);
            agentEngine.getConversationFacade().publishConversationUpdate(context, updatePayload);
        }
        return Result.success();
    }


    /**
     * 查询指定会话（含派生的子代理会话）名下的所有活动子进程
     */
    @GetMapping("/processes")
    public Result<List<ActiveProcessVo>> getActiveProcesses(@RequestParam Long id) {
        List<ActiveProcessVo> resultList = new ArrayList<>();

        Collection<AgentContext> allContexts = agentEngine.getContextFacade().getAllContexts();
        List<AgentContext> targetContexts = new ArrayList<>();

        AgentContext mainContext = agentEngine.getContextFacade().getActiveContext(id);
        if (mainContext != null) {
            targetContexts.add(mainContext);
        }

        // 收集子代理会话的上下文
        for (AgentContext ctx : allContexts) {
            if (id.equals(ctx.getParentCid())) {
                targetContexts.add(ctx);
            }
        }

        long now = System.currentTimeMillis();
        for (AgentContext ctx : targetContexts) {
            String title = conversationService.findById(ctx.getCid())
                    .map(Conversation::getTitle)
                    .orElse("子智能体任务");

            RuntimeContext runtimeCtx = ctx.extra(RuntimeContext.class);
            if (runtimeCtx == null) {
                continue;
            }

            runtimeCtx.getActiveProcesses().forEach((toolCallId, activeProcess) -> {
                Process process = activeProcess.getProcess();
                boolean isAlive = process.isAlive();
                Long actualPid = process.pid();

                // 若主进程已退出（可能是wrapper进程已死），检查后代子孙进程是否依然在运行
                if (!isAlive && activeProcess.getChildPids() != null) {
                    for (Long childPid : activeProcess.getChildPids()) {
                        if (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)) {
                            isAlive = true;
                            actualPid = childPid; // 指向依然存活的真正工作进程PID
                            break;
                        }
                    }
                }

                if (isAlive) {
                    resultList.add(ActiveProcessVo.builder()
                            .cid(activeProcess.getCid())
                            .sessionTitle(title)
                            .toolCallId(toolCallId)
                            .pid(actualPid)
                            .command(activeProcess.getCommand())
                            .cwd(activeProcess.getCwd())
                            .startTime(activeProcess.getStartTime())
                            .runningTimeMs(now - activeProcess.getStartTime())
                            .build());
                } else {
                    // 🌟 懒清理：一旦发现主进程和所有后代子进程均已死亡，将其从活动映射中移除，防止内存累积
                    runtimeCtx.getActiveProcesses().remove(toolCallId);
                }
            });
        }

        return Result.success(resultList);
    }

    /**
     * 强杀指定会话（含派生的子会话）名下的子进程。
     * 如果传入 toolCallId，则只杀该特定进程；否则全杀该会话下的所有子进程。
     */
    @PostMapping("/processes/kill")
    public Result<Boolean> killProcess(
            @RequestParam Long id,
            @RequestParam(required = false) String toolCallId) {

        Collection<AgentContext> allContexts = agentEngine.getContextFacade().getAllContexts();
        List<AgentContext> targetContexts = new ArrayList<>();

        AgentContext mainContext = agentEngine.getContextFacade().getActiveContext(id);
        if (mainContext != null) {
            targetContexts.add(mainContext);
        }

        for (AgentContext ctx : allContexts) {
            if (id.equals(ctx.getParentCid())) {
                targetContexts.add(ctx);
            }
        }

        for (AgentContext ctx : targetContexts) {
            RuntimeContext runtimeCtx = ctx.extra(RuntimeContext.class);
            if (runtimeCtx == null) {
                continue;
            }

            if (toolCallId != null && !toolCallId.isBlank()) {
                ActiveProcess activeProcess = runtimeCtx.getActiveProcesses().get(toolCallId);
                if (activeProcess != null) {
                    log.info("用户请求单杀会话 {} 的子进程树: ToolCallId={}", ctx.getCid(), toolCallId);
                    activeProcess.destroyForcibly();
                    runtimeCtx.getActiveProcesses().remove(toolCallId);
                }
            } else {
                runtimeCtx.getActiveProcesses().forEach((tcId, activeProcess) -> {
                    log.info("用户请求全杀会话 {} 的子进程树: ToolCallId={}", ctx.getCid(), tcId);
                    activeProcess.destroyForcibly();
                });
                runtimeCtx.getActiveProcesses().clear();
            }
        }

        return Result.success(true);
    }

    /**
     * 查询指定会话（含派生的子代理会话）名下的所有活动大模型网络请求
     */
    @GetMapping("/llm-calls")
    public Result<List<ActiveLlmCallVo>> getActiveLlmCalls(@RequestParam Long id) {
        List<ActiveLlmCallVo> resultList = new ArrayList<>();

        Collection<AgentContext> allContexts = agentEngine.getContextFacade().getAllContexts();
        List<AgentContext> targetContexts = new ArrayList<>();

        AgentContext mainContext = agentEngine.getContextFacade().getActiveContext(id);
        if (mainContext != null) {
            targetContexts.add(mainContext);
        }

        // 收集子代理会话的上下文
        for (AgentContext ctx : allContexts) {
            if (id.equals(ctx.getParentCid())) {
                targetContexts.add(ctx);
            }
        }

        long now = System.currentTimeMillis();
        for (AgentContext ctx : targetContexts) {
            String title = conversationService.findById(ctx.getCid())
                    .map(Conversation::getTitle)
                    .orElse("子智能体任务");

            ctx.getActiveLlmCalls().forEach((llmCallId, activeCall) -> {
                Call call = activeCall.getCall();
                if (call != null && !call.isCanceled()) {
                    resultList.add(ActiveLlmCallVo.builder()
                            .cid(activeCall.getCid())
                            .sessionTitle(title)
                            .llmCallId(llmCallId)
                            .model(activeCall.getModel())
                            .startTime(activeCall.getStartTime())
                            .durationTimeMs(now - activeCall.getStartTime())
                            .build());
                }
            });
        }

        return Result.success(resultList);
    }
}
