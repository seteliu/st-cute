package com.stioc.cute.tool.websearchtool;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.util.List;

/**
 * SearXNG JSON 接口响应。
 * <p>
 * 按 R2/R3 裁决保留顶层 results/suggestions/answers/unresponsive_engines，
 * 丢弃 infoboxes/corrections 等低价值字段；
 * resultCount 为宿主层自产字段（映射时计算 results 数量），不在此 DTO 中。
 * </p>
 */
@Data
public class SearxngSearchResponse {

    /**
     * 回显搜索词
     */
    private String query;

    /**
     * 搜索结果列表
     */
    private List<SearxngSearchResult> results;

    /**
     * 搜索联想词（可空，供模型二次优化搜索词）
     */
    private List<String> suggestions;

    /**
     * 直接答案（可空，部分查询类型命中）
     */
    private List<String> answers;

    /**
     * 无响应引擎列表（可空，原始结构为 [引擎名, 原因] 数组的数组，输出前拍平）
     */
    @JSONField(name = "unresponsive_engines")
    private List<List<String>> unresponsiveEngines;
}
