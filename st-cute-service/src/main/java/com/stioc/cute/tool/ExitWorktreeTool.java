package com.stioc.cute.tool;

import com.stioc.cute.tool.access.ToolExecutionContext;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolNames;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.worktree.access.WorktreeService;
import com.stioc.cute.conversation.access.ConversationService;
import com.mybatisflex.core.util.UpdateEntity;
import com.stioc.cute.conversation.access.ConversationEntity;
import com.stioc.cute.agent.event.AgentEventFactory;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;

/**
 * 退出隔离副本工具 (exit_worktree)
 */
@Slf4j
@Component
public class ExitWorktreeTool implements CuteTool {

    @Resource
    private WorktreeService worktreeService;
    @Resource
    private ConversationService conversationService;

    @Override
    public String getName() {
        return ToolNames.EXIT_WORKTREE;
    }

    @Override
    public String getDescription() {
        return "【退出工作区隔离工具】退出并清理指定的 Git Worktree 物理副本。如果副本中有未提交的代码修改或 HEAD 前进，会自动保留副本供后续合并；若没有任何变更，则会幂等清理物理目录 and 分支。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "worktreePath": {
              "type": "string",
              "description": "要退出的隔离工作副本的绝对路径"
            },
            "branchName": {
              "type": "string",
              "description": "隔离副本对应的临时分支名，例如：'worktree-fix-bug'"
            },
            "repoRoot": {
              "type": "string",
              "description": "当前 Git 主仓库的根路径"
            },
            "baseCommit": {
              "type": "string",
              "description": "可选参数。创建隔离工作副本时的基准 Commit SHA。若指定，工具会自动比对判断是否有新修改代码。若无，直接销毁清理副本；若有，保留副本防代码丢失。"
            }
          },
          "required": ["worktreePath", "branchName", "repoRoot"]
        }
        """;
    }

    @Override
    public boolean isExposeOnDemand() {
        return true; // 按需暴露，初始隐藏
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        String worktreePath = (String) arguments.get("worktreePath");
        String branchName = (String) arguments.get("branchName");
        String repoRoot = (String) arguments.get("repoRoot");
        String baseCommit = (String) arguments.get("baseCommit");

        if (worktreePath == null || worktreePath.trim().isEmpty()
                || branchName == null || branchName.trim().isEmpty()
                || repoRoot == null || repoRoot.trim().isEmpty()) {
            return new JSONObject().fluentPut("error", "参数 worktreePath, branchName 和 repoRoot 均不能为空").toJSONString();
        }

        AgentContext agentContext = context.agentContext();
        String projectBasePath = null;
        if (agentContext != null) {
            projectBasePath = conversationService.getProjectPath(agentContext.getCid());
        }

        File worktreeDir = new File(worktreePath);
        if (!worktreeDir.exists()) {
            // 目录不存在，可以直接视为无变更并做幂等清理
            log.info("退出 Worktree 发现目标路径不存在，直接执行幂等清理");
            worktreeService.cleanupWorktree(projectBasePath, worktreePath, branchName);
            unbindWorktree(agentContext);
            return new JSONObject().fluentPut("success", true)
                    .fluentPut("message", "目标隔离工作副本路径不存在，分支已成功删除，清理完毕。")
                    .toJSONString();
        }

        // 变更探测
        boolean hasChanges = false;
        if (baseCommit != null && !baseCommit.trim().isEmpty()) {
            hasChanges = worktreeService.detectChanges(worktreeDir, baseCommit);
        }

        if (hasChanges) {
            // 有变更时保留副本，但仍解绑 AgentContext（后续通过 git merge 处理）
            log.info("检测到物理隔离工作副本有代码变更或 HEAD 产生提交，保留此副本供后续合并。");
            unbindWorktree(agentContext);
            return new JSONObject()
                    .fluentPut("success", true)
                    .fluentPut("hasChanges", true)
                    .fluentPut("worktreePath", worktreePath)
                    .fluentPut("branchName", branchName)
                    .fluentPut("message", "【警告】检测到隔离副本内有新的开发代码或分支已前进，为防止工作丢失，已保留该 Worktree 空间。后续请到主仓库根目录下执行 'git merge " + branchName + "' 或手动合并该分支。")
                    .toJSONString();
        } else {
            // 执行销毁清理，清理成功后再解绑，保证状态一致性
            try {
                worktreeService.cleanupWorktree(projectBasePath, worktreePath, branchName);
                unbindWorktree(agentContext);
                return new JSONObject()
                        .fluentPut("success", true)
                        .fluentPut("hasChanges", false)
                        .fluentPut("message", "检测到物理副本无任何新代码变更，已成功执行幂等销毁，删除隔离目录和关联分支。")
                        .toJSONString();
            } catch (Exception e) {
                log.error("销毁隔离副本失败", e);
                return new JSONObject().fluentPut("error", "销毁隔离副本失败: " + e.getMessage()).toJSONString();
            }
        }
    }

    /**
     * 解绑当前会话的 Worktree 物理隔离属性，持久化并同步 AgentContext 内存状态
     */
    private void unbindWorktree(AgentContext agentContext) {
        if (agentContext == null) {
            return;
        }
        ConversationEntity updatePayload = UpdateEntity.of(ConversationEntity.class);
        updatePayload.setId(agentContext.getCid());
        updatePayload.setWorktreePath("");
        updatePayload.setWorktreeBranch("");
        agentContext.publishEvent(AgentEventFactory.createConversationUpdate(agentContext, updatePayload));
        log.info("对话会话 {} 已解绑并退出 Worktree 隔离", agentContext.getCid());
    }
}
