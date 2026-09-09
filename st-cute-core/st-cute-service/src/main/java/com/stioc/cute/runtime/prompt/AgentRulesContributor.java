package com.stioc.cute.runtime.prompt;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.prompt.SystemPromptContributor;
import com.stioc.cute.runtime.loop.types.AgentRuleVo;
import com.stioc.cute.runtime.loop.RuntimeContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 开发规约贡献者：注入全局与项目级 AGENTS.md / rules 规则内容段。
 * <p>（内容迁自原 SystemPromptGenerator 的 rulesSection 装配逻辑，行为等价）</p>
 */
@Component
public class AgentRulesContributor implements SystemPromptContributor {

    @Override
    public int order() {
        return 200;
    }

    @Override
    public String contribute(AgentContext context) {
        RuntimeContext runtimeCtx = context != null ? context.extra(RuntimeContext.class) : null;
        List<AgentRuleVo> rules = runtimeCtx != null ? runtimeCtx.getRules() : List.of();
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        StringBuilder rulesSb = new StringBuilder();
        for (AgentRuleVo rule : rules) {
            if (rule != null && StringUtils.hasText(rule.getContent())) {
                rulesSb.append("[").append(rule.getName()).append(" 开发指令与规范]\n")
                        .append(rule.getContent().trim()).append("\n\n");
            }
        }
        String rulesStr = rulesSb.toString().trim();
        return StringUtils.hasText(rulesStr) ? "【项目开发规范】\n" + rulesStr : null;
    }
}
