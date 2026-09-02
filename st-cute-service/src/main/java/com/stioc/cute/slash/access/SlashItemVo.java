package com.stioc.cute.slash.access;

import lombok.Builder;
import lombok.Data;

/**
 * Slash 补全下拉的单个选项传输对象
 */
@Data
@Builder
public class SlashItemVo {

    /**
     * 选项唯一名称（如技能名 st-spec），回填时以 "/{name} " 形式写入输入框
     */
    private String name;

    /**
     * 选项描述说明（如技能的 description），用于下拉列表次要展示辅助辨识
     */
    private String description;
}
