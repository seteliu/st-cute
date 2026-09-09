package com.stioc.cute.engine.prompt;

import com.stioc.cute.engine.loop.core.AgentContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 系统提示词组装器：收集全部贡献者 → order 升序 → 过滤空白 → 统一拼接。
 * <p>
 * 引擎只负责骨架与顺序，段落内容全部来自 Contributor（引擎内置默认环境段 + 宿主贡献者）。
 * </p>
 */
@Slf4j
public class SystemPromptAssembler {

    private final List<SystemPromptContributor> contributors;

    /**
     * 由 Builder.build() 装配注入全部贡献者并预排序（order 升序，稳定排序保证同序可预期）
     */
    public SystemPromptAssembler(List<SystemPromptContributor> contributors) {
        this.contributors = contributors == null ? List.of() : contributors.stream()
                .sorted(Comparator.comparingInt(SystemPromptContributor::order))
                .collect(Collectors.toList());
        log.info("SystemPromptAssembler 初始化完成，装载提示词贡献者 {} 个", this.contributors.size());
    }

    /**
     * 组装当前会话的完整系统提示词
     *
     * @param context 当前会话上下文
     * @return 按序拼接的完整系统提示词（无有效贡献者时返回空串）
     */
    public String assemble(AgentContext context) {
        return contributors.stream()
                .map(c -> safeContribute(c, context))
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n\n"))
                .trim();
    }

    /**
     * 单贡献者贡献隔离：单个贡献者异常不影响整体组装
     */
    private String safeContribute(SystemPromptContributor c, AgentContext context) {
        try {
            return c.contribute(context);
        } catch (Exception e) {
            log.warn("提示词贡献者 {} 执行异常，本段跳过: {}", c.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }
}
