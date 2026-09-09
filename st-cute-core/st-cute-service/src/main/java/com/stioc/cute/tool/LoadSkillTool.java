package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.skill.types.Skill;
import com.stioc.cute.skill.SkillManagerService;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolResult;
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
    public String getRawName() {
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
    public ToolAccessLevel getAccessLevel() {
        // 只读：仅读取技能内容注入上下文，无外部副作用
        return ToolAccessLevel.READ;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        ToolArgs args = ToolArgs.of(arguments);
        String skillName = args.getStringTrimmed("skillName");
        if (!StringUtils.hasText(skillName)) {
            return ToolResult.error("参数 'skillName' 不能为空。");
        }

        Skill skill = skillManagerService.getSkill(skillName, agentContext);
        if (skill == null) {
            return ToolResult.error("在当前上下文中未找到技能：" + skillName);
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
