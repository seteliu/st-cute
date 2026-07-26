package com.stioc.cute.agent.access;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 规约文件信息传输对象 (AGENTS.md)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRuleVo {
    /**
     * 规约名称或级别 (例如 全局级, 项目级)
     */
    private String name;

    /**
     * 物理绝对路径
     */
    private String path;

    /**
     * 最后修改时间 (格式化后)
     */
    private String updateTime;

    /**
     * 文件大小 (字节)
     */
    private long size;

    /**
     * 文件完整内容文本
     */
    private String content;
}
