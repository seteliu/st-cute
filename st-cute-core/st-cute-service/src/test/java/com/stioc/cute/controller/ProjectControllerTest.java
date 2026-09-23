package com.stioc.cute.controller;

import com.stioc.cute.project.ProjectEntity;
import com.stioc.cute.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ProjectController} 切片测试。
 * <p>重点：save 的物理路径校验链（空路径/不存在/非目录/被占用）与 @TempDir 真目录 happy path、
 * delete 幂等、set-active/update-expanded 透传。</p>
 */
@WebMvcTest(controllers = ProjectController.class)
@Import(ProjectController.class)
class ProjectControllerTest extends AbstractControllerSliceTest {

    @MockitoBean
    private ProjectService projectService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void pinMode() {
        ensurePasswordlessMode();
    }

    @Test
    @DisplayName("list：返回全部注册项目")
    void listProjects() throws Exception {
        ProjectEntity entity = new ProjectEntity();
        entity.setId(1L);
        entity.setName("demo");
        entity.setPath("P:/demo");
        when(projectService.findAll()).thenReturn(List.of(entity));

        mockMvc.perform(localGet("/api/project/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("demo"));
    }

    @Test
    @DisplayName("save：空路径拒绝（BusinessException→code 500）")
    void saveRejectsBlankPath() throws Exception {
        mockMvc.perform(localPost("/api/project/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("项目路径不能为空"));

        verify(projectService, never()).save(any());
    }

    @Test
    @DisplayName("save：物理路径不存在拒绝")
    void saveRejectsNonexistentPath() throws Exception {
        mockMvc.perform(localPost("/api/project/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"X:/no/such/dir\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("项目物理路径在磁盘中不存在，请输入真实的物理路径"));
    }

    @Test
    @DisplayName("save：路径指向具体文件而非目录拒绝")
    void saveRejectsFileNotDirectory() throws Exception {
        Path file = tempDir.resolve("afile.txt");
        Files.writeString(file, "x");

        mockMvc.perform(localPost("/api/project/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"" + file.toAbsolutePath().toString().replace("\\", "/") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("项目路径必须是一个文件夹目录，不能指向具体文件"));
    }

    @Test
    @DisplayName("save：路径已被其他项目占用拒绝")
    void saveRejectsDuplicatedPath() throws Exception {
        ProjectEntity existing = new ProjectEntity();
        existing.setId(99L);
        when(projectService.findByPath(tempDir.toAbsolutePath().toString().replace("\\", "/")))
                .thenReturn(Optional.of(existing));

        mockMvc.perform(localPost("/api/project/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"" + tempDir.toAbsolutePath().toString().replace("\\", "/") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("该项目物理路径已被其他项目使用"));
    }

    @Test
    @DisplayName("save：新建项目无名称时默认取末段文件夹名")
    void saveDefaultsNameToLastFolderSegment() throws Exception {
        when(projectService.findByPath(any())).thenReturn(Optional.empty());
        ProjectEntity saved = new ProjectEntity();
        saved.setId(7L);
        saved.setPath(tempDir.toAbsolutePath().toString().replace("\\", "/"));
        when(projectService.save(any(ProjectEntity.class))).thenReturn(saved);

        mockMvc.perform(localPost("/api/project/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"" + tempDir.toAbsolutePath().toString().replace("\\", "/") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 新建（id=null）时名称回填末段文件夹名且写入创建时间
        verify(projectService).save(argThat(p ->
                p.getName() != null && p.getCreateTime() != null));
    }

    @Test
    @DisplayName("delete：项目不存在时幂等成功")
    void deleteMissingProjectIdempotent() throws Exception {
        when(projectService.findById(404L)).thenReturn(Optional.empty());

        mockMvc.perform(localDelete("/api/project/delete").param("id", "404"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(projectService, never()).deleteById(404L);
    }

    @Test
    @DisplayName("delete：项目存在时级联删除")
    void deleteProject() throws Exception {
        when(projectService.findById(7L)).thenReturn(Optional.of(new ProjectEntity()));

        mockMvc.perform(localDelete("/api/project/delete").param("id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(projectService).deleteById(7L);
    }

    @Test
    @DisplayName("update-expanded：展开状态透传")
    void updateExpanded() throws Exception {
        mockMvc.perform(localPost("/api/project/update-expanded")
                        .param("id", "7")
                        .param("expanded", "true"))
                .andExpect(status().isOk());

        verify(projectService).updateExpanded(7L, true);
    }

    @Test
    @DisplayName("set-active：设置活跃项目")
    void setActiveProject() throws Exception {
        mockMvc.perform(localPost("/api/project/set-active").param("id", "7"))
                .andExpect(status().isOk());

        verify(projectService).setActiveProject(7L);
    }
}
