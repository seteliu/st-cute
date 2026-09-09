package com.stioc.cute.controller;

import com.stioc.cute.worktree.types.FileDiffVo;
import com.stioc.cute.worktree.WorktreeService;
import com.stioc.cute.worktree.types.ActiveWorktreeVo;
import com.stioc.cute.platform.common.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import com.stioc.cute.project.ProjectService;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Git Worktree 状态查询控制器（物理隔离副本创建/退出机制已删除，
 * 前端仅消费 git 分支与文件变动查询能力）
 */
@Slf4j
@RestController
@RequestMapping("/api/worktree")
public class WorktreeController {

    @Resource
    private WorktreeService worktreeService;
    @Resource
    private ProjectService projectService;

    /**
     * 获取指定项目或当前会话下的活跃 Git Worktree 列表
     */
    @GetMapping("/list")
    public Result<List<ActiveWorktreeVo>> getActiveWorktrees(@RequestParam(required = false) Long cid) {
        log.debug("收到获取活跃 Worktree 列表请求, cid={}", cid);
        String projectBasePath = getProjectBasePath(cid);
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
        String projectBasePath = getProjectBasePath(cid);
        List<FileDiffVo> diffList = worktreeService.getWorktreeDiff(projectBasePath, branchName, baseCommit);
        return Result.success(diffList);
    }

    /**
     * 解析查询基准目录：会话绑定项目路径优先，未绑定（或 cid 缺失）时兜底全局配置目录。
     * 仅按 cid 查询，不猜测其他会话的工作区。
     */
    private String getProjectBasePath(Long cid) {
        if (projectService != null) {
            return projectService.getProjectBasePathByCid(cid);
        }
        return System.getProperty("user.dir");
    }
}
