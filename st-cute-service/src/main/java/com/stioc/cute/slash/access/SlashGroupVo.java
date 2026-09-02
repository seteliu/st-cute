package com.stioc.cute.slash.access;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Slash 补全下拉的分组传输对象，当前仅含 skill 分组，结构上预留多分组扩展能力
 */
@Data
@Builder
public class SlashGroupVo {

    /**
     * 分组名称（如 skill）
     */
    private String group;

    /**
     * 分组下的全部补全选项
     */
    private List<SlashItemVo> items;
}
