package com.stioc.cute.tool;

import com.stioc.cute.tool.access.ToolExecutionContext;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolNames;
import com.stioc.cute.worktree.access.WorktreeService;
import com.stioc.cute.agent.access.AgentContext;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

import com.stioc.cute.conversation.access.ConversationService;
import com.mybatisflex.core.util.UpdateEntity;
import com.stioc.cute.conversation.access.ConversationEntity;
import com.stioc.cute.agent.event.AgentEventFactory;

/**
 * 进入隔离副本工具 (enter_worktree)
 */
@Slf4j
@Component
public class EnterWorktreeTool implements CuteTool {

    @Resource
    private WorktreeService worktreeService;
    @Resource
    private ConversationService conversationService;

    @Override
    public String getName() {
        return ToolNames.ENTER_WORKTREE;
    }

    @Override
    public String getDescription() {
        return "【物理隔离工作副本工具】基于指定的 Git commit 基准和物理隔离空间标识(slug)，创建一个物理上的 Git Worktree 隔离工作副本，用于在独立的工作区执行风险代码开发。该副本完全隔离于主开发工作目录。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "slug": {
              "type": "string",
              "description": "隔离空间的短名称标识（如 'fix-bug-123'），物理工作区将以此命名"
            },
            "baseCommit": {
              "type": "string",
              "description": "隔离工作区分支的基准 Commit SHA。不传或传空时默认以主仓库当前 HEAD 为基准"
            }
          },
          "required": ["slug"]
        }
        """;
    }

    @Override
    public boolean isExposeOnDemand() {
        return true; // 按需暴露，初始隐藏
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        String slug = (String) arguments.get("slug");
        String baseCommit = (String) arguments.get("baseCommit");

        if (slug == null || slug.trim().isEmpty()) {
            return new JSONObject().fluentPut("error", "参数 slug 不能为空").toJSONString();
        }

        // 校验 slug 安全字符集：只允许字母、数字、连字符、下划线
        if (!slug.matches("^[a-zA-Z0-9_\\-]+$")) {
            return new JSONObject().fluentPut("error", "参数 slug 不合法。只允许使用字母、数字、连字符(-)和下划线(_)，禁止包含斜杠、点号等字符。").toJSONString();
        }

        try {
            AgentContext agentContext = context.agentContext();
            String projectBasePath = null;
            if (agentContext != null) {
                projectBasePath = conversationService.getProjectPath(agentContext.getCid());
            }

            Map<String, String> worktreeInfo = worktreeService.createWorktree(projectBasePath, slug, baseCommit);
            String path = worktreeInfo.get("worktreePath");
            String branch = worktreeInfo.get("branch");
            String head = worktreeInfo.get("head");
            String repoRoot = worktreeInfo.get("repoRoot");

            if (agentContext != null) {
                ConversationEntity updatePayload = UpdateEntity.of(ConversationEntity.class);
                updatePayload.setId(agentContext.getCid());
                updatePayload.setWorktreePath(path);
                updatePayload.setWorktreeBranch(branch);
                agentContext.publishEvent(AgentEventFactory.createConversationUpdate(agentContext, updatePayload));
                log.info("对话会话 {} 成功绑定 Worktree 路径: {}, 分支: {}", agentContext.getCid(), path, branch);
            }

            String hint = worktreeService.buildWorktreePromptHint(path, branch, head);

            return new JSONObject()
                    .fluentPut("success", true)
                    .fluentPut("worktreePath", path)
                    .fluentPut("branch", branch)
                    .fluentPut("head", head)
                    .fluentPut("repoRoot", repoRoot)
                    .fluentPut("message", "已成功建立并进入物理隔离工作副本！\n" + hint)
                    .toJSONString();
        } catch (Exception e) {
            log.error("创建 Git Worktree 失败", e);
            return new JSONObject().fluentPut("error", "创建物理隔离工作副本失败: " + e.getMessage()).toJSONString();
        }
    }
}
