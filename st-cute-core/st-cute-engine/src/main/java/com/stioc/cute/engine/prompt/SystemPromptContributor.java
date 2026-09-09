package com.stioc.cute.engine.prompt;

import com.stioc.cute.engine.loop.core.AgentContext;

/**
 * 系统提示词槽位贡献者接口（宿主与引擎通用）。
 * <p>
 * 系统提示词被建模为「槽位 + 贡献者」：引擎只定义组装框架，
 * 每段内容由一个 Contributor 按序贡献。引擎内置通用环境段（os/日期/shell），
 * 人设、规约、技能、平台环境等段落全部由宿主贡献。
 * </p>
 */
public interface SystemPromptContributor {

    /**
     * 参与排序，宿主与引擎内置槽位统一排布（值小在前）。
     * 约定：引擎内置段 &lt;= 100；宿主 Persona 类 100~200；规则/技能类 200~400；环境补充类 400+。
     */
    int order();

    /**
     * 贡献的提示词片段（返回 null 或空白时不参与拼接）
     *
     * @param context 当前会话上下文
     */
    String contribute(AgentContext context);
}
