package com.stioc.cute.entrypoint.http;

import com.stioc.cute.agent.access.AgentContextManager;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.platform.common.Result;
import com.stioc.cute.skill.access.Skill;
import com.stioc.cute.skill.access.SkillManagerService;
import com.stioc.cute.slash.access.SlashGroupVo;
import com.stioc.cute.slash.access.SlashItemVo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Slash 快捷补全控制器，为前端聊天输入框提供补全分组列表查询能力
 */
@Slf4j
@RestController
@RequestMapping("/api/slash")
public class SlashApi {

    @Resource
    private AgentContextManager agentContextManager;
    @Resource
    private SkillManagerService skillManagerService;

    /**
     * 获取当前会话的 slash 补全分组列表
     * 当前仅含 skill 分组：选项为当前会话可见的全部技能（项目专属 + 全局）
     */
    @GetMapping("/list")
    public Result<List<SlashGroupVo>> getSlashList(@RequestParam Long cid) {
        // 会话未绑定项目路径时，上下文仍可获取，仅技能列表可能为空
        AgentContext context = agentContextManager.getOrCreateContext(cid);
        if (context == null) {
            return Result.error(500, "无法初始化或获取当前会话上下文");
        }

        // 当前会话可见的全部技能包（含项目专属及全局注册）
        List<Skill> skills = skillManagerService.getSkills(context);
        List<SlashItemVo> items = skills.stream()
                .map(skill -> SlashItemVo.builder()
                        .name(skill.getName())
                        .description(skill.getDescription())
                        .build())
                .toList();

        // 每次调用实时装配，不引入缓存，保证热重载后数据即时生效
        SlashGroupVo skillGroup = SlashGroupVo.builder()
                .group("skill")
                .items(items)
                .build();

        return Result.success(List.of(skillGroup));
    }
}
