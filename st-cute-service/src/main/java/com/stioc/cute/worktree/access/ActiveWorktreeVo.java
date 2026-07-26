package com.stioc.cute.worktree.access;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 活跃工作区信息传输对象 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActiveWorktreeVo {

    /**
     * 隔离工作区物理绝对路径
     */
    private String path;

    /**
     * 隔离工作区绑定的 Git 分支名称
     */
    private String branch;
}
