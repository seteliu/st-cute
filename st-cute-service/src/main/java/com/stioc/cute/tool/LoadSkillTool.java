package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.skill.access.Skill;
import com.stioc.cute.skill.access.SkillManagerService;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolExecutionContext;
import com.stioc.cute.tool.access.ToolNames;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 按需加载特定技能（Skill）详细指令及知识正文的核心静态工具，
 * 兼容业界主流的标准（如 Anthropic Agent Skills, Google Antigravity）。
 */
@Slf4j
@Component
public class LoadSkillTool implements CuteTool {

    @Resource
    private SkillManagerService skillManagerService;

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getName() {
        return ToolNames.LOAD_SKILL;
    }

    @Override
    public String getDescription() {
        return "【安全核心工具】加载并激活特定技能包（Skill）的完整详细操作规范、系统指令及知识库正文。如果你被要求调用或使用某项技能，或认为任务与之密切相关，你必须通过该工具加载并获取具体指令。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "skillName": {
              "type": "string",
              "description": "要加载并激活的技能名称，例如 'writer'"
            }
          },
          "required": ["skillName"]
        }
        """;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        String skillName = (String) arguments.get("skillName");
        if (!StringUtils.hasText(skillName)) {
            return new JSONObject().fluentPut("error", "参数 'skillName' 不能为空。").toJSONString();
        }

        Skill skill = skillManagerService.getSkill(skillName, agentContext);
        if (skill == null) {
            return new JSONObject().fluentPut("error", "在当前上下文中未找到技能：" + skillName).toJSONString();
        }

        JSONObject result = new JSONObject();
        result.put("name", skill.getName());
        result.put("description", skill.getDescription());
        result.put("mode", skill.getMode());
        result.put("source", skill.getSource());
        result.put("systemPrompt", skill.getSystemPrompt() != null ? skill.getSystemPrompt().trim() : "");

        log.info("LoadSkillTool 成功加载技能: {}, 模式: {}", skillName, skill.getMode());
        return result.toJSONString();
    }
}
