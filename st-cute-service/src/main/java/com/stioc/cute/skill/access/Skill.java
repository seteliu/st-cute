package com.stioc.cute.skill.access;

import lombok.Data;
import java.util.List;

/**
 * 智能体动态技能包数据模型，映射 SKILL.md 中的 YAML Frontmatter 元数据与 Markdown 提示词正文
 */
@Data
public class Skill {
    /**
     * 技能的唯一名称标识
     */
    private String name;

    /**
     * 技能的简要描述说明
     */
    private String description;

    /**
     * 该技能触发被建议执行的初始化命令行指令
     */
    private String command;

    /**
     * 技能包内注册暴露的智能体可用本地工具列表
     */
    private List<String> tools;

    /**
     * 技能包提示词主体正文内容
     */
    private String systemPrompt;

    /**
     * 技能包对应的 SKILL.md 绝对物理路径
     */
    private String path;

    /**
     * 技能来源类型：GLOBAL (全局配置) 或 PROJECT (项目专属)
     */
    private String source;

    /**
     * 技能运行模式：inline (内联) 或 fork (派生子智能体)
     */
    private String mode = "inline";
}
