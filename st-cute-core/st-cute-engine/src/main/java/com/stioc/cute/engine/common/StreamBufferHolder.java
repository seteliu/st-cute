package com.stioc.cute.engine.common;

/**
 * 流式内容缓冲持有器：思考流/正文流双缓冲与归属切换管理。
 * <p>
 * 自 AgentContext 内部类族升入 common（纯展示缓存，与循环状态无关）：
 * 每个上下文仅保留最新一份思考流/正文流缓存，消息终态后按 messageId 清除，
 * 用于刷新页面时 HTTP 列表接口回填 DB 中尚未落库的流式内容。
 * AgentContext 持有本类实例并以同名薄委托暴露，外部调用形态不变。
 * </p>
 */
public class StreamBufferHolder {

    /**
     * 当前活跃的思考流内容缓存（仅保留最新一份，消息终态后按 messageId 清除）
     */
    private volatile StreamBuffer activeThinkingStream = null;

    /**
     * 当前活跃的正文流内容缓存（仅保留最新一份，消息终态后按 messageId 清除）
     */
    private volatile StreamBuffer activeContentStream = null;

    /**
     * 追加流式内容缓存片段。
     * 归属 messageId 与当前缓存不一致时，视为开启了新消息的流式输出，
     * 清空旧缓存并重新建立归属（每个上下文仅保留最新一份思考流/正文流缓存）。
     * 仅由通知层单线程（notify-serial）调用，写-写天然串行。
     *
     * @param isReasoning true=思考流缓存，false=正文流缓存
     * @param messageId   流式输出归属的 ASSISTANT 消息 ID
     * @param chunk       增量内容片段
     */
    public void appendStreamChunk(boolean isReasoning, Long messageId, String chunk) {
        if (messageId == null || chunk == null || chunk.isEmpty()) {
            return;
        }
        StreamBuffer current = isReasoning ? activeThinkingStream : activeContentStream;
        if (current == null || !current.messageId.equals(messageId)) {
            // 归属切换：清空旧缓存，为新一轮流式输出建立全新缓冲
            current = new StreamBuffer(messageId);
            if (isReasoning) {
                this.activeThinkingStream = current;
            } else {
                this.activeContentStream = current;
            }
        }
        current.append(chunk);
    }

    /**
     * 按 messageId 清除流式内容缓存。
     * 消息进入终态（SUCCESS/FAILED/CANCELED）后调用，此后由 DB 全量数据兜底展示。
     * 仅由通知层单线程（notify-serial）调用。
     *
     * @param messageId 已完结的消息 ID
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
     * 读取流式内容缓存的当前累积快照（HTTP 线程并发读）。
     * messageId 不匹配（缓存已切换到新消息或已清除）时返回 null。
     *
     * @param isReasoning true=思考流缓存，false=正文流缓存
     * @param messageId   目标消息 ID
     * @return 累积内容快照，无有效缓存时返回 null
     */
    public String snapshotStreamText(boolean isReasoning, Long messageId) {
        if (messageId == null) {
            return null;
        }
        StreamBuffer current = isReasoning ? activeThinkingStream : activeContentStream;
        if (current == null || !current.messageId.equals(messageId)) {
            return null;
        }
        return current.snapshot();
    }

    /**
     * 流式内容缓冲实体：绑定单一 messageId 的累积缓冲。
     * 写方为通知层单线程，读方为 HTTP 并发线程；
     * synchronized 仅用于防止读-写撕裂（StringBuilder 非线程安全，无锁并发读会
     * 读到扩容中的半态数据甚至抛越界异常），写-写串行由单线程执行器天然保证
     */
    private static final class StreamBuffer {
        /**
         * 缓存归属的 ASSISTANT 消息 ID
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
         * 追加增量片段（写方持锁）
         */
        private synchronized void append(String chunk) {
            buffer.append(chunk);
        }

        /**
         * 读取当前累积内容快照（读方持锁，与 append 互斥防撕裂）
         */
        private synchronized String snapshot() {
            return buffer.toString();
        }
    }
}
