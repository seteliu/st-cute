package com.stioc.cute.project;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import com.stioc.cute.conversation.ConversationService;
import com.stioc.cute.websocket.WebSocketBroadcast;
import com.stioc.cute.repository.ProjectMapper;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.platform.contract.ContractFile;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 项目管理业务逻辑层，定义模块对外暴露的操作契约
 */
@Slf4j
@Service
public class ProjectService {

    @Resource
    private ProjectMapper projectMapper;

    @Resource
    @Lazy
    private ConversationService conversationService;

    @Resource
    private WebSocketBroadcast webSocketBroadcast;

    /**
     * 获取全部已登记注册的物理项目列表
     */
    public List<ProjectEntity> findAll() {
        return projectMapper.selectAll();
    }

    /**
     * 根据工作区物理根路径检索注册的项目实体
     */
    public Optional<ProjectEntity> findByPath(String path) {
        QueryWrapper query = QueryWrapper.create()
                .where(ProjectEntity::getPath).eq(path);
        return Optional.ofNullable(projectMapper.selectOneByQuery(query));
    }

    /**
     * 根据主键 ID 获取注册的项目数据
     */
    public Optional<ProjectEntity> findById(Long id) {
        return Optional.ofNullable(projectMapper.selectOneById(id));
    }

    /**
     * 获取项目物理根路径（底层核心实现）。
     * 优先根据 projectId 查项目物理路径；未绑定、不存在或未配置时统一兜底返回全局配置目录（~/.st-cute）。
     *
     * @param projectId 项目 ID
     * @return 项目物理绝对根路径；无有效项目时兜底返回全局配置目录
     */
    public String getProjectBasePath(Long projectId) {
        if (projectId != null) {
            String path = findById(projectId)
                    .map(ProjectEntity::getPath)
                    .orElse(null);
            if (StringUtils.hasText(path)) {
                return path;
            }
        }
        return ContractFile.getGlobalDir().getAbsolutePath();
    }

    /**
     * 获取项目物理根路径（workspaceId 重载）。
     * 将 workspaceId（项目 ID 字符串）解析为 Long projectId 后委托调用底层实现。
     *
     * @param workspaceId 工作区标识（项目 ID 字符串）
     * @return 项目物理绝对根路径；无有效项目时兜底返回全局配置目录
     */
    public String getProjectBasePath(String workspaceId) {
        if (StringUtils.hasText(workspaceId)) {
            try {
                return getProjectBasePath(Long.parseLong(workspaceId.trim()));
            } catch (NumberFormatException e) {
                // 非法数字格式跳过
            }
        }
        return getProjectBasePath((Long) null);
    }

    /**
     * 根据会话 ID（cid）获取会话绑定的项目物理根路径。
     * 内部完成 cid → workspaceId → projectBasePath 映射，无有效项目时兜底返回全局配置目录。
     *
     * @param cid 会话 ID
     * @return 项目物理绝对根路径
     */
    public String getProjectBasePathByCid(Long cid) {
        if (cid == null) {
            return getProjectBasePath((Long) null);
        }
        return conversationService.findById(cid)
                .map(conv -> getProjectBasePath(conv.getWorkspaceId()))
                .orElseGet(() -> getProjectBasePath((Long) null));
    }

    /**
     * 获取当前会话上下文的项目物理根路径。
     * 优先从上下文 workspaceId 解析，未绑定或解析失败时兜底返回全局目录。
     *
     * @param context 当前会话上下文
     * @return 项目物理绝对根路径
     */
    public String getProjectBasePath(AgentContext context) {
        return getProjectBasePath(context != null ? context.getWorkspaceId() : null);
    }

    /**
     * 将传入的路径解析为绝对规范路径。
     * 绝对路径直接规范化；相对路径以项目根路径（或兜底全局目录）为基准解析。
     *
     * @param pathVal 待解析路径字符串
     * @param context 当前会话上下文
     * @return 绝对规范化 Path
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

    /**
     * 保存或物理插入项目登记记录
     */
    public ProjectEntity save(ProjectEntity project) {
        boolean isNew = project.getId() == null;
        if (project.getExpanded() == null) {
            project.setExpanded(true);
        }
        if (project.getActive() == null) {
            project.setActive(false);
        }
        LocalDateTime now = LocalDateTime.now();
        if (isNew) {
            if (project.getCreateTime() == null) {
                project.setCreateTime(now);
            }
            if (project.getUpdateTime() == null) {
                project.setUpdateTime(now);
            }
            projectMapper.insert(project);
            // 新建项目保存后，直接在后端将其标记为当前唯一的活跃项目
            setActiveProject(project.getId());
            project.setActive(true);
            webSocketBroadcast.broadcast(WebSocketBroadcast.EventType.PROJECT_CREATED, project);
        } else {
            project.setUpdateTime(now);
            projectMapper.update(project);
        }
        return project;
    }

    /**
     * 根据主键物理删除该项目注册行
     */
    @Transactional
    public void deleteById(Long id) {
        log.info("物理级联删除项目及其会话, id={}", id);
        // 1. 检查被删除的项目是否是当前活跃项目
        Optional<ProjectEntity> targetOpt = findById(id);
        boolean wasActive = targetOpt.map(p -> Boolean.TRUE.equals(p.getActive())).orElse(false);

        // 2. 级联物理删除该项目下的会话以及会话消息，并且从内存中移除
        conversationService.deleteConversationsByProjectId(id);
        projectMapper.deleteById(id);

        // 3. 如果被删除的项目是活跃项目，自动将剩余 ID 最大的项目标记为活跃项目
        if (wasActive) {
            List<ProjectEntity> remain = findAll();
            if (!remain.isEmpty()) {
                remain.sort((a, b) -> Long.compare(b.getId(), a.getId()));
                setActiveProject(remain.get(0).getId());
            }
        }

        webSocketBroadcast.broadcast(WebSocketBroadcast.EventType.PROJECT_DELETED, id);
    }

    /**
     * 更新指定项目的展开/折叠状态
     */
    public void updateExpanded(Long id, Boolean expanded) {
        if (id == null) {
            return;
        }
        UpdateChain.of(projectMapper)
                .set(ProjectEntity::getExpanded, expanded)
                .set(ProjectEntity::getUpdateTime, LocalDateTime.now())
                .where(ProjectEntity::getId).eq(id)
                .update();
    }

    /**
     * 将指定项目标记为唯一的当前活跃项目
     */
    @Transactional
    public void setActiveProject(Long id) {
        if (id == null) {
            return;
        }
        // 1. 将所有 active=true 的项目的 active 字段单列更新为 false
        UpdateChain.of(projectMapper)
                .set(ProjectEntity::getActive, false)
                .where(ProjectEntity::getActive).eq(true)
                .update();

        // 2. 将指定 ID 项目的 active 字段单列更新为 true
        UpdateChain.of(projectMapper)
                .set(ProjectEntity::getActive, true)
                .where(ProjectEntity::getId).eq(id)
                .update();
    }
}
