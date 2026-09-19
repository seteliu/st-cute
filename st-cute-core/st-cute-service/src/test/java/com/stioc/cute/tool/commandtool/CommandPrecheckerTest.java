package com.stioc.cute.tool.commandtool;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.runtime.loop.RuntimeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令行执行预检与熔断防线单元测试。
 * 覆盖 PowerShell 写文件硬拦截、重复命令熔断保险丝、构建类命令识别与超时计算。
 */
class CommandPrecheckerTest {

    @Nested
    @DisplayName("checkPowerShellWrite 写文件硬拦截测试")
    class PowerShellWriteTests {

        @ParameterizedTest(name = "危险写入命令被拦截: {0}")
        @ValueSource(strings = {
                "powershell -Command \"Set-Content -Path test.txt -Value 'hello'\"",
                "powershell.exe Out-File output.log",
                "pwsh -c \"Add-Content log.txt 'append'\"",
                "powershell set-content foo.java 'bar'",
                "pwsh  -Command \"Get-Process | Out-File -FilePath proc.txt\""
        })
        @DisplayName("命中 PowerShell/pwsh 写文件 cmdlet 时拦截并返回拒绝理由")
        void blockPowerShellWriteCmdlets(String command) {
            boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
            String result = CommandPrechecker.checkPowerShellWrite(command);
            if (isWin) {
                assertNotNull(result, "Windows 下应拦截 PowerShell 写文件命令");
                assertTrue(result.contains("拒绝执行"));
                assertTrue(result.contains("PowerShell 写入文件"));
            } else {
                assertNull(result, "非 Windows 平台不触发该拦截");
            }
        }

        @ParameterizedTest(name = "安全命令放行: {0}")
        @ValueSource(strings = {
                "mvn clean test",
                "git status",
                "powershell Get-Process",
                "pwsh -Command \"Get-ChildItem\"",
                "echo hello > file.txt"
        })
        @DisplayName("普通命令或非写入类 PowerShell 命令放行")
        void allowSafeCommands(String command) {
            assertNull(CommandPrechecker.checkPowerShellWrite(command));
        }
    }

    @Nested
    @DisplayName("isBuildCommand 构建类命令识别测试")
    class BuildCommandRecognitionTests {

        @ParameterizedTest(name = "识别为构建类命令: {0}")
        @ValueSource(strings = {
                "mvn compile",
                "mvn.cmd clean install",
                "./mvnw test",
                ".\\gradlew build",
                "npm run dev",
                "pnpm.cmd install",
                "yarn build",
                "cargo.exe check",
                "docker compose up",
                "dotnet run",
                "make -j4",
                "cmake --build ."
        })
        @DisplayName("常见构建类命令命中白名单")
        void recognizeBuildCommands(String command) {
            assertTrue(CommandPrechecker.isBuildCommand(command));
        }

        @ParameterizedTest(name = "识别为普通非构建类命令: {0}")
        @ValueSource(strings = {
                "git log",
                "ls -la",
                "dir",
                "python script.py",
                "node index.js",
                "echo hello",
                "cat file.txt"
        })
        @DisplayName("非构建类命令返回 false")
        void recognizeNonBuildCommands(String command) {
            assertFalse(CommandPrechecker.isBuildCommand(command));
        }
    }

    @Nested
    @DisplayName("resolveIdleTimeout 空闲超时计算测试")
    class IdleTimeoutTests {

        @Test
        @DisplayName("显式传入超时参数时优先采用显式值")
        void explicitTimeoutTakesPrecedence() {
            long explicit = 120_000L;
            long timeout = CommandPrechecker.resolveIdleTimeout("mvn test", explicit);
            assertEquals(explicit, timeout);
        }

        @Test
        @DisplayName("构建类命令未显式指定超时，放宽至 90 秒")
        void buildCommandUsesExtendedTimeout() {
            long timeout = CommandPrechecker.resolveIdleTimeout("pnpm test", null);
            assertEquals(CommandPrechecker.BUILD_IDLE_TIMEOUT_MS, timeout);
            assertEquals(90_000L, timeout);
        }

        @Test
        @DisplayName("普通命令未显式指定超时，采用默认基线 30 秒")
        void normalCommandUsesDefaultTimeout() {
            long timeout = CommandPrechecker.resolveIdleTimeout("git status", null);
            assertEquals(CommandPrechecker.DEFAULT_IDLE_TIMEOUT_MS, timeout);
            assertEquals(30_000L, timeout);
        }
    }

    @Nested
    @DisplayName("checkRepeatBreak & recordCommandOutput 重复执行熔断保险丝测试")
    class RepeatBreakTests {

        private AgentContext createAgentContextWithRuntime() {
            AgentContext context = new AgentContext(1001L, null, null);
            RuntimeContext runtimeContext = new RuntimeContext(1001L);
            context.putExtraContext(runtimeContext);
            return context;
        }

        @Test
        @DisplayName("上下文为空或缺少 RuntimeContext 时安全放行")
        void nullContextReturnsNull() {
            assertNull(CommandPrechecker.checkRepeatBreak(null, "git status@/project"));

            AgentContext emptyContext = new AgentContext(1002L, null, null);
            assertNull(CommandPrechecker.checkRepeatBreak(emptyContext, "git status@/project"));
        }

        @Test
        @DisplayName("连续执行同一命令且输出相同达到 10 次时触发熔断拒绝，第 9 次依然放行")
        void breakAfterTenConsecutiveIdenticalOutputs() {
            AgentContext context = createAgentContextWithRuntime();
            String fp = "mvn test@/workspace";
            String fixedOutput = "BUILD FAILURE: compilation error in Foo.java";

            // 模拟执行并记录 9 次相同输出
            for (int i = 1; i <= 9; i++) {
                assertNull(CommandPrechecker.checkRepeatBreak(context, fp), "第 " + i + " 次预检应放行");
                CommandPrechecker.recordCommandOutput(context, fp, fixedOutput);
            }

            // 第 10 次预检：已经累计了 9 次相同输出，依然放行
            assertNull(CommandPrechecker.checkRepeatBreak(context, fp));
            // 记录第 10 次
            CommandPrechecker.recordCommandOutput(context, fp, fixedOutput);

            // 再次预检（此时相同输出已达到 10 次），触发熔断拒绝
            String rejection = CommandPrechecker.checkRepeatBreak(context, fp);
            assertNotNull(rejection, "连续 10 次相同输出后应熔断拒绝");
            assertTrue(rejection.contains("拒绝执行"));
            assertTrue(rejection.contains("连续 10 次产生完全相同的输出"));
        }

        @Test
        @DisplayName("输出发生变化时重置相同输出计数，避免误杀正常迭代重试")
        void outputChangeResetsCounter() {
            AgentContext context = createAgentContextWithRuntime();
            String fp = "cargo test@/workspace";

            // 连续记录 8 次输出 A
            for (int i = 0; i < 8; i++) {
                CommandPrechecker.recordCommandOutput(context, fp, "Error A");
            }

            // 第 9 次输出变为 Error B
            CommandPrechecker.recordCommandOutput(context, fp, "Error B");

            // 再来 2 次 Error B（共 3 次 Error B），不应触发熔断
            CommandPrechecker.recordCommandOutput(context, fp, "Error B");
            CommandPrechecker.recordCommandOutput(context, fp, "Error B");

            assertNull(CommandPrechecker.checkRepeatBreak(context, fp), "输出改变后计数已重置，不应熔断");
        }

        @Test
        @DisplayName("不同命令指纹各自拥有独立追踪器，互不干扰")
        void differentFingerprintsAreIsolated() {
            AgentContext context = createAgentContextWithRuntime();
            String fp1 = "git status@/dir1";
            String fp2 = "git status@/dir2";

            // fp1 累计 10 次
            for (int i = 0; i < 10; i++) {
                CommandPrechecker.recordCommandOutput(context, fp1, "nothing to commit");
            }

            // fp1 触发熔断，fp2 依然放行
            assertNotNull(CommandPrechecker.checkRepeatBreak(context, fp1));
            assertNull(CommandPrechecker.checkRepeatBreak(context, fp2));
        }
    }
}
