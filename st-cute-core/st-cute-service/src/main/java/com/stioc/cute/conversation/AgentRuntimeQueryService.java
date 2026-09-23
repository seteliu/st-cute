package com.stioc.cute.conversation;

import com.stioc.cute.conversation.types.ActiveLlmCallVo;
import com.stioc.cute.conversation.types.ActiveProcessVo;
import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.types.ConversationPatch;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageQuery;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.SortDirection;
import com.stioc.cute.runtime.loop.RuntimeContext;
import com.stioc.cute.tool.commandtool.ActiveProcess;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 智能体运行态查询与联动服务（宿主侧）。
 * <p>
 * 自 {@link com.stioc.cute.controller.ConversationController} 下沉的会话运行态业务：
 * 接入层只保留参数接收与结果封装，上下文收集、级联更新、存活判定与视图装配收口于此。
 * </p>
 * <p>
 * 「主会话 + 其派生子会话」的上下文收集在多个运行态接口中反复出现，统一收敛为
 * {@link #collectRelatedContexts(Long)}，避免各处重复且口径漂移。
 * </p>
 */
@Slf4j
@Service
public class AgentRuntimeQueryService {

    @Resource
    private AgentEngine agentEngine;
    @Resource
    private ConversationService conversationService;

    /**
     * 收集指定会话及其直接派生的子智能体会话的上下文（主上下文在前，子上下文随后）。
     * <p>
     * 子会话判定口径为 {@code ctx.getParentCid()} 等于目标 cid，仅覆盖直接派生一层
     * （孙辈会话由其父会话各自持有，不在同一面板中展开）。
     * </p>
     *
     * @param cid 主会话 ID
     * @return 相关上下文列表（主上下文不存在时仅含子上下文；均不存在时为空列表）
     */
    public List<AgentContext> collectRelatedContexts(Long cid) {
        List<AgentContext> targetContexts = new ArrayList<>();
        AgentContext mainContext = agentEngine.getContextFacade().getActiveContext(cid);
        if (mainContext != null) {
            targetContexts.add(mainContext);
        }
        Collection<AgentContext> allContexts = agentEngine.getContextFacade().getAllContexts();
        for (AgentContext ctx : allContexts) {
            if (cid != null && cid.equals(ctx.getParentCid())) {
                targetContexts.add(ctx);
            }
        }
        return targetContexts;
    }

    /**
     * 级联更新会话及其直接子会话的供应商绑定。
     * <p>
     * 子会话继承父会话的供应商选择：父改则子随，避免子智能体沿用过期模型。
     * 无活动上下文（会话未装载）时不做任何事——落库由引擎事件链内的上下文承载。
     * </p>
     */
    public void cascadeProviderToChildren(Long cid, String providerGroup, String providerModelName) {
        AgentContext context = agentEngine.getContextFacade().getOrCreateContext(cid);
        if (context == null) {
            return;
        }
        agentEngine.getConversationFacade().publishConversationUpdate(context,
                new ConversationPatch(cid).providerGroup(providerGroup).providerModelName(providerModelName));

        List<Conversation> children = conversationService.findByParentCid(cid);
        if (children != null) {
            for (Conversation child : children) {
                AgentContext childContext = agentEngine.getContextFacade().getOrCreateContext(child.getId());
                if (childContext == null) {
                    continue;
                }
                agentEngine.getConversationFacade().publishConversationUpdate(childContext,
                        new ConversationPatch(child.getId())
                                .providerGroup(providerGroup)
                                .providerModelName(providerModelName));
            }
        }
    }

    /**
     * 级联更新会话及其直接子会话的权限模式。
     * <p>
     * 与供应商同理：子会话继承父会话的权限模式，父改则子随。单条子会话更新失败只记日志，
     * 不影响父会话与其余子会话（避免一个异常子会话阻断整轮级联）。
     * </p>
     */
    public void cascadePermissionModeToChildren(Long cid, String permissionMode) {
        AgentContext context = agentEngine.getContextFacade().getOrCreateContext(cid);
        if (context == null) {
            return;
        }
        agentEngine.getConversationFacade().publishConversationUpdate(context,
                new ConversationPatch(cid).permissionMode(permissionMode));

        List<Conversation> children = conversationService.findByParentCid(cid);
        if (children != null) {
            for (Conversation child : children) {
                try {
                    AgentContext childContext = agentEngine.getContextFacade().getOrCreateContext(child.getId());
                    if (childContext == null) {
                        continue;
                    }
                    agentEngine.getConversationFacade().publishConversationUpdate(childContext,
                            new ConversationPatch(child.getId()).permissionMode(permissionMode));
                } catch (Exception e) {
                    log.error("级联更新子会话权限模式失败: childCid={}", child.getId(), e);
                }
            }
        }
    }

    /**
     * 查询指定会话（含派生子会话）名下的全部活动子进程。
     * <p>
     * <b>注意：本方法虽为查询语义，但包含副作用</b>——发现主进程与所有后代均已死亡时，
     * 会顺手把该登记条目从活动映射中移除（懒清理）。这是刻意的内存防累积设计：
     * 进程消亡后无人回来清理，登记表会随会话生命周期持续膨胀。
     * </p>
     */
    public List<ActiveProcessVo> listActiveProcesses(Long cid) {
        List<ActiveProcessVo> resultList = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (AgentContext ctx : collectRelatedContexts(cid)) {
            RuntimeContext runtimeCtx = ctx.extra(RuntimeContext.class);
            if (runtimeCtx == null) {
                continue;
            }
            String title = resolveSessionTitle(ctx.getCid());

            runtimeCtx.getActiveProcesses().forEach((toolCallId, activeProcess) -> {
                Process process = activeProcess.getProcess();
                boolean isAlive = process.isAlive();

                // 存活判定统一走 hasSurvivor：主进程 + 启动追踪名单（childPids）+ MSYS 族徽
                // 收网名单（msysWinPids）三处任一存活即视为有活口——MSYS 孤儿不在 childPids 内，
                // 仅按主进程/childPids 判定会让 PPID 断链孤儿在面板上失明
                if (isAlive) {
                    resultList.add(buildProcessVo(activeProcess, toolCallId, title, process.pid(), now));
                } else if (activeProcess.hasSurvivor()) {
                    // 主进程已死但仍有后代/收网名单成员存活（wrapper 已死、真正的工作进程还在跑）：
                    // 展示存活成员 PID，保留用户手杀通道
                    Long survivorPid = firstAlivePid(activeProcess);
                    if (survivorPid != null) {
                        resultList.add(buildProcessVo(activeProcess, toolCallId, title, survivorPid, now));
                    }
                } else {
                    // 懒清理：主进程与所有后代子进程均已死亡，从活动映射中移除，防止内存累积
                    runtimeCtx.getActiveProcesses().remove(toolCallId);
                }
            });
        }
        return resultList;
    }

    /**
     * 查询指定会话（含派生子会话）名下的全部活动大模型网络请求。
     */
    public List<ActiveLlmCallVo> listActiveLlmCalls(Long cid) {
        List<ActiveLlmCallVo> resultList = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (AgentContext ctx : collectRelatedContexts(cid)) {
            String title = resolveSessionTitle(ctx.getCid());
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
        return resultList;
    }

    /**
     * 统计指定会话对大模型可见消息的累计提示词缓存占比。
     * <p>
     * 逐轮真实用量由引擎统一记录在该轮 ASSISTANT 消息行上（每轮输入含全部历史，逐行累加会重复计数，
     * 故分母取 SUM(input)、分子取 SUM(cached) 再做整体相除——比例恒小于等于逐轮最大值，语义正确）。
     * 查询走 light 轻量投影，仅取两个 bigint 列，规避 content 大 TEXT 字段的溢出页 I/O。
     * </p>
     *
     * @param cid 会话 ID
     * @return 缓存占比（小数制，保留 4 位小数，如 0.8765）；无可见消息或累计输入为 0 时返回 0
     */
    public BigDecimal calculateCacheRatio(Long cid) {
        List<Message> rows = agentEngine.getMessageStore().listByQuery(MessageQuery.builder()
                .cid(cid)
                .visibleToModel(true)
                .role(MessageRole.ASSISTANT)
                .light(true)
                .sortField("id")
                .sortDirection(SortDirection.ASC)
                .build());
        long totalInput = 0;
        long totalCached = 0;
        for (Message row : rows) {
            if (row.getInputTokens() != null) {
                totalInput += row.getInputTokens();
            }
            if (row.getCachedTokens() != null) {
                totalCached += row.getCachedTokens();
            }
        }
        if (totalInput <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(totalCached)
                .divide(BigDecimal.valueOf(totalInput), 4, RoundingMode.HALF_UP);
    }

    /**
     * 强杀指定会话（含派生子会话）名下的子进程。
     * <p>
     * 传入 toolCallId 时只杀该特定进程；否则全杀该会话下的所有子进程。
     * 手杀与自动超时清扫同源（{@link ActiveProcess#destroyForciblyAndVerify()}），
     * 含 MSYS 族徽撒网与延迟复查补杀，故用户手杀同样能确定性触达 PPID 断链的 MSYS 孤儿。
     * </p>
     */
    public void killProcesses(Long cid, String toolCallId) {
        for (AgentContext ctx : collectRelatedContexts(cid)) {
            RuntimeContext runtimeCtx = ctx.extra(RuntimeContext.class);
            if (runtimeCtx == null) {
                continue;
            }

            if (toolCallId != null && !toolCallId.isBlank()) {
                ActiveProcess activeProcess = runtimeCtx.getActiveProcesses().get(toolCallId);
                if (activeProcess != null) {
                    log.info("用户请求单杀会话 {} 的子进程树: ToolCallId={}", ctx.getCid(), toolCallId);
                    activeProcess.destroyForciblyAndVerify();
                    runtimeCtx.getActiveProcesses().remove(toolCallId);
                }
            } else {
                runtimeCtx.getActiveProcesses().forEach((tcId, activeProcess) -> {
                    log.info("用户请求全杀会话 {} 的子进程树: ToolCallId={}", ctx.getCid(), tcId);
                    activeProcess.destroyForciblyAndVerify();
                });
                runtimeCtx.getActiveProcesses().clear();
            }
        }
    }

    /**
     * 装配子进程视图 VO
     */
    private ActiveProcessVo buildProcessVo(ActiveProcess activeProcess, String toolCallId,
                                           String title, Long pid, long now) {
        return ActiveProcessVo.builder()
                .cid(activeProcess.getCid())
                .sessionTitle(title)
                .toolCallId(toolCallId)
                .pid(pid)
                .command(activeProcess.getCommand())
                .cwd(activeProcess.getCwd())
                .startTime(activeProcess.getStartTime())
                .runningTimeMs(now - activeProcess.getStartTime())
                .build();
    }

    /**
     * 解析会话展示标题：查库取标题，查无（如子智能体会话尚未落库）回退通用文案
     */
    private String resolveSessionTitle(Long cid) {
        return conversationService.findById(cid)
                .map(Conversation::getTitle)
                .orElse("子智能体任务");
    }

    /**
     * 返回登记条目中第一个仍存活的进程 PID（优先级：MSYS 收网名单 > 启动追踪名单）。
     * <p>供面板展示"真正还在跑的工作进程"用；全部消亡返回 null。</p>
     */
    private Long firstAlivePid(ActiveProcess activeProcess) {
        List<Long> msysWinPids = activeProcess.getMsysWinPids();
        if (msysWinPids != null) {
            for (Long pid : msysWinPids) {
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    return pid;
                }
            }
        }
        List<Long> childPids = activeProcess.getChildPids();
        if (childPids != null) {
            for (Long pid : childPids) {
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    return pid;
                }
            }
        }
        return null;
    }
}
