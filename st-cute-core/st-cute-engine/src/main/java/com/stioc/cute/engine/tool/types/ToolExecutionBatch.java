package com.stioc.cute.engine.tool.types;

import com.stioc.cute.engine.llm.types.CuteToolCall;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 单轮推理产出工具调用的批执行分组：按只读/写副作用切分为并发批与串行批。
 */
@Getter
@RequiredArgsConstructor
public class ToolExecutionBatch {

    /**
     * 本批待执行的工具调用清单
     */
    private final List<CuteToolCall> calls;

    /**
     * 是否只读批（只读批可并发执行，写批串行且持写工具锁）
     */
    private final boolean readOnly;
}
