package com.stioc.cute.runtime.prompt;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.prompt.SystemPromptContributor;
import com.stioc.cute.runtime.loop.types.AgentRuleVo;
import com.stioc.cute.runtime.loop.RuntimeContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 开发规约贡献者：注入全局与项目级 AGENTS.md / rules 规则内容段。
 * <p>（内容迁自原 SystemPromptGenerator 的 rulesSection 装配逻辑；
 * 因规约正文由用户自由格式编写，统一以 XML 标签包裹并标注来源，
 * 显式划定外部内容边界，避免与宿主系统指令混淆）</p>
 */
@Component
public class AgentRulesContributor implements SystemPromptContributor {

    /**
     * 规约正文中可能出现的闭合标签（含空白变体、忽略大小写），
     * 拼装前统一中和，防止正文内容提前越界破坏边界结构
     */
    private static final Pattern CLOSING_TAG_PATTERN = Pattern.compile("(?i)</\\s*(rule|project-rules)\\s*>");

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
                // 每条规约独立包裹一层 <rule>，并在 source 属性标注来源（级别名称 + 物理路径）
                rulesSb.append("<rule source=\"").append(escapeXmlAttr(buildSource(rule))).append("\">\n")
                        .append(neutralizeClosingTags(rule.getContent().trim())).append("\n")
                        .append("</rule>\n\n");
            }
        }
        String rulesStr = rulesSb.toString().trim();
        return StringUtils.hasText(rulesStr)
                ? "【项目开发规范】\n<project-rules>\n" + rulesStr + "\n</project-rules>"
                : null;
    }

    /**
     * 拼装规约来源描述（级别名称 + 物理路径），便于模型识别该段内容的出处
     */
    private String buildSource(AgentRuleVo rule) {
        String name = StringUtils.hasText(rule.getName()) ? rule.getName() : "未命名规约";
        return StringUtils.hasText(rule.getPath()) ? name + "，路径: " + rule.getPath() : name;
    }

    /**
     * 中和规约正文里自带的闭合标签：替换为无害的标注文本，避免提前闭合导致边界失效
     */
    private String neutralizeClosingTags(String content) {
        // </rule> / </project-rules>（含空白变体） => <rule-closing-tag> / <project-rules-closing-tag>
        return CLOSING_TAG_PATTERN.matcher(content).replaceAll("<$1-closing-tag>");
    }

    /**
     * XML 属性值转义，防止来源名称或路径中的特殊字符破坏标签结构
     */
    private String escapeXmlAttr(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
