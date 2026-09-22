package com.stioc.cute.tool.commandtool;

import com.stioc.cute.git.GitBashLocator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Windows 命令行预转义（{@link WindowsCommandLineEscaper}）与 {@link ProcessLauncher} 集成测试。
 * <p>
 * 背景：JDK 在 Windows 上默认走 LEGACY 模式拼接子进程命令行，不转义参数内部的双引号，
 * 导致含空白与引号的命令被 CommandLineToArgvW 裂解成多个参数，bash 只执行首段，
 * 其余静默降级为 $0/$1/$2（如 {@code echo "a b c d"} 只输出 {@code a}，退出码仍为 0）。
 * </p>
 * <p>
 * 本测试分两层：
 * <ul>
 *   <li>转义规则层：直接断言 {@link WindowsCommandLineEscaper#escapeForShell} 的输出，跨平台可跑；</li>
 *   <li>端到端层：经 {@link ProcessLauncher#prepareProcess} 真实拉起 Git Bash 执行命令，
 *       断言最终输出与「命令落盘为脚本执行」的语义一致（该层仅在 Windows 且探测到 Git Bash 时执行）。</li>
 * </ul>
 * </p>
 */
class WindowsCommandLineEscaperTest {

    @TempDir
    Path tempDir;

    /** Git Bash 探测器（生产同款实现，JVM 内自带探测缓存） */
    private final GitBashLocator gitBashLocator = new GitBashLocator();

    /** 当前平台是否为 Windows */
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    @Nested
    @DisplayName("escapeForShell 转义规则")
    class EscapeRuleTests {

        @Test
        @DisplayName("Windows 下为双引号补转义反斜杠；非 Windows 平台原样返回不污染命令")
        void escapeByPlatform() {
            String command = "echo \"a b c d\"";
            String escaped = WindowsCommandLineEscaper.escapeForShell(command);
            if (isWindows()) {
                assertEquals("echo \\\"a b c d\\\"", escaped,
                        "每个双引号前应补一个反斜杠，使解析后引号得以保留");
            } else {
                assertEquals(command, escaped,
                        "非 Windows 不经过 CommandLineToArgvW，转义反而会让命令出现多余的反斜杠");
            }
        }

        @Test
        @DisplayName("引号前已有反斜杠时按 2K+1 规则扩张（K 为引号前连续反斜杠数）")
        void escapeBackslashesBeforeQuote() {
            Assumptions.assumeTrue(isWindows(), "该规则仅在 Windows 生效");
            // 命令文本为 echo "a\"b"（中间的 \" 是「反斜杠 + 引号」两个字符）
            String command = "echo \"a\\\"b\"";
            // 首个引号前 0 个反斜杠 → 补 1 个；中间引号前 1 个 → 补 3 个；末尾引号前 0 个 → 补 1 个
            assertEquals("echo \\\"a\\\\\\\"b\\\"", WindowsCommandLineEscaper.escapeForShell(command));
        }

        @Test
        @DisplayName("非引号前的反斜杠与参数尾部反斜杠保持原样（尾部由 JDK 自动加倍、解析折半还原）")
        void keepPlainBackslashes() {
            Assumptions.assumeTrue(isWindows(), "该规则仅在 Windows 生效");
            // 非引号前的反斜杠无转义含义，不得改动（否则会破坏 \d 这类正则写法）
            assertEquals("grep -P \\d+ .", WindowsCommandLineEscaper.escapeForShell("grep -P \\d+ ."));
            // 尾部反斜杠若再自行加倍，会与 JDK 的自动加倍叠加成错误结果
            assertEquals("echo abc\\", WindowsCommandLineEscaper.escapeForShell("echo abc\\"));
        }

        @Test
        @DisplayName("不含双引号的命令与空命令不受影响")
        void noQuoteCommandUnchanged() {
            Assumptions.assumeTrue(isWindows(), "该规则仅在 Windows 生效");
            String plain = "mvn -f st-cute-core/pom.xml compile -DskipTests";
            assertEquals(plain, WindowsCommandLineEscaper.escapeForShell(plain));
            assertEquals("", WindowsCommandLineEscaper.escapeForShell(""));
        }
    }

    @Nested
    @DisplayName("ProcessLauncher 集成：命令经 Git Bash 真实执行")
    class LauncherIntegrationTests {

        @BeforeEach
        void requireWindowsWithGitBash() {
            Assumptions.assumeTrue(isWindows(), "该缺陷仅在 Windows 出现，非 Windows 无需本集成测试");
            Assumptions.assumeTrue(gitBashLocator.detectPath() != null, "本机未探测到 Git Bash，跳过集成测试");
        }

        /**
         * 经生产路径拉起命令并返回标准输出。
         * 走 {@link ProcessLauncher#prepareProcess} 而非自行 new ProcessBuilder，
         * 以确保测试覆盖真实的转义注入点。
         */
        private String exec(String command) throws Exception {
            ProcessLaunchSpec spec = ProcessLauncher.prepareProcess(
                    command, "bash", tempDir.toFile(), gitBashLocator);
            Process process = spec.builder().start();
            // 关闭子进程标准输入：避免命令若意外读取 stdin 时挂起
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.close();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return output;
        }

        @Test
        @DisplayName("双引号内含空格的参数不再被裂解（原缺陷核心场景）")
        void quotedArgumentWithSpaceSurvives() throws Exception {
            assertEquals("a b c d\n", exec("echo \"a b c d\""));
            assertEquals("中文 带空格\n", exec("echo \"中文 带空格\""));
        }

        @Test
        @DisplayName("多个含空格参数并排时全部完整传递")
        void multipleQuotedArgumentsSurvive() throws Exception {
            assertEquals("[one two][three four]\n",
                    exec("printf '[%s]' \"one two\" \"three four\"; echo"));
        }

        @Test
        @DisplayName("引号内含方括号的 git 提交消息形态完整传递")
        void gitCommitMessageFormSurvives() throws Exception {
            // 用的是 printf 而非真实 git 提交：只验证参数完整性，不产生仓库副作用
            assertEquals("[fix(web): 修复登录问题]",
                    exec("printf '[%s]' \"fix(web): 修复登录问题\""));
        }

        @Test
        @DisplayName("for 循环等含引号的多语句结构恢复正常执行")
        void loopStructureWorks() throws Exception {
            assertEquals("第 1 行\n第 2 行\n第 3 行\n",
                    exec("for i in 1 2 3; do echo \"第 $i 行\"; done"));
        }

        @Test
        @DisplayName("单引号内的双引号原样保留，转义引号亦正确还原")
        void quoteNestingWorks() throws Exception {
            assertEquals("say \"hi\"\n", exec("echo 'say \"hi\"'"));
            assertEquals("a\"b\n", exec("echo \"a\\\"b\""));
        }

        @Test
        @DisplayName("原本正常的命令无回归：引号无空格、纯命令、无引号多参数")
        void noRegressionOnPreviouslyWorkingCommands() throws Exception {
            assertEquals("ab\n", exec("echo \"ab\""));
            assertEquals("mvn -f st-cute-core/pom.xml compile -DskipTests\n",
                    exec("echo mvn -f st-cute-core/pom.xml compile -DskipTests"));
            assertEquals("path\\to\\file\n", exec("echo \"path\\\\to\\\\file\""));
        }
    }
}
