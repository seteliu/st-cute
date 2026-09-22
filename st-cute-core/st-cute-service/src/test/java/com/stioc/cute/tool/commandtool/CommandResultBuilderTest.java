package com.stioc.cute.tool.commandtool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令输出压缩器单元测试。
 * 覆盖行数闸门、字符闸门、两闸门叠加与边界口径（末尾换行符计数）。
 */
class CommandResultBuilderTest {

    /**
     * 构造指定行数的输出（每行以换行符结尾）
     */
    private String lines(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append("log line ").append(i).append('\n');
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("字符闸门")
    class CharGateTests {

        @Test
        @DisplayName("null 与未超限输出原样返回")
        void passthrough() {
            assertNull(CommandResultBuilder.compactOutputIfNeeded(null));
            String small = "hello world\n";
            assertEquals(small, CommandResultBuilder.compactOutputIfNeeded(small));
        }

        @Test
        @DisplayName("超过字符上限时压缩为首尾保留并给出原始规模")
        void compactByChars() {
            // 行数不超限（9000 < 10000）但字符超限（每行 20 字符 + 换行，约 18.9 万字符 > 10 万），
            // 隔离验证字符闸门的独立触发路径——这正是字符闸门存在的核心场景（行少但每行长）
            StringBuilder sb = new StringBuilder();
            IntStream.range(0, 9000).forEach(i -> sb.append("a".repeat(20)).append('\n'));
            String big = sb.toString();

            String result = CommandResultBuilder.compactOutputIfNeeded(big);

            assertTrue(result.length() < big.length(), "压缩后应显著变短");
            // 行数未超限不应触发行数闸门，正文首尾原样保留，仅中段替换为摘要
            assertTrue(result.startsWith("aaaa"), "应保留正文开头");
            assertTrue(result.endsWith("aaaa\n"), "应保留正文结尾");
            assertFalse(result.contains("输出行数过多已压缩"), "行数未超限不应触发行数闸门");
            assertTrue(result.contains("原输出共 9000 行"), "摘要应包含原始总行数");
            assertTrue(result.contains("如需完整内容可重定向到文件后用 read_file 分段读取"), "应给出可行动指引");
        }
    }

    @Nested
    @DisplayName("行数闸门")
    class LineGateTests {

        @Test
        @DisplayName("未超行数上限时原样返回")
        void underLineLimit() {
            String output = lines(100);
            assertEquals(output, CommandResultBuilder.compactOutputIfNeeded(output));
        }

        @Test
        @DisplayName("超过行数上限：保留头尾各 5000 行，总行数统计不含末尾换行符多算")
        void compactByLines() {
            // 11000 行、每行 "x\n" 共约 2.2 万字符，不触发字符闸门，可隔离验证行数闸门
            StringBuilder sb = new StringBuilder();
            IntStream.range(0, 11000).forEach(i -> sb.append("x\n"));
            String output = sb.toString();

            String result = CommandResultBuilder.compactOutputIfNeeded(output);

            // 总行数必须精确为 11000（此前 off-by-one 会报 11001）
            assertTrue(result.contains("共 11000 行"), "总行数应为 11000。实际:\n" + snippet(result));
            // 11000 - 5000 - 5000 = 1000
            assertTrue(result.contains("中间省略 1000 行"), "省略行数应为 1000。实际:\n" + snippet(result));
            assertTrue(result.contains("输出行数过多已压缩"), "应标识为行数压缩");
        }

        @Test
        @DisplayName("末尾无换行符时最后一行仍计入总行数")
        void trailingNewlineCounting() {
            // 末尾无换行：应算作 11000 行而非 10999
            StringBuilder sb = new StringBuilder();
            IntStream.range(0, 10999).forEach(i -> sb.append("x\n"));
            sb.append("x");   // 最后一行无换行符
            String output = sb.toString();

            String result = CommandResultBuilder.compactOutputIfNeeded(output);

            assertTrue(result.contains("共 11000 行"),
                    "末尾无换行符时最后一行应计入，共 11000 行。实际:\n" + snippet(result));
        }

        private String snippet(String text) {
            int mid = text.length() / 2;
            return text.substring(Math.max(0, mid - 150), Math.min(text.length(), mid + 150));
        }
    }

    @Nested
    @DisplayName("双闸门叠加")
    class CombinedGateTests {

        @Test
        @DisplayName("行数与字符双超限时，摘要仍保留原始行数与字符数信息")
        void bothGatesTriggered() {
            // 25000 行、每行 "log line N"：约 24.7 万字符，两闸门均触发
            String output = lines(25000);

            String result = CommandResultBuilder.compactOutputIfNeeded(output);

            // 关键：行数信息不得被字符闸门的截断吃掉
            assertTrue(result.contains("原输出共 25000 行"), "应保留原始总行数。实际:\n" + snippet(result));
            assertTrue(result.contains("字符"), "应保留字符规模信息");
            // 首尾内容保留
            assertTrue(result.startsWith("log line 1\n"), "应保留开头");
            assertTrue(result.endsWith("log line 25000\n"), "应保留结尾");
        }

        private String snippet(String text) {
            int mid = text.length() / 2;
            return text.substring(Math.max(0, mid - 200), Math.min(text.length(), mid + 200));
        }
    }

    @Nested
    @DisplayName("结果封装")
    class BuildResultTests {

        @Test
        @DisplayName("五字段契约与超时细分标记")
        void buildResult() {
            String normal = CommandResultBuilder.buildCommandResult(0, false, "/tmp", "out");
            assertTrue(normal.contains("\"exitCode\":0"));
            assertTrue(normal.contains("\"timeout\":false"));
            assertFalse(normal.contains("idleTimeout"), "非超时不应携带 idleTimeout 字段");

            String timedOut = CommandResultBuilder.buildCommandResult(-1, true, "/tmp", "out", true);
            assertTrue(timedOut.contains("\"idleTimeout\":true"), "空闲超时应携带细分标记");
        }
    }
}
