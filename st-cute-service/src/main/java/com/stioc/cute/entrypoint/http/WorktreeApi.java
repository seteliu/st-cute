package com.stioc.cute.entrypoint.http;

import com.stioc.cute.worktree.access.FileDiffVo;
import com.stioc.cute.worktree.access.WorktreeService;
import com.stioc.cute.worktree.access.ActiveWorktreeVo;
import com.stioc.cute.platform.common.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import com.stioc.cute.conversation.access.ConversationService;
import com.stioc.cute.agent.access.AgentContextManager;
import org.springframework.util.StringUtils;

import java.util.List;
import java.io.File;

/**
 * 物理隔离工作区 Git Worktree 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/worktree")
public class WorktreeApi {

    @Resource
    private WorktreeService worktreeService;
    @Resource
    private ConversationService conversationService;
    @Resource
    private AgentContextManager agentContextManager;

    /**
     * 获取指定项目或当前会话下的活跃隔离 Git Worktree 列表
     */
    @GetMapping("/list")
    public Result<List<ActiveWorktreeVo>> getActiveWorktrees(@RequestParam(required = false) Long cid) {
        log.debug("收到获取活跃 Worktree 列表请求, cid={}", cid);
        String projectBasePath = getProjectBasePath(cid, null);
        List<ActiveWorktreeVo> list = worktreeService.getActiveWorktrees(projectBasePath);
        return Result.success(list);
    }

    /**
     * 获取指定隔离分支相对于基础 Commit 或主干的分支差异 Diff
     */
    @GetMapping("/diff")
    public Result<List<FileDiffVo>> getDiff(
            @RequestParam String branchName,
            @RequestParam(required = false) String baseCommit,
            @RequestParam(required = false) Long cid) throws Exception {
        log.debug("收到获取 Worktree diff 请求: branchName={}, baseCommit={}, cid={}", branchName, baseCommit, cid);
        String projectBasePath = getProjectBasePath(cid, null);
        List<FileDiffVo> diffList = worktreeService.getWorktreeDiff(projectBasePath, branchName, baseCommit);
        return Result.success(diffList);
    }

    private String getProjectBasePath(Long cid, String worktreePath) {
        if (StringUtils.hasText(worktreePath)) {
            String path = findProjectBasePathFromWorktreePath(worktreePath);
            if (path != null) return path;
        }
        if (cid != null) {
            String path = conversationService.getProjectPath(cid);
            if (StringUtils.hasText(path)) return path;
        }
        for (var sess : agentContextManager.getAllContexts()) {
            String path = conversationService.getProjectPath(sess.getCid());
            if (StringUtils.hasText(path)) return path;
        }
        return System.getProperty("user.dir");
    }

    private String findProjectBasePathFromWorktreePath(String worktreePath) {
        if (!StringUtils.hasText(worktreePath)) return null;
        File wtFile = new File(worktreePath).getAbsoluteFile();
        File parent = wtFile.getParentFile();
        if (parent != null && "worktrees".equals(parent.getName())) {
            File projectCuteDir = parent.getParentFile();
            if (projectCuteDir != null && (".st-cute".equals(projectCuteDir.getName()) || ".agents".equals(projectCuteDir.getName()))) {
                File projectDir = projectCuteDir.getParentFile();
                if (projectDir != null) {
                    return projectDir.getAbsolutePath();
                }
            }
        }
        return null;
    }
}
