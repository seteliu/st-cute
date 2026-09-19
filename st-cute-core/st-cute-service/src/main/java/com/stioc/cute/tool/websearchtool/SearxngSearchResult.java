package com.stioc.cute.tool.websearchtool;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.util.List;

/**
 * SearXNG 单条搜索结果。
 * <p>
 * 按 R2 裁决的字段裁剪方案，仅保留模型可读的有效字段
 * （title/url/content/engines/score/pubdate），
 * 丢弃 parsed_url/positions/template 与各类媒体空字段等渲染与统计噪音。
 * </p>
 */
@Data
public class SearxngSearchResult {

    /**
     * 结果标题
     */
    private String title;

    /**
     * 结果链接
     */
    private String url;

    /**
     * 内容摘要（模型阅读的主要信息来源）
     */
    private String content;

    /**
     * 命中的上游引擎列表（多引擎命中代表结果可信度更高）
     */
    private List<String> engines;

    /**
     * SearXNG 聚合排序分（接口返回时已按此降序排列）
     */
    private Double score;

    /**
     * 发布时间（上游引擎覆盖有限，可空；映射自 SearXNG 的 pubdate 字段）
     */
    @JSONField(name = "pubdate")
    private String publishedDate;
}
