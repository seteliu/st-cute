package com.stioc.cute.worktree.access;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Git Worktree 工作区多副本安全构建与变更检测接口
 */
public interface WorktreeService {

    /**
     * 向上溯源寻找 Git 仓库根目录
     */
    File findRepoRoot(File startDir);

    /**
     * 解析 Git 底层数据库元数据目录 .git 物理文件夹位置
     */
    File resolveGitDir(File repoRoot);

    /**
     * 获取 Git 当前指向的头部提交哈希值
     */
    String readHeadCommit(File gitDir);

    /**
     * 读取当前活跃的 Git 分支名称
     */
    String readCurrentBranch(File gitDir);

    /**
     * 安全地解析解析 Git 的 HEAD 指针引用指向的物理提交
     */
    String safeResolveRef(File gitDir, String refPath);

    /**
     * 校验 Git 分支或引用名称是否合法规范
     */
    boolean isValidRefName(String ref);

    /**
     * 为智能体运行实例动态创建独立的物理隔离 Git Worktree 工作区
     */
    Map<String, String> createWorktree(String projectBasePath, String slug, String baseCommit) throws Exception;

    /**
     * 级联强行清理并物理回收销毁已使用的隔离工作区
     */
    void cleanupWorktree(String projectBasePath, String worktreePath, String branchName);

    /**
     * 安全检测隔离工作区是否存在未提交的物理文件修改变更
     */
    boolean detectChanges(File worktreeDir, String baseCommit);

    /**
     * 渲染提示语告诉大模型当前工作区和绑定的分支元数据
     */
    String buildWorktreePromptHint(String worktreePath, String branch, String head);

    /**
     * 获取指定隔离分支相对于基础 Commit 节点的完整物理差异
     */
    List<FileDiffVo> getWorktreeDiff(String projectBasePath, String branchName, String baseCommit) throws Exception;

    /**
     * 获取当前注册管理的活跃隔离工作区分支状态列表
     */
    List<ActiveWorktreeVo> getActiveWorktrees(String projectBasePath);
}
