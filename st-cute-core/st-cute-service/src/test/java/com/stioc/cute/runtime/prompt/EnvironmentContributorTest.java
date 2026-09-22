package com.stioc.cute.runtime.prompt;

import com.stioc.cute.engine.llm.ProviderResolver;
import com.stioc.cute.engine.llm.types.Provider;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.permission.types.PermissionMode;
import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coding 环境信息提示词贡献者测试。
 * 验证环境段落格式、稳定前缀缓存友好排序 (order=300)、反斜杠归一化与模型信息注入。
 */
class EnvironmentContributorTest {

    private EnvironmentContributor contributor;

    @BeforeEach
    void setUp() throws Exception {
        contributor = new EnvironmentContributor();

        ProviderResolver providerResolver = new ProviderResolver() {
            @Override
            public Provider getProviderConfigForContext(AgentContext context) {
                Provider provider = new Provider();
                provider.setModelName("gemini-2.5-flash");
                return provider;
            }
        };

        ProjectService projectService = new ProjectService() {
            @Override
            public String getProjectBasePath(AgentContext context) {
                return "D:/projects/st-cute-backend";
            }
        };

        Field prField = EnvironmentContributor.class.getDeclaredField("providerResolver");
        prField.setAccessible(true);
        prField.set(contributor, providerResolver);

        Field psField = EnvironmentContributor.class.getDeclaredField("projectService");
        psField.setAccessible(true);
        psField.set(contributor, projectService);
    }

    @Test
    @DisplayName("排序固定为 300，位于末尾以最大化稳定前缀的 LLM 缓存命中范围")
    void verifyContributorOrder() {
        assertEquals(300, contributor.order());
    }

    @Test
    @DisplayName("contribute 生成结构化环境提示词并正确填充各段字段")
    void verifyEnvironmentPromptStructure() {
        AgentContext context = new AgentContext(555L, null, null);
        context.setPermissionMode(PermissionMode.RELAXED_APPROVAL.name());

        String prompt = contributor.contribute(context);
        assertNotNull(prompt);

        // 段落标题
        assertTrue(prompt.contains("【环境信息】"));

        // 操作系统
        assertTrue(prompt.contains("- 操作系统平台: " + System.getProperty("os.name")));

        // 用户级目录必须将反斜杠替换为正斜杠
        String globalPath = ContractFile.getGlobalDir().getAbsolutePath().replace("\\", "/");
        assertTrue(prompt.contains(globalPath), "用户级目录路径应规范化正斜杠展示");

        // 运行模型名称
        assertTrue(prompt.contains("- 运行大模型: gemini-2.5-flash"));

        // 工作目录
        assertTrue(prompt.contains("- 当前工作目录: D:/projects/st-cute-backend"));

        // 权限模式说明
        assertTrue(prompt.contains("【宽松审批模式】"));

        // 会话 ID 与临时目录约定
        assertTrue(prompt.contains("- 当前会话 ID (cid): 555"));
        assertTrue(prompt.contains("/tmp/cid_555/"));
    }
}
