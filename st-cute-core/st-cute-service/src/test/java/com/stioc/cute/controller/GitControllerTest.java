package com.stioc.cute.controller;

import com.stioc.cute.git.GitService;
import com.stioc.cute.git.types.GitBranchVo;
import com.stioc.cute.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link GitController} 切片测试。
 * <p>覆盖基准目录解析（会话绑定项目优先，未绑定回退全局）与 diff 参数透传。</p>
 */
@WebMvcTest(controllers = GitController.class)
@Import(GitController.class)
class GitControllerTest extends AbstractControllerSliceTest {

    @MockitoBean
    private GitService gitService;
    @MockitoBean
    private ProjectService projectService;

    @BeforeEach
    void pinMode() {
        ensurePasswordlessMode();
    }

    @Test
    @DisplayName("list：会话绑定项目时以项目路径为基准查分支")
    void listBranchesWithProjectBasePath() throws Exception {
        when(projectService.getProjectBasePathByCid(5L)).thenReturn("P:/GitProject/demo");
        when(gitService.getBranches("P:/GitProject/demo")).thenReturn(List.of(
                new GitBranchVo("P:/GitProject/demo", "master", true)));

        mockMvc.perform(localGet("/api/git/list").param("cid", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].branch").value("master"))
                .andExpect(jsonPath("$.data[0].current").value(true));
    }

    @Test
    @DisplayName("list：cid 缺省或未绑定项目时回退空基准路径（由 GitService 内部再解析）")
    void listBranchesFallbackBasePath() throws Exception {
        when(projectService.getProjectBasePathByCid(null)).thenReturn(null);
        when(gitService.getBranches(null)).thenReturn(List.of());

        mockMvc.perform(localGet("/api/git/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(gitService).getBranches(null);
    }

    @Test
    @DisplayName("diff：分支名与基准 commit 透传")
    void getDiffWithBaseCommit() throws Exception {
        when(projectService.getProjectBasePathByCid(5L)).thenReturn("P:/GitProject/demo");
        when(gitService.getBranchDiff("P:/GitProject/demo", "feature-x", "abc123")).thenReturn(List.of());

        mockMvc.perform(localGet("/api/git/diff")
                        .param("cid", "5")
                        .param("branchName", "feature-x")
                        .param("baseCommit", "abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(gitService).getBranchDiff("P:/GitProject/demo", "feature-x", "abc123");
    }
}
