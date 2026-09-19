package com.stioc.cute.tool.contexttool;

import com.stioc.cute.engine.tool.types.ToolArgs;

import java.util.Map;

/**
 * 技能加载工具输入参数强类型绑定对象
 */
public record LoadSkillArgs(String skillName) {
    public static LoadSkillArgs from(Map<String, Object> arguments) {
        ToolArgs args = ToolArgs.of(arguments);
        return new LoadSkillArgs(args.getStringTrimmed("skillName"));
    }
}
