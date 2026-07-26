package com.stioc.cute.security.access;

import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.conversation.access.ConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.Resource;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 统一的工作区路径解析器，用于规范化项目的绝对与相对路径，权限校验和工具执行共享此解析逻辑。
 */
@Slf4j
@Component
public class WorkspacePathResolver {

    @Resource
    private ConversationService conversationService;

    /**
     * 获取当前会话执行时的项目根路径。
     * 优先级：worktreePath > DB 中的项目路径 > JVM 工作目录。
     */
    public String getProjectBasePath(AgentContext context) {
        if (context != null) {
            if (StringUtils.hasText(context.getWorktreePath())) {
                return context.getWorktreePath();
            }
            if (context.getCid() != null) {
                String path = conversationService.getProjectPath(context.getCid());
                if (StringUtils.hasText(path)) {
                    return path;
                }
            }
        }
        return System.getProperty("user.dir");
    }

    /**
     * 将传入的路径解析为绝对规范路径。
     * 绝对路径直接规范化；相对路径以项目根路径为基准解析。
     */
    public Path resolvePath(String pathVal, AgentContext context) {
        if (pathVal == null) {
            return null;
        }
        Path path = Paths.get(pathVal);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        String baseDir = getProjectBasePath(context);
        return Paths.get(baseDir).resolve(path).toAbsolutePath().normalize();
    }
}
