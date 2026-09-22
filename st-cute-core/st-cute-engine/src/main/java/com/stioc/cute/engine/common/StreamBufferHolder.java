package com.stioc.cute.engine.common;

/**
 * 流式内容缓冲持有器：思考流/正文流/工具日志流三缓冲与归属切换管理。
 * <p>
 * 每个上下文仅保留最新一份各流缓存，消息终态后按 messageId 清除，
 * 用于刷新页面时 HTTP 列表接口回填 DB 中尚未落库的流式内容。
 * AgentContext 持有本类实例并以同名薄委托暴露，外部调用形态不变。
 * </p>
 * <p>
 * <b>线程模型</b>：读方为 HTTP 并发线程；写方依流类型与事件分发模式而变——
 * 思考流/正文流在极速模式（默认）下与终态事件同经通知车道串行消费（写入与清空同车道），
 * 非极速模式下由循环线程同步直调；工具日志流的写入方恒为「子进程 stdout 读取线程」，
 * 与清空方（车道线程）不同线程，靠桶内 synchronized 保证读写不撕裂。
 * 实例为会话私有、跨会话无共享。
 * </p>
 */
public class StreamBufferHolder {

    /**
     * 流式缓存内容的最大保留长度（字符）。
     * <p>
     * 仅对工具日志流生效：命令输出上限受宿主保护阀约束（可达 200 万字符），
     * 而流式缓存仅用于运行期展示回填，超出部分保留尾部（命令的结论通常在末尾）
     * 即可，避免失控刷屏输出把会话内存撑爆。与前端侧截断口径保持一致。
     * </p>
     */
    public static final int TOOL_LOG_KEEP_LENGTH = 100_000;

    /**
     * 当前活跃的思考流内容缓存（仅保留最新一份，消息终态后按 messageId 清除）
     */
    private volatile StreamBuffer activeThinkingStream = null;

    /**
     * 当前活跃的正文流内容缓存（仅保留最新一份，消息终态后按 messageId 清除）
     */
    private volatile StreamBuffer activeContentStream = null;

    /**
     * 当前活跃的工具日志流内容缓存（仅保留最新一份，工具消息终态后按 messageId 清除）
     */
    private volatile StreamBuffer activeToolLogStream = null;

    /**
     * 工具日志流水位线：最后一个已进入终态、缓存已被清除的工具消息 ID。
     * <p>
     * 存在的必要性：后台持久服务命令（runInBackground）的工具消息进入终态后，
     * 子进程仍可能存活并持续产出 stdout，其日志线程会继续发布日志事件。
     * 若不做拦截，清除后到达的迟到 chunk 会按「归属切换」逻辑重建缓存桶，
     * 而该消息已终结、再无清除时机，导致缓存常驻泄漏。
     * </p>
     * <p>
     * 语义：{@code messageId <= 水位线} 即为已终结消息的迟到日志，直接丢弃
     * （消息 ID 全局单调递增，故该判据能覆盖同 id 迟到与更早 id 迟到两种情形）。
     * </p>
     * <p>
     * <b>刻意只作用于工具日志流</b>：思考流/正文流存在「同 messageId 清空后重放」的
     * 正常语义（透明重试的 CLEAR 信号、以及助手消息重试后的同 id 续流），
     * 一旦挂上水位线，重放内容会被整段丢弃。
     * </p>
     */
    private volatile Long toolLogWatermark = null;

    /**
     * 追加流式内容缓存片段。
     * <p>
     * 归属 messageId 与当前缓存不一致时，视为开启了新消息的流式输出，
     * 清空旧缓存并重新建立归属（每条流仅保留最新一份缓存）。
     * </p>
     * <p>
     * 两层丢弃判据（任一命中即忽略本次 chunk）：
     * <ol>
     *   <li>迟到旧消息：messageId 小于当前桶归属（事件乱序到达的兜底）；</li>
     *   <li>已终结消息的迟到日志：仅工具日志流，命中水位线即丢。</li>
     * </ol>
     * </p>
     *
     * @param type      流类型（思考流/正文流/工具日志流）
     * @param messageId 流式输出归属的消息 ID
     * @param chunk     增量内容片段
     */
    public void appendStreamChunk(StreamBufferType type, Long messageId, String chunk) {
        if (type == null || messageId == null || chunk == null || chunk.isEmpty()) {
            return;
        }

        // 判据 2：工具日志流在终态后仍有后台进程持续输出，迟到 chunk 一律丢弃
        if (type == StreamBufferType.TOOL_LOG) {
            Long watermark = this.toolLogWatermark;
            if (watermark != null && messageId <= watermark) {
                return;
            }
        }

        StreamBuffer current = bufferOf(type);

        // 判据 1：迟到旧消息的 chunk（乱序到达兜底），小于当前归属即丢
        if (current != null && messageId < current.messageId) {
            return;
        }

        if (current == null || !current.messageId.equals(messageId)) {
            // 归属切换：清空旧缓存，为新消息的流式输出建立全新缓冲
            current = new StreamBuffer(messageId);
            setBuffer(type, current);
        }
        current.append(chunk, type == StreamBufferType.TOOL_LOG ? TOOL_LOG_KEEP_LENGTH : 0);
    }

    /**
     * 按 messageId 清除思考流与正文流缓存。
     * 助手消息进入终态（SUCCESS/FAILED/CANCELED）后调用，此后由 DB 全量数据兜底展示。
     *
     * @param messageId 已完结的助手消息 ID
     */
    public void clearStreamBuffers(Long messageId) {
        if (messageId == null) {
            return;
        }
        if (activeThinkingStream != null && activeThinkingStream.messageId.equals(messageId)) {
            this.activeThinkingStream = null;
        }
        if (activeContentStream != null && activeContentStream.messageId.equals(messageId)) {
            this.activeContentStream = null;
        }
    }

    /**
     * 清除工具日志流缓存并推进水位线（工具消息进入终态后调用）。
     * <p>
     * 与助手流的区别在于「推进水位线」这一步：工具消息无重试续流语义，
     * 终态即终结，此后同 id 的日志必然来自尚存的后台进程，应被永久丢弃。
     * </p>
     *
     * @param messageId 已进入终态的工具消息 ID
     */
    public void clearToolLogStream(Long messageId) {
        if (messageId == null) {
            return;
        }
        if (activeToolLogStream != null && activeToolLogStream.messageId.equals(messageId)) {
            this.activeToolLogStream = null;
        }
        // 水位线只增不减：兜住事件乱序时更早的终态事件晚到，不使其回退
        Long watermark = this.toolLogWatermark;
        if (watermark == null || messageId > watermark) {
            this.toolLogWatermark = messageId;
        }
    }

    /**
     * 按 messageId 清除<b>单条</b>流的缓存（思考流或正文流之一，由 type 指定）。
     * <p>
     * 用于透明重试的清空信号：重试重放会分别发出思考流清空与正文流清空两条信号，
     * 每条只作用于自己的流，故不能用 {@link #clearStreamBuffers(Long)}（那会连带清掉另一条）。
     * </p>
     *
     * @param type      流类型（思考流/正文流/工具日志流）
     * @param messageId 目标消息 ID
     */
    public void clearStreamChunk(StreamBufferType type, Long messageId) {
        if (type == null || messageId == null) {
            return;
        }
        StreamBuffer current = bufferOf(type);
        if (current != null && current.messageId.equals(messageId)) {
            setBuffer(type, null);
        }
    }

    /**
     * 读取流式内容缓存的当前累积快照（HTTP 线程并发读）。
     * messageId 不匹配（缓存已切换到新消息或已清除）时返回 null。
     *
     * @param type      流类型（思考流/正文流/工具日志流）
     * @param messageId 目标消息 ID
     * @return 累积内容快照，无有效缓存时返回 null
     */
    public String snapshotStreamText(StreamBufferType type, Long messageId) {
        if (type == null || messageId == null) {
            return null;
        }
        StreamBuffer current = bufferOf(type);
        if (current == null || !current.messageId.equals(messageId)) {
            return null;
        }
        return current.snapshot();
    }

    /**
     * 按流类型取出当前持有的缓存桶（无则返回 null）
     */
    private StreamBuffer bufferOf(StreamBufferType type) {
        return switch (type) {
            case THINKING -> activeThinkingStream;
            case CONTENT -> activeContentStream;
            case TOOL_LOG -> activeToolLogStream;
        };
    }

    /**
     * 按流类型写入缓存桶引用（value 为 null 表示清除该条流）
     */
    private void setBuffer(StreamBufferType type, StreamBuffer buffer) {
        switch (type) {
            case THINKING -> this.activeThinkingStream = buffer;
            case CONTENT -> this.activeContentStream = buffer;
            case TOOL_LOG -> this.activeToolLogStream = buffer;
        }
    }

    /**
     * 流式内容缓冲实体：绑定单一 messageId 的累积缓冲。
     * 写方可能是子进程 stdout 读取线程（工具日志），清空方为通知车道线程，读方为 HTTP 并发线程；
     * synchronized 仅用于防止读-写撕裂（StringBuilder 非线程安全，无锁并发读会
     * 读到扩容中的半态数据甚至抛越界异常），写-写串行由「追加与清空按发布顺序错开」保证
     */
    private static final class StreamBuffer {
        /**
         * 缓存归属的消息 ID
         */
        private final Long messageId;

        /**
         * 内容累积缓冲（append 与 snapshot 需互斥访问）
         */
        private final StringBuilder buffer = new StringBuilder();

        private StreamBuffer(Long messageId) {
            this.messageId = messageId;
        }

        /**
         * 追加增量片段（写方持锁）。
         *
         * @param chunk     增量文本
         * @param keepLimit 尾部保留上限（字符）；<=0 表示不截断
         */
        private synchronized void append(String chunk, int keepLimit) {
            buffer.append(chunk);
            if (keepLimit > 0 && buffer.length() > keepLimit) {
                // 超长输出（命令失控刷屏）只保留尾部：结论通常出现在末尾，
                // 且流式缓存仅用于运行期展示，无需完整留存
                buffer.delete(0, buffer.length() - keepLimit);
            }
        }

        /**
         * 读取当前累积内容快照（读方持锁，与 append 互斥防撕裂）
         */
        private synchronized String snapshot() {
            return buffer.toString();
        }
    }
}
