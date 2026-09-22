package com.stioc.cute.runtime.tool;

import com.stioc.cute.engine.loop.ApprovalRuleWriter;
import com.stioc.cute.engine.tool.types.ToolPermissionDecision;
import com.stioc.cute.permission.PermissionService;
import com.stioc.cute.project.ProjectService;
import com.stioc.cute.permission.types.PermissionRule;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import org.springframework.util.StringUtils;

/**
 * 审批授权持久化供血实现：包装权限服务的本地规则写入。
 */
@Component
public class ApprovalRuleWriterImpl implements ApprovalRuleWriter {

    @Resource
    private PermissionService permissionService;
    @Resource
    private ProjectService projectService;

    @Override
    public void writeAllowRule(String toolName, String contentPattern, String workspaceId) {
        PermissionRule rule = new PermissionRule(toolName, contentPattern, ToolPermissionDecision.ALLOW.name());
        String projectBasePath = (projectService != null && StringUtils.hasText(workspaceId))
                ? projectService.getProjectBasePath(workspaceId) : null;
        if (projectBasePath != null) {
            permissionService.writeLocalPermissionRule(rule, projectBasePath);
        } else {
            permissionService.writeLocalPermissionRule(rule);
        }
    }
}
