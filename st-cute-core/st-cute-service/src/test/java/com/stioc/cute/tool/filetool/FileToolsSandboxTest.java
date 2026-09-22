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
        @DisplayName("覆写已有文件内容：先 read 建立门禁授信后覆写成功")
        void overwriteExistingFile() throws IOException {
            Path file = tempDir.resolve("data.txt");
            Files.writeString(file, "v1");

            // 新门禁：覆写非空文件前必须先读过
            readFileTool.execute(Map.of("path", "data.txt"), execContext);

            writeFileTool.execute(Map.of(
                    "path", "data.txt",
                    "content", "v2"
            ), execContext);

            assertEquals("v2", Files.readString(file));
        }

        @Test
        @DisplayName("覆写未读过的非空文件被门禁拦截，物理文件不变")
        void overwriteUnreadFileRejected() throws IOException {
            Path file = tempDir.resolve("Protected.txt");
            Files.writeString(file, "existing content");

            String resultJson = writeFileTool.execute(Map.of(
                    "path", "Protected.txt",
                    "content", "trample"
            ), execContext);

            JSONObject json = JSON.parseObject(resultJson);
            assertTrue(json.containsKey("error"));
            assertTrue(json.getString("error").contains("拒绝覆写"));
            // 物理文件内容未被破坏
            assertEquals("existing content", Files.readString(file));
        }

        @Test
        @DisplayName("空文件覆写豁免门禁：0 字节无内容可毁，直接写入成功")
        void overwriteEmptyFileExempted() throws IOException {
            Path file = tempDir.resolve("empty.txt");
            Files.writeString(file, "");

            String resultJson = writeFileTool.execute(Map.of(
                    "path", "empty.txt",
                    "content", "fresh"
            ), execContext);

            JSONObject json = JSON.parseObject(resultJson);
            assertTrue(json.getBooleanValue("success"));
            assertEquals("fresh", Files.readString(file));
        }

        @Test
        @DisplayName("write 后连续覆写放行：写后哈希自动更新，第二次覆写不自我锁死")
        void consecutiveOverwriteAfterWrite() throws IOException {
            // 1. 新建写入（无门禁）
            writeFileTool.execute(Map.of("path", "chain.txt", "content", "first"), execContext);
            // 2. 紧接着覆写（写后哈希已登记，应放行而非被自己的门禁拦截）
            String resultJson = writeFileTool.execute(Map.of("path", "chain.txt", "content", "second"), execContext);

            JSONObject json = JSON.parseObject(resultJson);
            assertTrue(json.getBooleanValue("success"));
            assertEquals("second", Files.readString(tempDir.resolve("chain.txt")));
        }

        @Test
        @DisplayName("过时覆写拦截：读取后外部修改文件导致哈希变化，再覆写被拦截")
        void staleOverwriteRejected() throws IOException {
            Path file = tempDir.resolve("Sync.txt");
            Files.writeString(file, "v1");

            readFileTool.execute(Map.of("path", "Sync.txt"), execContext);

            // 模拟外部偷偷修改
            Files.writeString(file, "v1-external-changed");

            String resultJson = writeFileTool.execute(Map.of(
                    "path", "Sync.txt",
                    "content", "v2"
            ), execContext);

            JSONObject json = JSON.parseObject(resultJson);
            assertTrue(json.containsKey("error"));
            assertTrue(json.getString("error").contains("已发生变化"));
            // 物理文件保持外部修改后的内容
            assertEquals("v1-external-changed", Files.readString(file));
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
    @DisplayName("EditFileTool 局部替换测试（无先读门禁，oldContent 唯一匹配自证）")
    class EditFileToolSecurityAndReplaceTests {

        @Test
        @DisplayName("未读先改放行：不要求先 read_file，oldContent 唯一命中即替换成功")
        void editWithoutReadSucceeds() throws IOException {
            Path file = tempDir.resolve("Guard.java");
            Files.writeString(file, "public class Guard { int v = 1; }");

            // 不调用 read_file，直接 edit——新设计下靠 oldContent 唯一匹配自证
            String resultJson = editFileTool.execute(Map.of(
                    "path", "Guard.java",
                    "oldContent", "int v = 1;",
                    "newContent", "int v = 2;"
            ), execContext);

            JSONObject json = JSON.parseObject(resultJson);
            assertTrue(json.getBooleanValue("success"));
            assertEquals("public class Guard { int v = 2; }", Files.readString(file));
        }

        @Test
        @DisplayName("正常链路：先 read_file 后 edit_file 局部替换成功")
        void readThenEditSuccess() throws IOException {
            Path file = tempDir.resolve("App.java");
            Files.writeString(file, "public class App {\n    String name = \"old\";\n}\n");

            // 1. read_file（软引导路径）
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
        @DisplayName("窗口外孪生提示：行号范围命中但文件内另有相同内容时，结果附注孪生行号")
        void twinTipAppendedOnRangeEdit() throws IOException {
            // 第 1 行与第 5 行各有一处相同内容，模型用行号范围框住第 5 行
            Path file = tempDir.resolve("Twin.java");
            Files.writeString(file, "validate(input);\nint a = 1;\nint b = 2;\nint c = 3;\nvalidate(input);\nint d = 4;\n");

            String resultJson = editFileTool.execute(Map.of(
                    "path", "Twin.java",
                    "oldContent", "validate(input);",
                    "newContent", "validate(input, strict);",
                    "startLine", 4,
                    "endLine", 6
            ), execContext);

            JSONObject json = JSON.parseObject(resultJson);
            assertTrue(json.getBooleanValue("success"));
            // message 中附注窗口外另一处孪生的行号
            assertTrue(json.getString("message").contains("另有 1 处相同内容位于第 1 行"));
            // 物理文件只改了第 5 行，第 1 行孪生未动
            String updated = Files.readString(file);
            assertTrue(updated.contains("validate(input, strict);"));
            long remaining = updated.lines().filter(l -> l.equals("validate(input);")).count();
            assertEquals(1, remaining);
        }

        @Test
        @DisplayName("全局编辑无孪生提示：不带行号范围时不附加孪生附注")
        void noTwinTipOnGlobalEdit() throws IOException {
            Path file = tempDir.resolve("Solo.java");
            Files.writeString(file, "int x = 1;\nint y = 2;\n");

            String resultJson = editFileTool.execute(Map.of(
                    "path", "Solo.java",
                    "oldContent", "int y = 2;",
                    "newContent", "int y = 20;"
            ), execContext);

            JSONObject json = JSON.parseObject(resultJson);
            assertTrue(json.getBooleanValue("success"));
            assertFalse(json.getString("message").contains("相同内容位于第"));
        }

        @Test
        @DisplayName("回显加帽：newContent 超过 4 行时 context 只回显头尾各 2 行与省略提示")
        void contextCappedForLargeNewContent() throws IOException {
            // 构造 10 行 oldContent → 替换为 10 行 newContent（触发 >4 行加帽）
            StringBuilder oldSb = new StringBuilder();
            for (int i = 1; i <= 10; i++) {
                oldSb.append("old line ").append(i).append('\n');
            }
            StringBuilder newSb = new StringBuilder();
            for (int i = 1; i <= 10; i++) {
                newSb.append("fresh line ").append(i).append('\n');
            }
            StringBuilder fileSb = new StringBuilder("before1\nbefore2\nbefore3\n");
            fileSb.append(oldSb);
            fileSb.append("after1\nafter2\nafter3\n");
            Path file = tempDir.resolve("Big.java");
            Files.writeString(file, fileSb.toString());

            String resultJson = editFileTool.execute(Map.of(
                    "path", "Big.java",
                    "oldContent", oldSb.toString(),
                    "newContent", newSb.toString()
            ), execContext);

            JSONObject json = JSON.parseObject(resultJson);
            assertTrue(json.getBooleanValue("success"));
            String context = json.getString("context");
            // 省略提示存在，中间 6 行不回显
            assertTrue(context.contains("省略 6 行"));
            assertFalse(context.contains("fresh line 3"));
            assertTrue(context.contains("fresh line 1"));
            assertTrue(context.contains("fresh line 10"));
        }
    }
}
