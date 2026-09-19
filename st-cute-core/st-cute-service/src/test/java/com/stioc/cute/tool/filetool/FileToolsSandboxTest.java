package com.stioc.cute.tool.filetool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.project.ProjectService;
import com.stioc.cute.runtime.loop.RuntimeContext;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件操作工具套件（ReadFileTool / WriteFileTool / EditFileTool）沙箱集成测试。
 * 基于 @TempDir 临时目录与轻量 ProjectService 替身，端到端测试文件的创建、读取、
 * 行号截取、局部原子替换及防幻觉未读先改安全门禁。
 */
class FileToolsSandboxTest {

    @TempDir
    Path tempDir;

    private ReadFileTool readFileTool;
    private WriteFileTool writeFileTool;
    private EditFileTool editFileTool;

    private AgentContext agentContext;
    private RuntimeContext runtimeContext;
    private ToolExecutionContext execContext;

    /**
     * 极简 ProjectService 替身，仅将路径解析到临时目录，不触碰数据库与 Spring
     */
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

    private static void injectProjectService(AbstractFileTool tool, ProjectService ps) {
        try {
            Field field = AbstractFileTool.class.getDeclaredField("projectService");
            field.setAccessible(true);
            field.set(tool, ps);
        } catch (Exception e) {
            throw new RuntimeException("注入 ProjectService 替身失败", e);
        }
    }

    @BeforeEach
    void setUp() {
        SandboxProjectService projectService = new SandboxProjectService(tempDir);

        readFileTool = new ReadFileTool();
        writeFileTool = new WriteFileTool();
        editFileTool = new EditFileTool();

        injectProjectService(readFileTool, projectService);
        injectProjectService(writeFileTool, projectService);
        injectProjectService(editFileTool, projectService);

        agentContext = new AgentContext(999L, null, null);
        runtimeContext = new RuntimeContext(999L);
        agentContext.putExtraContext(runtimeContext);

        execContext = new ToolExecutionContext(agentContext, "call_sandbox_001");
    }

    @Nested
    @DisplayName("WriteFileTool 新建与覆写测试")
    class WriteFileToolTests {

        @Test
        @DisplayName("自动级联创建深层父目录并成功写入文件")
        void writeCreatingParentDirectories() throws IOException {
            String relativePath = "src/sub/nested/Hello.java";
            String code = "public class Hello {}";

            String resultJson = writeFileTool.execute(Map.of(
                    "path", relativePath,
                    "content", code
            ), execContext);

            JSONObject json = JSON.parseObject(resultJson);
            assertNotNull(json);
            assertTrue(json.getBooleanValue("success"));

            Path target = tempDir.resolve("src/sub/nested/Hello.java");
            assertTrue(Files.exists(target));
            assertEquals(code, Files.readString(target));
        }

        @Test
        @DisplayName("覆写已有文件内容")
        void overwriteExistingFile() throws IOException {
            Path file = tempDir.resolve("data.txt");
            Files.writeString(file, "v1");

            writeFileTool.execute(Map.of(
                    "path", "data.txt",
                    "content", "v2"
            ), execContext);

            assertEquals("v2", Files.readString(file));
        }
    }

    @Nested
    @DisplayName("ReadFileTool 读取与切片测试")
    class ReadFileToolTests {

        @Test
        @DisplayName("读取完整文件并附带规范行号前缀")
        void readWholeFileWithLineNumbers() throws IOException {
            Path file = tempDir.resolve("sample.txt");
            Files.writeString(file, "line A\nline B\nline C\n");

            String content = readFileTool.execute(Map.of("path", "sample.txt"), execContext);
            assertNotNull(content);
            assertTrue(content.contains("1: line A"));
            assertTrue(content.contains("2: line B"));
            assertTrue(content.contains("3: line C"));
        }

        @Test
        @DisplayName("按 startLine 和 lineCount 精确切片读取")
        void readLineRange() throws IOException {
            Path file = tempDir.resolve("range.txt");
            Files.writeString(file, "10\n20\n30\n40\n50\n");

            String content = readFileTool.execute(Map.of(
                    "path", "range.txt",
                    "startLine", 2,
                    "lineCount", 3
            ), execContext);

            assertNotNull(content);
            assertFalse(content.contains("1: 10"));
            assertTrue(content.contains("2: 20"));
            assertTrue(content.contains("3: 30"));
            assertTrue(content.contains("4: 40"));
            assertFalse(content.contains("5: 50"));
        }

        @Test
        @DisplayName("读取不存在的文件返回明确错误")
        void readNonExistentFileReturnsError() {
            String resultJson = readFileTool.execute(Map.of("path", "missing.txt"), execContext);
            JSONObject json = JSON.parseObject(resultJson);
            assertTrue(json.containsKey("error"));
            assertTrue(json.getString("error").contains("文件不存在"));
        }
    }

    @Nested
    @DisplayName("EditFileTool 局部替换与防幻觉哈希门禁测试")
    class EditFileToolSecurityAndReplaceTests {

        @Test
        @DisplayName("未读先改拦截：未通过 read_file 读取过的文件直接 edit_file 被硬拦截")
        void unreadBeforeWriteRejection() throws IOException {
            Path file = tempDir.resolve("Guard.java");
            Files.writeString(file, "public class Guard { int v = 1; }");

            // 尚未调用 read_file，runtimeContext 中无哈希记录
            String resultJson = editFileTool.execute(Map.of(
                    "path", "Guard.java",
                    "oldContent", "int v = 1;",
                    "newContent", "int v = 2;"
            ), execContext);

            JSONObject json = JSON.parseObject(resultJson);
            assertTrue(json.containsKey("error"));
            assertTrue(json.getString("error").contains("拒绝执行代码修改"));
            assertTrue(json.getString("error").contains("尚未读取过该文件"));

            // 物理文件内容未被修改
            assertEquals("public class Guard { int v = 1; }", Files.readString(file));
        }

        @Test
        @DisplayName("正常合规链路：先 read_file 记录哈希，再 edit_file 局部替换成功")
        void readThenEditSuccess() throws IOException {
            Path file = tempDir.resolve("App.java");
            Files.writeString(file, "public class App {\n    String name = \"old\";\n}\n");

            // 1. read_file 建立门禁授信
            String readResult = readFileTool.execute(Map.of("path", "App.java"), execContext);
            assertTrue(readResult.contains("1: public class App"));

            // 2. edit_file 执行替换
            String editResult = editFileTool.execute(Map.of(
                    "path", "App.java",
                    "oldContent", "String name = \"old\";",
                    "newContent", "String name = \"new\";"
            ), execContext);

            JSONObject json = JSON.parseObject(editResult);
            assertTrue(json.getBooleanValue("success"));

            // 3. 校验物理文件内容已被精准替换
            String updated = Files.readString(file);
            assertEquals("public class App {\n    String name = \"new\";\n}\n", updated);
        }

        @Test
        @DisplayName("过时修改拦截：读取后外部修改文件导致哈希变化，再次 edit_file 被拦截")
        void staleContextRejection() throws IOException {
            Path file = tempDir.resolve("Sync.java");
            Files.writeString(file, "int x = 1;");

            // 1. read_file 记录当前哈希
            readFileTool.execute(Map.of("path", "Sync.java"), execContext);

            // 2. 模拟外部偷偷修改了文件
            Files.writeString(file, "int x = 999;");

            // 3. edit_file 尝试修改，应被拦截防过时篡改
            String editResult = editFileTool.execute(Map.of(
                    "path", "Sync.java",
                    "oldContent", "int x = 1;",
                    "newContent", "int x = 2;"
            ), execContext);

            JSONObject json = JSON.parseObject(editResult);
            assertTrue(json.containsKey("error"));
            assertTrue(json.getString("error").contains("已发生变化"));
        }
    }
}
