package com.stioc.cute.runtime.tool;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.ToolGuard;
import com.stioc.cute.engine.tool.types.ToolPermissionVerdict;
import com.stioc.cute.permission.PermissionService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 引擎工具安全守卫供血实现：包装宿主权限服务。
 */
@Component
public class ToolGuardImpl implements ToolGuard {

    @Resource
    private PermissionService permissionService;

    @Override
    public ToolPermissionVerdict evaluate(CuteTool tool, Map<String, Object> args, AgentContext context) {
        return permissionService.evaluateVerdict(tool, args, context);
    }
}
