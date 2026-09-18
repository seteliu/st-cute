package com.stioc.cute.git.types;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Git 分支信息传输对象 VO（数据源自 git branch 自定义 format 输出）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitBranchVo {

    /**
     * 分支工作区物理绝对路径（有物理工作区取实际路径，无则回退仓库根目录，作为 diff 命令执行目录）
     */
    private String path;

    /**
     * 分支名称
     */
    private String branch;

    /**
     * 是否为仓库当前检出分支（git branch 的 * 标记），前端默认选中用
     */
    private boolean current;
}
