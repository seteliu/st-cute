package com.stioc.cute.worktree.access;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件修改差异信息传输对象 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileDiffVo {

    /**
     * 修改文件的文件名/相对路径
     */
    private String filename;

    /**
     * 该文件的 Git 差异文本内容
     */
    private String diffContent;

    /**
     * 文件变更类型：ADD（新增）, DELETE（删除）, MODIFY（修改）
     */
    private String changeType;
}
