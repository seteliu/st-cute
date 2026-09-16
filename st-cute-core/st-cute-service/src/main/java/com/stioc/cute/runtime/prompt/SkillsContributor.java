package com.stioc.cute.runtime.prompt;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.prompt.SystemPromptContributor;
import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.runtime.loop.RuntimeContext;
import com.stioc.cute.skill.types.Skill;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 技能包贡献者：注入已装载技能的元数据列表与按需加载引导段。
 * <p>（内容迁自原 SystemPromptGenerator 的 skillsSection 装配逻辑，行为等价）</p>
 * <p>极简 Skill 模式开启时，不再注入完整技能清单以节省 Token，改为输出按需触发引导段。</p>
 * <p>紧随人设段（order=100）输出，且位于环境段（order=300）的会话变量分叉点之前，跨会话可保持前缀缓存命中。</p>
 */
@Component
public class SkillsContributor implements SystemPromptContributor {

    @Resource
    private ContractProperty contractProperty;

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String contribute(AgentContext context) {
        RuntimeContext runtimeCtx = context != null ? context.extra(RuntimeContext.class) : null;
        List<Skill> skills = runtimeCtx != null ? runtimeCtx.getSkills() : List.of();

        // 极简 Skill 模式：不注入技能清单（节省 Token），仅输出按需触发引导段；
        // 即便当前会话无技能也输出该段，保证 AI 遇到技能触发意图时知道去调 load_skill 尝试
        if (contractProperty.isMinimalSkillMode()) {
            return """
                    【可插拔技能包（Skills）按需触发模式】
                    当前系统已启用极简技能模式：技能清单不注入本提示词；仅当用户有明确的技能使用意图时才按需加载技能，平时不要加载任何技能。
                    - 当用户消息中出现技能触发意图（如 /xx-xx 形式的技能名，或"帮我调用 xx-xx skill"之类的表述），表示用户希望使用该技能包；
                    - 此时你必须调用 load_skill 工具，传入去掉 / 前缀的同名技能名称，加载其完整详细指令后再执行；
                    - 若加载后发现该技能为 fork 模式，则改用 invoke_subagent 工具派生子智能体在其独立会话中执行该技能，完成后向你汇报；
                    - 除此之外的常规任务中，不要主动加载任何技能。""";
        }

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
