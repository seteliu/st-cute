package com.stioc.cute.runtime.prompt;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.prompt.SystemPromptContributor;
import com.stioc.cute.runtime.loop.RuntimeContext;
import com.stioc.cute.skill.types.Skill;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 技能包贡献者：注入已装载技能的元数据列表与按需加载引导段。
 * <p>（内容迁自原 SystemPromptGenerator 的 skillsSection 装配逻辑，行为等价）</p>
 */
@Component
public class SkillsContributor implements SystemPromptContributor {

    @Override
    public int order() {
        return 310;
    }

    @Override
    public String contribute(AgentContext context) {
        RuntimeContext runtimeCtx = context != null ? context.extra(RuntimeContext.class) : null;
        List<Skill> skills = runtimeCtx != null ? runtimeCtx.getSkills() : List.of();
        if (skills == null || skills.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n【可插拔技能包（Skills）列表】\n");
        sb.append("当前系统已检测到并装配了以下专业技能。你当前只有它们的简要描述，为了避免上下文臃肿，它们的详细规范默认不加载：\n");
        sb.append("- 如果你需要调用/使用某项 `inline` 模式技能，你必须首先调用 `load_skill` 工具传入其技能名称以将指令注入到当前会话；\n");
        sb.append("- 如果你需要调用/使用某项 `fork` 模式技能，为保持当前开发会话上下文整洁，你必须调用 `invoke_subagent` 工具派生子智能体来专门执行此任务，并要求子智能体在其独立会话中调用 `load_skill` 激活该技能进行处理，完成后向你汇报。\n\n");
        for (Skill skill : skills) {
            sb.append(String.format("- 技能名称：%s\n", skill.getName()));
            sb.append(String.format("  执行模式：%s\n", skill.getMode()));
            if (StringUtils.hasText(skill.getDescription())) {
                sb.append(String.format("  技能描述：%s\n", skill.getDescription()));
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
