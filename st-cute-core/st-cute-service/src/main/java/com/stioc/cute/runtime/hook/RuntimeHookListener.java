package com.stioc.cute.runtime.hook;

import com.stioc.cute.engine.hook.HookListener;
import com.stioc.cute.engine.hook.HookPayload;
import com.stioc.cute.engine.hook.HookType;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.hook.HookService;
import com.stioc.cute.hook.types.HookContext;
import com.stioc.cute.hook.types.HookEventType;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 运行时 Hook 监听器：实现引擎生命周期挂点扩展，转译触发宿主 Hook 机制（hooks.json 规则）。
 * <p>
 * 引擎 5 个挂点（ON_CONTEXT_START / ON_LOOP_START / ON_LOOP_END / ON_TOOL_CALL / ON_TOOL_COMPLETE）
 * 在 cid 数据锁内同步直调本监听器并翻译为宿主 HookService.triggerHook 调用；
 * 阻断语义：宿主 Hook 抛出的异常向上传播，由引擎侧调用点 try-catch 捕获拦截工具执行。
 * </p>
 */
@Slf4j
@Component
public class RuntimeHookListener implements HookListener {

    @Resource
    private HookService hookService;

    @Override
    public void onHook(HookType type, HookPayload payload, AgentContext context) throws Exception {
        if (type == null || context == null) {
            return;
        }
        HookEventType hostEvent = mapToHostEvent(type);
        if (hostEvent == null) {
            return;
        }

        // 强类型挂点载荷还原为宿主 HookContext（生命周期挂点载荷为 null）
        HookContext.HookContextBuilder builder = HookContext.builder()
                .cid(context.getCid())
                .agentContext(context);
        if (payload != null) {
            builder.toolCallId(payload.getToolCallId())
                    .toolName(payload.getToolName())
                    .toolArgs(payload.getToolArgs())
                    .filePath(payload.getTargetResource())
                    .toolResult(payload.getToolResult());
        }

        try {
            hookService.triggerHook(hostEvent, builder.build());
        } catch (Exception e) {
            // 阻断语义：向上传播异常，让引擎侧调用点的 try-catch 捕获并拦截工具执行
            throw new RuntimeException("宿主生命周期 Hook 阻断: " + e.getMessage(), e);
        }
    }

    /**
     * 引擎挂点类型 → 宿主 Hook 事件枚举映射
     */
    private HookEventType mapToHostEvent(HookType type) {
        return switch (type) {
            case ON_CONTEXT_START -> HookEventType.ON_CONTEXT_START;
            case ON_LOOP_START -> HookEventType.ON_LOOP_START;
            case ON_LOOP_END -> HookEventType.ON_LOOP_END;
            case ON_TOOL_CALL -> HookEventType.ON_TOOL_CALL;
            case ON_TOOL_COMPLETE -> HookEventType.ON_TOOL_COMPLETE;
        };
    }
}
