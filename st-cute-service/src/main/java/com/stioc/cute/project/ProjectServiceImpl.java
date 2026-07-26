package com.stioc.cute.project;

import com.mybatisflex.core.query.QueryWrapper;
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
        if (isNew) {
            projectMapper.insert(project);
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
        // 级联物理删除该项目下的会话以及会话消息，并且从内存中移除
        conversationService.deleteConversationsByProjectId(id);
        projectMapper.deleteById(id);
        contractWsBroadcast.broadcast(ContractWsBroadcast.EventType.PROJECT_DELETED, id);
    }
}
