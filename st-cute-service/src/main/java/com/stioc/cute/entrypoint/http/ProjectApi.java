package com.stioc.cute.entrypoint.http;

import com.stioc.cute.platform.common.Result;
import com.stioc.cute.platform.common.BusinessException;
import com.stioc.cute.project.access.ProjectEntity;
import com.stioc.cute.project.access.ProjectService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 项目管理的 HTTP REST API
 */
@Slf4j
@RestController
@RequestMapping("/api/project")
public class ProjectApi {

    @Resource
    private ProjectService projectService;


    /**
     * 获取全部注册管理的物理项目列表
     */
    @GetMapping("/list")
    public Result<List<ProjectEntity>> getProjects() {
        List<ProjectEntity> list = projectService.findAll();
        return Result.success(list);
    }

    /**
     * 新增或更新已注册的物理项目记录
     */
    @PostMapping("/save")
    public Result<ProjectEntity> saveProject(@RequestBody ProjectEntity project) {
        if (!StringUtils.hasText(project.getPath())) {
            throw new BusinessException("项目路径不能为空");
        }

        String path = project.getPath().trim();
        project.setPath(path);

        File file = new File(path);
        if (!file.exists()) {
            throw new BusinessException("项目物理路径在磁盘中不存在，请输入真实的物理路径");
        }
        if (!file.isDirectory()) {
            throw new BusinessException("项目路径必须是一个文件夹目录，不能指向具体文件");
        }

        // 重复性校验
        Optional<ProjectEntity> existing = projectService.findByPath(path);
        if (existing.isPresent()) {
            ProjectEntity ext = existing.get();
            if (!ext.getId().equals(project.getId())) {
                throw new BusinessException("该项目物理路径已被其他项目使用");
            }
        }

        if (project.getId() == null) {
            project.setCreatedAt(LocalDateTime.now());
            if (!StringUtils.hasText(project.getName())) {
                // 默认取最后一节文件夹名
                String name = extractLastFolder(path);
                project.setName(name);
            }
        }

        ProjectEntity saved = projectService.save(project);
        log.info("保存项目成功: {}, path={}", saved.getName(), saved.getPath());
        return Result.success(saved);
    }

    /**
     * 根据主键物理级联删除注册的项目记录
     */
    @DeleteMapping("/delete")
    public Result<Boolean> deleteProject(@RequestParam Long id) {
        log.info("请求删除项目及其所有会话, id={}", id);
        Optional<ProjectEntity> projectOpt = projectService.findById(id);
        if (projectOpt.isEmpty()) {
            return Result.success(true);
        }

        projectService.deleteById(id);
        return Result.success(true);
    }

    /**
     * 更新指定项目的展开/折叠状态
     */
    @PostMapping("/update-expanded")
    public Result<Boolean> updateExpanded(@RequestParam Long id, @RequestParam Boolean expanded) {
        projectService.updateExpanded(id, expanded);
        return Result.success(true);
    }

    /**
     * 设置指定项目为当前活跃会话项目
     */
    @PostMapping("/set-active")
    public Result<Boolean> setActiveProject(@RequestParam Long id) {
        projectService.setActiveProject(id);
        return Result.success(true);
    }

    private String extractLastFolder(String path) {
        if (path == null) return "新项目";
        String normalized = path.replace("\\", "/");
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int idx = normalized.lastIndexOf("/");
        if (idx >= 0 && idx < normalized.length() - 1) {
            return normalized.substring(idx + 1);
        }
        return path;
    }
}
