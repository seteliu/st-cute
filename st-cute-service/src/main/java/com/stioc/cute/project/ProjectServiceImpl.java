package com.stioc.cute.project;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import com.stioc.cute.project.access.ProjectEntity;
import com.stioc.cute.project.access.ProjectService;
import com.stioc.cute.repository.ProjectMapper;
import com.stioc.cute.platform.contract.ContractWsBroadcast;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import com.stioc.cute.conversation.access.ConversationService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 项目管理业务逻辑层
 */
@Slf4j
@Service
public class ProjectServiceImpl implements ProjectService {

    @Resource
    private ProjectMapper projectMapper;

    @Resource
    @Lazy
    private ConversationService conversationService;

    @Resource
    private ContractWsBroadcast contractWsBroadcast;

    @Override
    public List<ProjectEntity> findAll() {
        return projectMapper.selectAll();
    }

    @Override
    public Optional<ProjectEntity> findByPath(String path) {
        QueryWrapper query = QueryWrapper.create()
                .where(ProjectEntity::getPath).eq(path);
        return Optional.ofNullable(projectMapper.selectOneByQuery(query));
    }

    @Override
    public Optional<ProjectEntity> findById(Long id) {
        return Optional.ofNullable(projectMapper.selectOneById(id));
    }

    @Override
    public ProjectEntity save(ProjectEntity project) {
        boolean isNew = project.getId() == null;
        if (project.getExpanded() == null) {
            project.setExpanded(true);
        }
        if (project.getActive() == null) {
            project.setActive(false);
        }
        if (isNew) {
            projectMapper.insert(project);
            // 新建项目保存后，直接在后端将其标记为当前唯一的活跃项目
            setActiveProject(project.getId());
            project.setActive(true);
            contractWsBroadcast.broadcast(ContractWsBroadcast.EventType.PROJECT_CREATED, project);
        } else {
            projectMapper.update(project);
        }
        return project;
    }

    @Override
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

        contractWsBroadcast.broadcast(ContractWsBroadcast.EventType.PROJECT_DELETED, id);
    }

    @Override
    public void updateExpanded(Long id, Boolean expanded) {
        if (id == null) return;
        UpdateChain.of(projectMapper)
                .set(ProjectEntity::getExpanded, expanded)
                .where(ProjectEntity::getId).eq(id)
                .update();
    }

    @Override
    @Transactional
    public void setActiveProject(Long id) {
        if (id == null) return;
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

    @Override
    public Optional<ProjectEntity> findActiveProject() {
        // 1. 如果数据库中有被标记为 active 的项目，按 id 降序排列取最大的一个
        QueryWrapper query = QueryWrapper.create()
                .where(ProjectEntity::getActive).eq(true)
                .orderBy(ProjectEntity::getId, false)
                .limit(1);
        ProjectEntity activeProj = projectMapper.selectOneByQuery(query);
        if (activeProj != null) {
            return Optional.of(activeProj);
        }

        // 2. 降级兜底逻辑：如果 active 全部为 0，按 id 降序排列取 id 最大的项目
        QueryWrapper fallbackQuery = QueryWrapper.create()
                .orderBy(ProjectEntity::getId, false)
                .limit(1);
        return Optional.ofNullable(projectMapper.selectOneByQuery(fallbackQuery));
    }
}
