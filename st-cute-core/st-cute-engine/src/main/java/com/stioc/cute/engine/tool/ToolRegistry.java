package com.stioc.cute.engine.tool;

import com.stioc.cute.engine.loop.core.AgentContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能体工具系统的注册中心，负责发现和包装所有 CuteTool 实现
 * （包括内置静态工具与 MCP 等外部动态工具源）。
 * <p>
 * 动态工具经 {@link DynamicToolProvider} 供血：
 * 会话级来源挂载于 AgentContext（宿主装载），全局级来源由宿主注册进 Builder（Optional 可选配置）。
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class ToolRegistry {

    private final List<CuteTool> staticTools;
    private final Optional<List<DynamicToolProvider>> globalToolProviders;

    /**
     * 静态工具名索引（小写名 → 工具实例）：静态工具集不可变，init 时一次构建，getTool 热路径零重建
     */
    private final Map<String, CuteTool> staticToolIndex = new ConcurrentHashMap<>();

    /**
     * 注册中心初始化（原 @PostConstruct 逻辑摘出，由 Builder.build() 在装配完成后显式调用）。
     * <p>
     * 含启动期 Schema 静态校验：参数 Schema 语法漂移的工具直接 fail-fast 拒绝注册，
     * 防止带病 schema 静默进入模型请求体导致调用必失败。
     * </p>
     */
    public void init() {
        List<String> schemaErrors = new ArrayList<>();
        // 预构建静态工具名索引：getTool 执行热路径直接命中，避免每次全量重建
        for (CuteTool tool : staticTools) {
            if (tool == null || tool.getName() == null) {
                continue;
            }
            // Schema 启动期卫兵：校验失败的工具拒绝注册并汇总报错
            List<String> errors = ToolSchemaValidator.validate(tool.getName(), tool.getArgumentSchema());
            if (!errors.isEmpty()) {
                schemaErrors.add(tool.getName() + " -> " + errors);
                continue;
            }
            staticToolIndex.put(tool.getName().toLowerCase(Locale.ROOT), tool);
            log.info("已注册静态工具: {}", tool.getName());
        }
        if (!schemaErrors.isEmpty()) {
            throw new IllegalStateException("存在参数 Schema 非法的工具，已拒绝注册: " + schemaErrors);
        }
        log.info("发现并初始化工具注册中心，静态工具共加载了 {} 个，全局动态工具源 {} 个",
                staticToolIndex.size(), globalToolProviders.map(List::size).orElse(0));
    }

    /**
     * 获取当前所有可用工具的快照（静态工具走预构建索引，动态工具实时解析）
     */
    public List<CuteTool> getAllTools(AgentContext context) {
        List<CuteTool> all = new ArrayList<>(staticToolIndex.values());

        // 1. 获取当前会话专属的动态工具（会话级 MCP 等，宿主装载进上下文）
        if (context != null) {
            for (DynamicToolProvider provider : context.getDynamicToolProviders()) {
                mergeProviderTools(all, provider);
            }
        }

        // 2. 融合加载全局共享的动态工具（全局 MCP 等，零候选时安全降级为空）
        if (globalToolProviders.isPresent()) {
            for (DynamicToolProvider provider : globalToolProviders.get()) {
                mergeProviderTools(all, provider);
            }
        }
        return all;
    }

    /**
     * 根据名称查找工具（精确匹配工具的 getName() 返回值，忽略大小写）。
     * <p>
     * 执行热路径方法：静态工具直接命中预构建索引零开销；
     * 动态工具按「会话级 → 全局级」顺序在 provider 的内存暴露清单中实时解析。
     * 动态工具不做缓存：exposedTools 为 volatile 内存清单遍历开销极小，
     * 实时解析可严格保证会话级隔离（不同会话的 MCP 工具互不可见）与工具上下线的即时可见性。
     * </p>
     */
    public CuteTool getTool(String name, AgentContext context) {
        if (name == null || name.isBlank()) {
            return null;
        }

        // 1. 静态索引优先：静态工具不可被动态工具覆盖（注册语义即如此）
        CuteTool staticHit = staticToolIndex.get(name.toLowerCase(Locale.ROOT));
        if (staticHit != null) {
            return staticHit;
        }

        // 2. 动态工具实时解析（保持会话级优先于全局级的既有优先序）
        if (context != null) {
            for (DynamicToolProvider provider : context.getDynamicToolProviders()) {
                CuteTool resolved = findInProvider(provider, name);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        if (globalToolProviders.isPresent()) {
            for (DynamicToolProvider provider : globalToolProviders.get()) {
                CuteTool resolved = findInProvider(provider, name);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return null;
    }

    /**
     * 单个 provider 的工具融合进快照（会话级与全局级共用；provider 为 null 时安全跳过）。
     * 以工具名（忽略大小写）去重：先到先得，静态工具与会话级工具不被后续来源覆盖
     */
    private void mergeProviderTools(List<CuteTool> sink, DynamicToolProvider provider) {
        if (provider == null || !provider.isRunning()) {
            return;
        }
        List<CuteTool> exposed = provider.getExposedTools();
        if (exposed == null || exposed.isEmpty()) {
            return;
        }
        for (CuteTool tool : exposed) {
            if (Objects.isNull(tool) || tool.getName() == null) {
                continue;
            }
            String key = tool.getName().toLowerCase(Locale.ROOT);
            if (sink.stream().noneMatch(t -> t.getName().toLowerCase(Locale.ROOT).equals(key))) {
                sink.add(tool);
            }
        }
    }

    /**
     * 在单个 provider 的暴露工具中按名查找（忽略大小写）
     */
    private CuteTool findInProvider(DynamicToolProvider provider, String name) {
        if (provider == null || !provider.isRunning()) {
            return null;
        }
        List<CuteTool> exposed = provider.getExposedTools();
        if (exposed == null || exposed.isEmpty()) {
            return null;
        }
        for (CuteTool tool : exposed) {
            if (tool != null && tool.getName() != null && tool.getName().equalsIgnoreCase(name)) {
                return tool;
            }
        }
        return null;
    }
}
