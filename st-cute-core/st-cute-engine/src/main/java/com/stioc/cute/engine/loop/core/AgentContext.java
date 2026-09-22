package com.stioc.cute.engine.loop.core;

import com.stioc.cute.engine.loop.types.ActiveLlmCall;
import com.stioc.cute.engine.event.AgentEventDispatcher;
import com.stioc.cute.engine.event.types.AgentEvent;
import com.stioc.cute.engine.hook.AgentHookDispatcher;
import com.stioc.cute.engine.hook.HookPayload;
import com.stioc.cute.engine.hook.HookType;
import com.stioc.cute.engine.common.StreamBufferHolder;
import com.stioc.cute.engine.common.StreamBufferType;
import com.stioc.cute.engine.tool.DynamicToolProvider;
import lombok.Data;
import lombok.Getter;
import lombok.AccessLevel;

import okhttp3.Call;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 维护每个 Agent 运行实例的实时对话会话控制状态与计量信息。
 * <p>
 * 纯状态容器定位：只承载本会话的运行时字段与原子操作，
 * 事件分发与 Hook 直调委托给引擎全局唯一的 AgentEventDispatcher / AgentHookDispatcher
 * （监听器清单全会话共享，不再逐上下文拷贝冗余清单）；流式内容缓存委托 StreamBufferHolder。
 * </p>
 */
@Data
public class AgentContext implements BaseAgentContext {

    /**
     * 会话唯一 ID
     */
    private final Long cid;

    /**
     * 事件分发器（引擎全局唯一，监听器清单全会话共享；publishEvent 薄委托落点）
     */
    private final AgentEventDispatcher eventDispatcher;

    /**
     * 生命周期挂点分发器（引擎全局唯一，宿主 Hook 监听器清单全会话共享；triggerHook 薄委托落点）
     */
    private final AgentHookDispatcher hookDispatcher;

    /**
     * 流式内容缓冲持有器（思考流/正文流双缓冲与归属切换，纯展示缓存）
     */
    private final StreamBufferHolder streamBufferHolder = new StreamBufferHolder();

    /**
     * 会话绑定的额外伴生扩展上下文容器 (BaseAgentContextClass -> BaseAgentContext)。
     * 扩展领域（如 Coding 底座 RuntimeContext）在此挂接，生命周期受 AgentContext 严格统管。
     */
    private final Map<Class<? extends BaseAgentContext>, BaseAgentContext> extraContexts = new ConcurrentHashMap<>();

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
     * 判定当前会话是否为子智能体（SubAgent）运行周期。
     * <p>统一收口原散布各处的魔法表达式 {@code parentCid != null && parentCid != 0}。</p>
     */
    public boolean isSubAgent() {
        return parentCid != null && parentCid != 0L;
    }

    /**
     * 当前权限兜底配置模式（字符串化承载，语义由宿主定义，如 READ_ONLY / SMART_APPROVAL / ALL_ALLOW）
     */
    private volatile String permissionMode = null;

    /**
     * 当前选用的模型供应商分组
     */
    private volatile String providerGroup = null;

    /**
     * 当前选用的具体模型名称
     */
    private volatile String providerModelName = null;

    /**
     * 工作区标识（恢复时装载；引擎不解释语义，由宿主的业务层自行解析，
     * 子会话创建时继承父会话值）
     */
    private volatile String workspaceId = null;

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
     * 会话级动态工具提供者列表（宿主装载的 MCP、平台插件工具源等，语义化扩展点）。
     * 宿主在上下文装载扩展点中注入，运行中可整体替换（volatile 保证可见性）
     */
    private volatile List<DynamicToolProvider> dynamicToolProviders = new CopyOnWriteArrayList<>();

    /**
     * 当前会话连续幻觉工具调用次数（以轮次为单位）。使用 AtomicInteger 保证自增原子性。
     * 重启后归 0 可接受：幻觉工具通常是模型问题，重新计数不影响正确性。
     */
    @Getter(AccessLevel.NONE)
    private final AtomicInteger consecutiveUnknownTools = new AtomicInteger(0);

    /**
     * 构造指定会话的智能体运行上下文实例
     *
     * @param cid 会话唯一 ID
     * @param eventDispatcher 引擎全局事件分发器
     * @param hookDispatcher 引擎全局生命周期挂点分发器
     */
    public AgentContext(Long cid, AgentEventDispatcher eventDispatcher, AgentHookDispatcher hookDispatcher) {
        this.cid = cid;
        this.eventDispatcher = eventDispatcher;
        this.hookDispatcher = hookDispatcher;
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
     * 原子替换会话级动态工具提供者列表。
     *
     * @param providers 新的提供者列表（null 视为清空）
     */
    public void replaceDynamicToolProviders(List<DynamicToolProvider> providers) {
        List<DynamicToolProvider> replacement = new CopyOnWriteArrayList<>();
        if (providers != null) {
            replacement.addAll(providers);
        }
        this.dynamicToolProviders = replacement;
    }

    /**
     * 发布智能体事件（薄委托至全局事件分发器：穿透型免锁直推第三层，
     * 写命令事件 cid 数据锁内同步完成一二层消费后异步推送前端）。
     *
     * @param event 待发布事件
     */
    public void publishEvent(AgentEvent event) {
        eventDispatcher.dispatch(this.cid, event);
    }

    /**
     * 触发生命周期挂点（薄委托至全局挂点分发器：cid 数据锁内同步直调全部宿主 Hook 监听器，
     * 任一监听器异常即向上传播，由引擎调用点捕获执行拦截或结果改写）。
     *
     * @param type    挂点类型
     * @param payload 强类型载荷（生命周期挂点为 null）
     */
    public void triggerHook(HookType type, HookPayload payload) throws Exception {
        hookDispatcher.dispatch(this, type, payload);
    }

    /**
     * 追加流式内容缓存片段（薄委托至流式缓冲持有器）。
     *
     * @param type      流类型（思考流/正文流/工具日志流）
     * @param messageId 流式输出归属的消息 ID
     * @param chunk     增量内容片段
     */
    public void appendStreamChunk(StreamBufferType type, Long messageId, String chunk) {
        streamBufferHolder.appendStreamChunk(type, messageId, chunk);
    }

    /**
     * 按 messageId 清除思考流与正文流缓存（薄委托至流式缓冲持有器）。
     *
     * @param messageId 已完结的助手消息 ID
     */
    public void clearStreamBuffers(Long messageId) {
        streamBufferHolder.clearStreamBuffers(messageId);
    }

    /**
     * 清除工具日志流缓存并推进水位线（薄委托至流式缓冲持有器）。
     *
     * @param messageId 已进入终态的工具消息 ID
     */
    public void clearToolLogStream(Long messageId) {
        streamBufferHolder.clearToolLogStream(messageId);
    }

    /**
     * 按 messageId 清除单条流的缓存（薄委托至流式缓冲持有器，供透明重试的清空信号使用）。
     *
     * @param type      流类型（思考流/正文流/工具日志流）
     * @param messageId 目标消息 ID
     */
    public void clearStreamChunk(StreamBufferType type, Long messageId) {
        streamBufferHolder.clearStreamChunk(type, messageId);
    }

    /**
     * 读取流式内容缓存的当前累积快照（薄委托至流式缓冲持有器）。
     *
     * @param type      流类型（思考流/正文流/工具日志流）
     * @param messageId 目标消息 ID
     * @return 累积内容快照，无有效缓存时返回 null
     */
    public String snapshotStreamText(StreamBufferType type, Long messageId) {
        return streamBufferHolder.snapshotStreamText(type, messageId);
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

    /**
     * 实现 BaseAgentContext 契约：清理当前会话关联的大模型网络连接与伴生扩展上下文副作用。
     */
    @Override
    public void clear() {
        // 1. 强退主大模型 HTTP 连接
        try {
            Call activeCall = this.activeLlmCall;
            if (activeCall != null && !activeCall.isCanceled()) {
                activeCall.cancel();
            }
        } catch (Exception ignored) {
        }
        this.activeLlmCall = null;

        // 2. 强退所有活动大模型网络调用
        this.activeLlmCalls.forEach((llmCallId, activeCall) -> {
            try {
                Call call = activeCall.getCall();
                if (call != null && !call.isCanceled()) {
                    call.cancel();
                }
            } catch (Exception ignored) {
            }
        });
        this.activeLlmCalls.clear();

        // 3. 挨个调用伴生扩展上下文的 clear 进行资源清理
        this.extraContexts.values().forEach(extra -> {
            try {
                extra.clear();
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * 登记或替换指定类型的额外伴生上下文（Map 语义 put）。
     *
     * @param type         额外上下文类型 Class
     * @param extraContext 额外上下文实例（为 null 时执行移除）
     * @param <T>          泛型边界
     */
    public <T extends BaseAgentContext> void putExtraContext(Class<T> type, T extraContext) {
        if (type == null) {
            return;
        }
        if (extraContext == null) {
            extraContexts.remove(type);
        } else {
            extraContexts.put(type, extraContext);
        }
    }

    /**
     * 登记或替换额外伴生上下文（按实例实际 Class 类型作为 Key）。
     *
     * @param extraContext 额外上下文实例（为 null 时不执行任何操作）
     */
    @SuppressWarnings("unchecked")
    public void putExtraContext(BaseAgentContext extraContext) {
        if (extraContext != null) {
            putExtraContext((Class<BaseAgentContext>) extraContext.getClass(), extraContext);
        }
    }

    /**
     * 获取指定类型的额外伴生上下文。
     *
     * @param type 目标类型 Class
     * @param <T>  泛型边界
     * @return 额外上下文实例，不存在返回 null
     */
    @SuppressWarnings("unchecked")
    public <T extends BaseAgentContext> T getExtraContext(Class<T> type) {
        if (type == null) {
            return null;
        }
        return (T) extraContexts.get(type);
    }

    /**
     * 快捷获取指定类型的额外伴生上下文（{@link #getExtraContext} 的别名简写）。
     *
     * @param type 目标类型 Class
     * @param <T>  泛型边界
     * @return 额外上下文实例，不存在返回 null
     */
    public <T extends BaseAgentContext> T extra(Class<T> type) {
        return getExtraContext(type);
    }
}
