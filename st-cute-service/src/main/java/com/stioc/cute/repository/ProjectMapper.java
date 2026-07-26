package com.stioc.cute.repository;

import com.mybatisflex.core.BaseMapper;
import com.stioc.cute.project.access.ProjectEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectMapper extends BaseMapper<ProjectEntity> {
}
