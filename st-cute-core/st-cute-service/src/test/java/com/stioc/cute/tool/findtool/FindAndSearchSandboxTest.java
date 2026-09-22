package com.stioc.cute.tool.findtool;

import com.stioc.cute.engine.common.JsonKit;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
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

    /**
     * 当前平台是否为 Windows。
     * <p>
     * 越界路径类用例必须按平台选取「真实绝对路径」：盘符形态（如 {@code Z:/x}）在 Linux 上
     * 会被 {@code Paths.get} 判为相对路径，用例会失去越界语义。
     * </p>
     */
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

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
            // 越界路径必须是「当前平台的绝对路径且落在沙箱白名单（项目根 / 临时目录 / 用户级配置目录）之外」。
            // 不可写死 "Z:/..." 这类 Windows 盘符路径：Linux 上 Paths.get 会判定为非绝对路径，
            // 被后续按相对路径解析，用例失去越界语义（曾因此在 CI 上假通过/假红）
            String outside = isWindows() ? "C:/Windows/System32/drivers/etc/hosts" : "/etc/hosts";
            assertTrue(Paths.get(outside).isAbsolute(), "越界用例必须选用当前平台的绝对路径");

            String verdict = SearchSandboxGuard.check(Paths.get(outside), agentContext, projectService, "rootDir");
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
            JSONArray files = JsonKit.parseArray(resultJson);
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
            JSONArray files = JsonKit.parseArray(resultJson);
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

            String result = grepSearchTool.execute(Map.of("pattern", "FOUND_ME"), execContext);
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
                    "pattern", "order-\\d{5}",
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
                    "pattern", "beta",
                    "rootDir", "Target.txt"
            ), execContext);

            assertNotNull(result);
            assertTrue(result.contains("已找到以下匹配行:"));
            assertTrue(result.contains("[Target.txt:2]"));
            assertFalse(result.contains("Other.txt"));
        }

        @Test
        @DisplayName("多行连续命中：Matcher 复用后行号与内容一一对应，不串行")
        void regexMatchAcrossManyLines() throws IOException {
            // 构造 300 行交替命中/不命中的文件，验证 Matcher 复用（reset）不会让命中状态残留
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= 300; i++) {
                sb.append(i % 2 == 0 ? "needle-" + i : "padding " + i).append('\n');
            }
            Files.writeString(tempDir.resolve("Many.txt"), sb.toString());

            String result = grepSearchTool.execute(Map.of(
                    "pattern", "needle-\\d+",
                    "useRegex", true,
                    "maxResults", 500
            ), execContext);

            assertNotNull(result);
            // 150 个偶数行应全部命中；逐条用「行号: 内容」组合校验，规避子串粘连误判
            // （如断言 [Many.txt:102] needle-102\n 时，[Many.txt:10] needle-102 之类不存在，
            //  但 needle-1020 若存在会误命中 contains——采用精确行号+完整内容双重校验）
            for (int i = 2; i <= 300; i += 2) {
                String expected = "[" + "Many.txt" + ":" + i + "] needle-" + i + "\n";
                assertTrue(result.contains(expected),
                        "第 " + i + " 行应命中且行内容正确。缺失时的实际上下文:\n"
                                + contextAround(result, "[Many.txt:" + i + "]"));
            }
            // 奇数行不得出现
            assertFalse(result.contains("[Many.txt:1] "), "奇数行不应命中");
            assertFalse(result.contains("[Many.txt:299] "), "奇数行不应命中");
        }

        /**
         * 截取 result 中指定标记附近的内容，用于断言失败时输出诊断信息
         */
        private String contextAround(String result, String marker) {
            int idx = result.indexOf(marker);
            if (idx < 0) {
                return "（标记未出现）结果前 600 字符:\n" + result.substring(0, Math.min(600, result.length()));
            }
            int start = Math.max(0, idx - 80);
            int end = Math.min(result.length(), idx + 120);
            return result.substring(start, end).replace("\n", "\\n");
        }

        @Test
        @DisplayName("文件头 1KB 纯 ASCII、主体 GBK 中文：8KB 样本可正确判定编码并命中中文")
        void gbkWithAsciiHeadDetectedVia8kProbe() throws IOException {
            // 编码探测的回退值随平台漂移：sun.jnu.encoding 在中文 Windows 上是 GBK，
            // 在 Linux CI（GitHub Actions ubuntu runner）上是 UTF-8——回退值若为 UTF-8，
            // GBK 字节会被解码成 U+FFFD 替换符，中文关键字永远匹配不到（曾因此 CI 假红）。
            // 本用例验证的就是「UTF-8 严格校验失败 → 回退系统原生编码」路径，
            // 须显式钉住回退编码为 GBK，保证在任意平台确定性走 GBK 回退分支
            String originalJnuEncoding = System.getProperty("sun.jnu.encoding");
            System.setProperty("sun.jnu.encoding", "GBK");
            try {
                // 用 GBK 编码写入：头部约 1.5KB 纯 ASCII 注释，主体为 GBK 中文（含目标关键字）
                StringBuilder sb = new StringBuilder();
                sb.append("// ASCII padding comment. ".repeat(60));   // 约 1.68KB 纯 ASCII
                sb.append("\n");
                for (int i = 1; i <= 5; i++) {
                    sb.append("日志内容：检索关键字标记").append(i).append('\n');
                }
                Path gbkFile = tempDir.resolve("Gbk.log");
                Files.writeString(gbkFile, sb.toString(), java.nio.charset.Charset.forName("GBK"));

                String result = grepSearchTool.execute(Map.of("pattern", "检索关键字"), execContext);

                assertNotNull(result);
                assertTrue(result.contains("已找到以下匹配行:"), "GBK 中文关键字应被命中。实际返回:\n" + result);
                assertTrue(result.contains("Gbk.log"), "应命中 GBK 文件");
            } finally {
                // 恢复原始值：surefire 默认同 JVM 复用跑全部用例，不恢复会污染其他用例的编码探测行为
                if (originalJnuEncoding == null) {
                    System.clearProperty("sun.jnu.encoding");
                } else {
                    System.setProperty("sun.jnu.encoding", originalJnuEncoding);
                }
            }
        }

        @Test
        @DisplayName("二进制文件跳过：文件头 8KB 内含零字节时不参与检索")
        void binaryFileSkippedWithLargerProbe() throws IOException {
            // 构造前 200 字节 ASCII + 第 300 字节为 0x00 的文件（零字节落在 8KB 探测窗内）
            byte[] data = new byte[1024];
            byte[] ascii = "binary file padding text without null bytes here\n".getBytes();
            System.arraycopy(ascii, 0, data, 0, ascii.length);
            data[300] = 0;
            Files.write(tempDir.resolve("binary.bin"), data);

            String result = grepSearchTool.execute(Map.of("pattern", "padding"), execContext);

            assertFalse(result.contains("binary.bin"), "含零字节的二进制文件应被跳过。实际返回:\n" + result);
            // 空结果时应附跳过统计，帮助模型区分「无匹配」与「文件被防御性跳过」
            JSONObject json = JsonKit.parseObject(result);
            assertTrue(json.containsKey("skipped"), "存在被跳过文件时空结果应附 skipped 统计。实际返回:\n" + result);
            assertTrue(json.getString("skipped").contains("二进制文件 1 个"),
                    "skipped 统计应准确描述被跳过的二进制文件数。实际返回:\n" + result);
        }

        @Test
        @DisplayName("超大文件跳过计入统计：10MB+ 文件不参与检索并在空结果中反馈")
        void oversizedFileCountedInSkipStats() throws IOException {
            // 构造 10MB+1 字节的纯 ASCII 文件（不含零字节，确保按「超大文件」而非「二进制」路径跳过）
            byte[] data = new byte[10 * 1024 * 1024 + 1];
            java.util.Arrays.fill(data, (byte) 'a');
            Files.write(tempDir.resolve("big.log"), data);

            String result = grepSearchTool.execute(Map.of("pattern", "not-exist-keyword"), execContext);

            JSONObject json = JsonKit.parseObject(result);
            assertTrue(json.containsKey("skipped"), "超大文件被跳过时空结果应附 skipped 统计。实际返回:\n" + result);
            assertTrue(json.getString("skipped").contains("超大文件(>10MB) 1 个"),
                    "skipped 统计应准确描述被跳过的超大文件数。实际返回:\n" + result);
            assertFalse(result.contains("big.log"), "超大文件内容不应参与匹配。实际返回:\n" + result);
        }
    }
}
