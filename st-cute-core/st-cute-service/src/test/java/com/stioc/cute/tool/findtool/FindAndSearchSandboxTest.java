package com.stioc.cute.tool.findtool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索与检索工具套件（SearchSandboxGuard / FindFilesTool / GrepSearchTool）沙箱集成测试。
 * 覆盖沙箱越界拦截守卫、浅层与递归 Glob 文件查找、常规目录排除与正文文本/正则检索。
 */
class FindAndSearchSandboxTest {

    @TempDir
    Path tempDir;

    private FindFilesTool findFilesTool;
    private GrepSearchTool grepSearchTool;

    private AgentContext agentContext;
    private ToolExecutionContext execContext;
    private SandboxProjectService projectService;

    static class SandboxProjectService extends ProjectService {
        private final Path root;

        SandboxProjectService(Path root) {
            this.root = root;
        }

        @Override
        public String getProjectBasePath(AgentContext context) {
            return root.toAbsolutePath().toString();
        }

        @Override
        public Path resolvePath(String pathVal, AgentContext context) {
            if (pathVal == null) {
                return null;
            }
            Path p = Paths.get(pathVal);
            return p.isAbsolute() ? p.normalize() : root.resolve(p).toAbsolutePath().normalize();
        }
    }

    private static void injectDependencies(AbstractFindTool tool, ProjectService ps) {
        try {
            Field psField = AbstractFindTool.class.getDeclaredField("projectService");
            psField.setAccessible(true);
            psField.set(tool, ps);

            ContractProperty cp = new ContractProperty();
            Field cpField = AbstractFindTool.class.getDeclaredField("contractProperty");
            cpField.setAccessible(true);
            cpField.set(tool, cp);
        } catch (Exception e) {
            throw new RuntimeException("注入检索工具依赖失败", e);
        }
    }

    @BeforeEach
    void setUp() {
        projectService = new SandboxProjectService(tempDir);

        findFilesTool = new FindFilesTool();
        grepSearchTool = new GrepSearchTool();

        injectDependencies(findFilesTool, projectService);
        injectDependencies(grepSearchTool, projectService);

        agentContext = new AgentContext(888L, null, null);
        execContext = new ToolExecutionContext(agentContext, "call_find_001");
    }

    @Nested
    @DisplayName("SearchSandboxGuard 沙箱守卫拦截测试")
    class SandboxGuardTests {

        @Test
        @DisplayName("项目内部合法路径放行（返回 null）")
        void allowPathInsideProjectRoot() {
            Path inner = tempDir.resolve("src/main/App.java");
            String verdict = SearchSandboxGuard.check(inner, agentContext, projectService, "rootDir");
            assertNull(verdict, "项目根目录下的路径应当放行");
        }

        @Test
        @DisplayName("沙箱外绝对越界路径拦截并返回错误文案")
        void rejectPathOutsideSandbox() {
            // 构造明显不在项目根、临时目录与用户主目录的越界路径
            Path outside = Paths.get("Z:/SecretServerFolder/passwords.txt");
            String verdict = SearchSandboxGuard.check(outside, agentContext, projectService, "rootDir");
            assertNotNull(verdict, "沙箱外路径必须被守卫拦截");
            assertTrue(verdict.contains("越界") || verdict.contains("沙箱"));
        }
    }

    @Nested
    @DisplayName("FindFilesTool 文件与目录 Glob 检索测试")
    class FindFilesToolTests {

        @Test
        @DisplayName("浅层模式：pattern 为 '*' 时仅列出第一层文件与目录")
        void shallowListDirectChildren() throws IOException {
            Files.writeString(tempDir.resolve("root.txt"), "content");
            Path subDir = Files.createDirectory(tempDir.resolve("subDir"));
            Files.writeString(subDir.resolve("nested.txt"), "nested");

            String resultJson = findFilesTool.execute(Map.of("pattern", "*"), execContext);
            JSONArray files = JSON.parseArray(resultJson);
            assertNotNull(files);

            // 包含 root.txt 和 subDir/，但不应递归直接包含 nested.txt（浅层）
            assertTrue(files.contains("root.txt"));
            assertTrue(files.contains("subDir/"));
            assertFalse(files.contains("subDir/nested.txt"));
        }

        @Test
        @DisplayName("递归模式：pattern 为 '**/*.txt' 递归查找所有匹配文件并默认跳过 target 产物")
        void recursiveGlobWithExclusion() throws IOException {
            Files.createDirectories(tempDir.resolve("src/main"));
            Files.writeString(tempDir.resolve("src/main/A.txt"), "A");

            // 创建应被默认跳过的 target 目录
            Files.createDirectories(tempDir.resolve("target/classes"));
            Files.writeString(tempDir.resolve("target/classes/B.txt"), "B");

            String resultJson = findFilesTool.execute(Map.of("pattern", "**/*.txt"), execContext);
            JSONArray files = JSON.parseArray(resultJson);
            assertNotNull(files);

            assertTrue(files.stream().anyMatch(f -> f.toString().contains("A.txt")));
            // 默认排除 target
            assertFalse(files.stream().anyMatch(f -> f.toString().contains("target")));
        }
    }

    @Nested
    @DisplayName("GrepSearchTool 正文文本与正则检索测试")
    class GrepSearchToolTests {

        @Test
        @DisplayName("普通文本子串搜索返回匹配文件名、行号与行内容")
        void plainSubstringSearch() throws IOException {
            Path file = tempDir.resolve("Hello.java");
            Files.writeString(file, "line 1\nString targetKeyword = \"FOUND_ME\";\nline 3\n");

            String result = grepSearchTool.execute(Map.of("query", "FOUND_ME"), execContext);
            assertNotNull(result);
            assertTrue(result.contains("已找到以下匹配行:"));
            assertTrue(result.contains("[Hello.java:2]"));
            assertTrue(result.contains("FOUND_ME"));
        }

        @Test
        @DisplayName("正则表达式检索与大小写忽略")
        void regexAndCaseInsensitiveSearch() throws IOException {
            Path file = tempDir.resolve("Order.log");
            Files.writeString(file, "User ID: ORDER-12345 placed successfully\n");

            String result = grepSearchTool.execute(Map.of(
                    "query", "order-\\d{5}",
                    "useRegex", true,
                    "ignoreCase", true
            ), execContext);

            assertNotNull(result);
            assertTrue(result.contains("已找到以下匹配行:"));
            assertTrue(result.contains("[Order.log:1]"));
            assertTrue(result.contains("ORDER-12345"));
        }

        @Test
        @DisplayName("单文件作用域检索：rootDir 传入具体文件路径仅在单文件内查找")
        void singleFileScopeSearch() throws IOException {
            Path targetFile = tempDir.resolve("Target.txt");
            Files.writeString(targetFile, "alpha\nbeta\n");

            Path otherFile = tempDir.resolve("Other.txt");
            Files.writeString(otherFile, "beta in other\n");

            String result = grepSearchTool.execute(Map.of(
                    "query", "beta",
                    "rootDir", "Target.txt"
            ), execContext);

            assertNotNull(result);
            assertTrue(result.contains("已找到以下匹配行:"));
            assertTrue(result.contains("[Target.txt:2]"));
            assertFalse(result.contains("Other.txt"));
        }
    }
}
