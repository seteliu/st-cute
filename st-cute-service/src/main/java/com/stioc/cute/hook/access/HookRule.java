package com.stioc.cute.hook.access;

import lombok.Data;
import java.util.Map;

/**
 * 智能体生命周期挂钩配置规则模型。
 * 对应 hooks.json 文件中的单个 Hook 任务项定义。
 */
@Data
public class HookRule {
    /**
     * 钩子规则的唯一名称标识
     */
    private String name;

    /**
     * 挂钩的生命周期切面事件名称（如 on_context_start 等）
     */
    private String event;

    /**
     * 工具过滤器，指定匹配触发钩子的工具协议名
     */
    private String toolFilter;

    /**
     * 文件路径的通配符正则 Glob 过滤串
     */
    private String pattern;

    /**
     * 切面匹配后物理执行的动作方式（如 execute_command）
     */
    private String action;

    /**
     * 绑定的动作附加环境参数 Map 映射
     */
    private Map<String, Object> args;

    /**
     * 是否在钩子物理执行返回错误时进行强行阻断中止推理循环
     */
    private boolean blocking;
}
