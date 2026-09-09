package com.stioc.cute.engine.hook;

import lombok.Builder;
import lombok.Data;

/**
 * 引擎生命周期挂点的强类型载荷。
 * <p>
 * 仅工具相关挂点（{@link HookType#ON_TOOL_CALL} / {@link HookType#ON_TOOL_COMPLETE}）
 * 携带内容；生命周期挂点（CONTEXT_START / LOOP_START / LOOP_END）载荷传 null。
 * 取代早期经事件总线传递的 JSONObject 裸载荷（魔法字符串取值）。
 * </p>
 */
@Data
@Builder
public class HookPayload {

    /**
     * 工具调用的唯一 ID
     */
    private final String toolCallId;

    /**
     * 工具名称
     */
    private final String toolName;

    /**
     * 工具入参的 JSON 字符串
     */
    private final String toolArgs;

    /**
     * 工具操作的目标资源标识（如文件路径、URI、表名等，可为 null）
     */
    private final String targetResource;

    /**
     * 工具执行结果（仅 ON_TOOL_COMPLETE 携带）
     */
    private final String toolResult;
}
