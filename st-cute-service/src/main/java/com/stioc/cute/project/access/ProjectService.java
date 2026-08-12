package com.stioc.cute.project.access;

import java.util.List;
import java.util.Optional;

/**
 * 项目管理服务接口，定义模块对外暴露的操作契约
 */
public interface ProjectService {

    /**
     * 获取全部已登记注册的物理项目列表
     */
    List<ProjectEntity> findAll();

    /**
     * 根据工作区物理根路径检索注册的项目实体
     */
    Optional<ProjectEntity> findByPath(String path);

    /**
     * 根据主键 ID 获取注册的项目数据
     */
    Optional<ProjectEntity> findById(Long id);

    /**
     * 保存或物理插入项目登记记录
     */
    ProjectEntity save(ProjectEntity project);

    /**
     * 根据主键物理删除该项目注册行
     */
    void deleteById(Long id);

    /**
     * 更新指定项目的展开/折叠状态
     */
    void updateExpanded(Long id, Boolean expanded);

    /**
     * 将指定项目标记为唯一的当前活跃项目
     */
    void setActiveProject(Long id);

    /**
     * 获取当前活跃项目（如果存在多个 active 为 true 的记录，容错取 id 最大的一个）
     */
    Optional<ProjectEntity> findActiveProject();
}
