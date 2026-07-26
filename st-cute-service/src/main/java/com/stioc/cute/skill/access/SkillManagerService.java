package com.stioc.cute.skill.access;

import com.stioc.cute.agent.access.AgentContext;
import java.io.IOException;
import java.util.List;

/**
 * 智能体动态资产（技能）加载与编辑管理接口
 */
public interface SkillManagerService {

    /**
     * 动态热装载指定项目工作区下的全部专属技能包元数据与提示词
     */
    void loadProjectSkills(AgentContext context, String projectBasePath);

    /**
     * 获取全局静态注册的全部技能包列表
     */
    List<Skill> getSkills();

    /**
     * 获取指定会话环境下可见的全部技能包列表（含项目专属及全局）
     */
    List<Skill> getSkills(AgentContext context);

    /**
     * 获取全局指定名称的技能包实体数据
     */
    Skill getSkill(String name);

    /**
     * 获取指定会话环境下可见的特定技能包数据
     */
    Skill getSkill(String name, AgentContext context);

    /**
     * 一键覆写/保存技能包对应 SKILL.md 文件的主体提示词内容
     */
    void updateSkillPrompt(String name, String newPrompt) throws IOException;
}
