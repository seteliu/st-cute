package com.stioc.cute.engine.event.types;

/**
 * 智能体运行周期内的核心写命令事件与流式事实事件类型。
 *
 * 事件层高度精简，由三层监听器同步/异步瀑布式链式消费：
 * 1. DIRECT (直接层，持久化写盘与内部控制命令) -> 2. CACHE (缓存同步层) -> 3. NOTIFICATION (通知推送层)
 *
 * 生命周期挂点（Hook）不在本枚举内：挂点是同步回调扩展点而非广播事实，
 * 见 engine/hook/ 包（HookType + AgentContext.triggerHook 同步直调）。
 */
public enum AgentEventType {

    // --------------------------------------------------
    // 1. 会话与消息的写命令 (Database Write Commands)
    // 需要经过 DIRECT（写盘）与 CACHE（内存回填）两层同步消费，
    // publishEvent 会为这类事件持有 cid 数据锁，保证临界区互斥
    // --------------------------------------------------

    /**
     * 创建新会话（含子会话拉起）
     */
    CONVERSATION_CREATE(false, false),

    /**
     * 更新会话属性（Token、解锁工具、Loop状态、权限模式、父子会话绑定等）
     */
    CONVERSATION_UPDATE(false, false),

    /**
     * 删除会话
     */
    CONVERSATION_DELETE(false, false),

    /**
     * 创建消息（用户提问、AI回复、工具消息）
     */
    MESSAGE_CREATE(false, false),

    /**
     * 更新消息内容/状态（AI生成结束、工具执行完毕、审批拒绝/等待中等）
     */
    MESSAGE_UPDATE(false, false),

    /**
     * 删除消息
     */
    MESSAGE_DELETE(false, false),

    // --------------------------------------------------
    // 2. 穿透/纯流式事实事件 (Pass-Through Stream Events)
    // 第一、二层无消费逻辑，publishEvent 不加锁直接投递第三层
    // --------------------------------------------------

    /**
     * 流式思考链（Reasoning）内容输出
     */
    AGENT_THINKING_STREAM(true, true),

    /**
     * 流式正文（Content）内容输出
     */
    AGENT_CONTENT_STREAM(true, true),

    /**
     * 工具执行过程中的控制台增量日志流
     */
    TOOL_LOG_STREAM(true, false);

    /**
     * 是否为穿透型事件：无 DIRECT/CACHE 层消费逻辑（纯流式直推前端）。
     * <p>
     * 穿透型事件发布时跳过 cid 数据锁与第一、二层遍历，避免高频流式输出（如 Token 级 chunk）
     * 在锁上产生无谓排队。
     * </p>
     */
    private final boolean passThrough;

    /**
     * 是否为网络型流式事件：内容直接外推网络连接、具备端到端背压能力。
     * <p>
     * 本判据独立于 {@link #passThrough}：仅思考流与正文流成立。二者在两种分发模式下行为不同——
     * <ul>
     *   <li><b>非极速模式</b>（{@code notifyFastMode=false}）：走同步直调，使循环线程在客户端
     *       消费完成后才继续产出，客户端网速慢则产出一并慢，由 TCP 层天然完成速率适配；</li>
     *   <li><b>极速模式</b>（默认开启）：与穿透型事件同样投递车道线程池，产出不被下游消费阻塞，
     *       吞吐优先；此时背压由车道队列的「调用线程执行」策略在积压超限时兜底承担。</li>
     * </ul>
     * 故本判据的含义是「具备背压能力的候补通道」，是否启用同步直调由分发器的极速模式决定。
     * </p>
     * <p>
     * 工具控制台日志不属于网络型（{@code networkStream=false}）：其产出由子进程 stdout 速率决定，
     * 同步直调会让命令输出读取线程被网络阻塞、进而挂起子进程输出，故始终异步投递。
     * </p>
     */
    private final boolean networkStream;

    AgentEventType(boolean passThrough, boolean networkStream) {
        this.passThrough = passThrough;
        this.networkStream = networkStream;
    }

    /**
     * @return 是否为穿透型事件（无第一、二层消费，发布时免锁直投第三层）
     */
    public boolean isPassThrough() {
        return passThrough;
    }

    /**
     * @return 是否为网络型流式事件（具备背压能力的候补通道，是否启用同步直调由分发器的极速模式决定）
     */
    public boolean isNetworkStream() {
        return networkStream;
    }

}
