package com.stioc.cute.agent.access;

import com.stioc.cute.agent.event.AgentEvent;
import com.stioc.cute.agent.event.AgentEventListener;
import com.stioc.cute.agent.event.ListenerTier;
import com.stioc.cute.security.access.PermissionMode;
import com.stioc.cute.skill.access.Skill;
import com.stioc.cute.hook.access.HookRule;
import com.stioc.cute.mcp.access.McpClientInstance;
import com.stioc.cute.platform.common.CommonThread;
import com.stioc.cute.platform.contract.ContractLock;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.Getter;
import lombok.AccessLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.Call;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

/**
 * 维护每个 Agent 运行实例的实时对话会话控制状态与计量信息
 */
@Data
@RequiredArgsConstructor
public class AgentContext {

    private static final Logger log = LoggerFactory.getLogger(AgentContext.class);

    /**
     * 会话唯一 ID
     */
    private final Long cid;

    /**
     * 当前会话下正在同步运行的外部子进程容器 (toolCallId -> ActiveProcess)
     */
    private final Map<String, ActiveProcess> activeProcesses = new ConcurrentHashMap<>();

    /**
     * 当前会话正处于活动状态的大模型 HTTP 请求连接引用
     */
    private volatile Call activeLlmCall = null;

    /**
     * 当前会话下所有活跃的大模型 HTTP 请求容器 (llmCallId -> ActiveLlmCall)
     */
    private final Map<String, ActiveLlmCall> activeLlmCalls = new ConcurrentHashMap<>();

    /**
     * 当前 ReAct 循环是否已被用户强行取消/打断
     */
    private volatile boolean canceled = false;

    /**
     * 当前 ReAct 循环是否正在运行中
     */
    private volatile boolean loopRunning = false;

    /**
     * 当前正处于活跃状态（正在生成文本或等待工具回调）的助理消息 ID
     */
    private volatile Long activeAssistantMsgId = null;

    /**
     * 当前会话关联的正在执行的 Loop 线程，用于并发时驱逐旧执行
     */
    private volatile Thread activeThread = null;

    /**
     * 最近一次 LLM 调用返回的输入 token 快照。
     */
    private volatile long inputTokens = 0;

    /**
     * 最近一次 LLM 调用返回的输出 token 快照。
     */
    private volatile long outputTokens = 0;

    /**
     * 最近一次 LLM 调用返回的缓存 token 快照。
     */
    private volatile long cachedTokens = 0;

    /**
     * 当前会话循环轮次（第几轮），同时充当循环触发的 CAS 令牌。
     * 用户发消息时置 1；每轮工具全部完成后由唯一合法触发者在 cid 锁内
     * compare(observed==current) + set(current+1) 消费并拉起下一轮循环。
     */
    @Getter(AccessLevel.NONE)
    private final AtomicInteger loopCount = new AtomicInteger(0);

    /**
     * 父会话 ID，如不为空，说明当前会话是由主智能体委派的 SubAgent 运行周期
     */
    private volatile Long parentCid = null;

    /**
     * 当前权限兜底配置模式
     */
    private volatile PermissionMode permissionMode = PermissionMode.READ_ONLY;

    /**
     * 当前选用的模型供应商分组
     */
    private volatile String providerGroup = null;

    /**
     * 当前选用的具体模型名称
     */
    private volatile String providerModelName = null;

    /**
     * 当前会话绑定的物理隔离工作区绝对路径
     */
    private volatile String worktreePath = null;

    /**
     * 当前轮次大模型请求调用的工具数量（内存状态快照）
     */
    private volatile int callToolCount = 0;

    /**
     * 当前会话等待执行完成的工具调用 ID 集合（内存屏障缓存）
     */
    private final Set<String> waitingToolIds = ConcurrentHashMap.newKeySet();

    /**
     * 当前会话等待执行完成的子智能体会话 ID 集合（内存屏障缓存）
     */
    private final Set<Long> waitingSubCids = ConcurrentHashMap.newKeySet();

    /**
     * 该会话解锁了的隐藏工具集（按需暴露工具）。
     * 用 volatile 引用保证整体替换时的可见性，内部集合用 ConcurrentHashMap.newKeySet() 保证单操作安全。
     */
    private volatile Set<String> unlockedTools = ConcurrentHashMap.newKeySet();

    /**
     * 当前会话专属的技能包列表
     */
    private final List<Skill> skills = new CopyOnWriteArrayList<>();

    /**
     * 当前会话专属的生命周期 Hook 规则列表
     */
    private final List<HookRule> hookRules = new CopyOnWriteArrayList<>();

    /**
     * 当前会话专属的项目开发指令与规范列表 (AGENTS.md)
     */
    private final List<AgentRuleVo> rules = new CopyOnWriteArrayList<>();

    /**
     * 当前会话专属的 MCP 客户端实例映射 (serverName -> McpClientInstance)
     */
    private final Map<String, McpClientInstance> mcpClients = new ConcurrentHashMap<>();

    /**
     * 当前会话绑定的物理隔离工作区 Git 分支名
     */
    private volatile String worktreeBranch = null;

    /**
     * 当前会话生命周期内成功读取过的文件内容指纹集合（绝对路径 → 内容哈希，双重强化门禁）。
     * 修改类工具执行前校验哈希是否与磁盘当前内容一致：一致放行（防幻觉），不一致拦截并要求重读（防过时修改）。
     * 相比旧的"每轮用户消息清空"策略，文件未变化时无需重复读取，消除长会话摩擦
     */
    private final Map<String, String> readFiles = new ConcurrentHashMap<>();

    /**
     * 当前会话近期的命令重复执行追踪表 (命令指纹 -> 追踪器)。
     * 用于检测同一命令短时间内输出完全相同的无效重复调用，防止模型陷入死循环空耗轮次。
     */
    private final Map<String, RepeatCommandTracker> recentCommands = new ConcurrentHashMap<>();

    /**
     * 当前会话连续幻觉工具调用次数（以轮次为单位）。使用 AtomicInteger 保证自增原子性。
     * 重启后归 0 可接受：幻觉工具通常是模型问题，重新计数不影响正确性。
     */
    @Getter(AccessLevel.NONE)
    private final AtomicInteger consecutiveUnknownTools = new AtomicInteger(0);

    /**
     * 事件监听器注册中心 (采用 CopyOnWriteArrayList 保证多线程并发安全)
     */
    private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 注册事件监听器。
     * 按 ListenerTier 升序插入（DIRECT → CACHE → NOTIFY），保证监听器链
     * 「先写盘落库 → 再同步内存缓存 → 最后异步推送前端」的固定执行顺序，
     * 注册时一次排好，publishEvent 直接遍历，无需每次事件重复排序。
     */
    public void registerListener(AgentEventListener listener) {
        if (listener == null) {
            return;
        }
        int order = listener.getTier().getOrder();
        int insertIdx = 0;
        while (insertIdx < listeners.size()
                && listeners.get(insertIdx).getTier().getOrder() <= order) {
            insertIdx++;
        }
        listeners.add(insertIdx, listener);
    }

    /**
     * 获取当前的循环轮次（第几轮）。
     *
     * @return 当前循环轮次
     */
    public int getLoopCount() {
        return loopCount.get();
    }

    /**
     * 设置当前的循环轮次。
     *
     * @param value 新的循环轮次值
     */
    public void setLoopCount(int value) {
        loopCount.set(value);
    }

    /**
     * 获取当前的连续未知工具调用次数。
     *
     * @return 连续未知工具调用次数
     */
    public int getConsecutiveUnknownTools() {
        return consecutiveUnknownTools.get();
    }

    /**
     * 设置当前的连续未知工具调用次数。
     *
     * @param value 新的连续未知工具调用次数值
     */
    public void setConsecutiveUnknownTools(int value) {
        consecutiveUnknownTools.set(value);
    }

    /**
     * 原子自增当前的连续未知工具调用次数，并返回新值。
     *
     * @return 自增后新的连续未知工具调用次数值
     */
    public int incrementAndGetConsecutiveUnknownTools() {
        return consecutiveUnknownTools.incrementAndGet();
    }

    /**
     * 原子替换 unlockedTools 集合。
     *
     * @param newTools 新的解锁工具集合
     */
    public void replaceUnlockedTools(Collection<String> newTools) {
        Set<String> replacement = ConcurrentHashMap.newKeySet();
        if (newTools != null) {
            replacement.addAll(newTools);
        }
        this.unlockedTools = replacement;
    }

    public void publishEvent(AgentEvent event) {
        if (event == null) {
            return;
        }
        if (event.getTimestamp() == 0L) {
            event.setTimestamp(System.currentTimeMillis());
        }

        // 穿透型事件：无第一、二层消费，免锁直推第三层异步串行队列
        if (event.getType().isPassThrough()) {
            for (AgentEventListener listener : this.listeners) {
                if (listener.getTier() == ListenerTier.NOTIFICATION) {
                    try {
                        CommonThread.submitNotify(() -> listener.onEvent(event));
                    } catch (Exception e) {
                        log.error("异步监听器处理事件发生异常: listener={}, type={}, cid={}",
                                listener.getClass().getSimpleName(), event.getType(), this.getCid(), e);
                    }
                }
            }
            return;
        }

        // 写命令事件：cid 数据锁覆盖第一、二层同步消费（DIRECT 写盘 + CACHE 回填），
        // 保证「写入 → 回填 → 判定」在单一临界区完成，防止并行批双触发；
        // 锁为可重入锁，与 ConversationServiceImpl.lockUpdateConversation 同源
        Lock cidLock = ContractLock.CID_DATA_STRIPED.get(this.getCid());
        cidLock.lock();
        try {
            for (AgentEventListener listener : this.listeners) {
                if (listener.getTier() == ListenerTier.DIRECT || listener.getTier() == ListenerTier.CACHE) {
                    // 第一、二层：同步执行，异常熔断阻断
                    try {
                        listener.onEvent(event);
                    } catch (RuntimeException e) {
                        log.error("同步监听器处理事件发生异常，触发熔断阻断: listener={}, type={}, cid={}",
                                listener.getClass().getSimpleName(), event.getType(), this.getCid(), e);
                        throw e; // 硬阻断
                    }
                } else {
                    // 第三层：异步串行推送前端
                    try {
                        CommonThread.submitNotify(() -> listener.onEvent(event));
                    } catch (Exception e) {
                        log.error("异步监听器处理事件发生异常: listener={}, type={}, cid={}",
                                listener.getClass().getSimpleName(), event.getType(), this.getCid(), e);
                    }
                }
            }
        } finally {
            cidLock.unlock();
        }
    }

    public void registerLlmCall(String llmCallId, Call call, String model) {
        if (llmCallId != null && call != null) {
            ActiveLlmCall activeCall = new ActiveLlmCall(llmCallId, this.cid, call, model, System.currentTimeMillis());
            activeLlmCalls.put(llmCallId, activeCall);
        }
    }

    public void unregisterLlmCall(String llmCallId) {
        if (llmCallId != null) {
            activeLlmCalls.remove(llmCallId);
        }
    }
}
