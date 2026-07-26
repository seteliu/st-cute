package com.stioc.cute.skill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 技能包装提示语信息传输对象 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillPromptDto {

    /**
     * 技能的唯一名称标识
     */
    private String name;

    /**
     * 该技能触发被自动追加的系统提示语
     */
    private String prompt;
}
