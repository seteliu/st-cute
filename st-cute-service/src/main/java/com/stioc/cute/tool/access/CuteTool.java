package com.stioc.cute.tool.access;

import java.util.Map;

/**
 * AI 智能体工具统一契约接口
 */
public interface CuteTool {
    
    /**
     * 工具的唯一名称
     */
    String getName();

    /**
     * 给大模型看的工具功能描述，指导模型合适时机调用
     */
    String getDescription();

    /**
     * 工具的参数 Schema 定义 (JSON Schema 格式字符串)
     */
    String getArgumentSchema();

    /**
     * 工具的执行入口
     *
     * @param arguments 大模型传递过来的参数 Map
     * @param context   显式的工具执行上下文
     * @return 执行结果的纯文本或 JSON 格式字符串，将回灌给大模型历史
     */
    String execute(Map<String, Object> arguments, ToolExecutionContext context);

    /**
     * 该工具是否属于「按需暴露」类型（隐藏工具，需通过发现指令显式解锁）
     */
    default boolean isExposeOnDemand() {
        return false;
    }

    /**
     * 该工具是否属于只读性质（无外部副作用，支持并发执行）
     */
    default boolean isReadOnly() {
        return false;
    }

    /**
     * 该工具是否属于写/修改文件性质（可能存在文件写锁与 Hook 触发需求）
     */
    default boolean isWriteTool() {
        return false;
    }
}

