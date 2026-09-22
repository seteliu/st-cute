package com.stioc.cute.tool.filetool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件局部代码替换匹配定位器纯单元测试。
 * 覆盖三阶段递进匹配算法、颠倒防御、行号偏移量统计与上下文截取。
 */
class FileEditMatcherTest {

    @Nested
    @DisplayName("locateMatch 三阶段递进匹配与防御测试")
    class LocateMatchTests {

        @Test
        @DisplayName("阶段1：精确匹配命中唯一位置，返回成功区间")
        void exactMatchSuccess() {
            String content = "public void hello() {\n    System.out.println(\"hello\");\n}\n";
            String oldContent = "System.out.println(\"hello\");";
            MatchLocateResult result = FileEditMatcher.locateMatch(
                    content, oldContent, oldContent, oldContent,
                    "System.out.println(\"world\");", content, "文件 [Test.java]", false
            );

            assertTrue(result.isSuccess());
            int expectedStart = content.indexOf(oldContent);
            assertEquals(expectedStart, result.startOffset());
            assertEquals(expectedStart + oldContent.length(), result.endOffset());
        }

        @Test
        @DisplayName("阶段1：精确匹配出现多处，拦截并提示提供更多上下文")
        void exactMatchMultipleOccurrences() {
            String content = "int a = 1;\nint a = 1;\n";
            String oldContent = "int a = 1;";
            MatchLocateResult result = FileEditMatcher.locateMatch(
                    content, oldContent, oldContent, oldContent,
                    "int a = 2;", content, "文件 [Test.java]", false
            );

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().contains("找到了多处 (2 处) 'oldContent' 的精确匹配"));
        }

        @Test
        @DisplayName("阶段2：EOL 变体精确重试命中成功")
        void eolVariantMatchSuccess() {
            // 文件内容为 CRLF 换行
            String content = "line1\r\nline2\r\nline3\r\n";
            // normalizedOld 为 LF 换行（未命中），altVariantOld 为 CRLF 换行
            String normalizedOld = "line1\nline2";
            String altVariantOld = "line1\r\nline2";

            MatchLocateResult result = FileEditMatcher.locateMatch(
                    content, normalizedOld, normalizedOld, altVariantOld,
                    "newLines", content, "文件 [Test.java]", false
            );

            assertTrue(result.isSuccess());
            int expectedStart = content.indexOf(altVariantOld);
            assertEquals(expectedStart, result.startOffset());
            assertEquals(expectedStart + altVariantOld.length(), result.endOffset());
        }

        @Test
        @DisplayName("阶段2：EOL 变体匹配存在多处，拦截并提示变体多处")
        void eolVariantMatchMultipleOccurrences() {
            String content = "dup\r\ndup\r\n";
            String normalizedOld = "dup\n";
            String altVariantOld = "dup\r\n";

            MatchLocateResult result = FileEditMatcher.locateMatch(
                    content, normalizedOld, normalizedOld, altVariantOld,
                    "new", content, "文件 [Test.java]", false
            );

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().contains("EOL 换行变体"));
        }

        @Test
        @DisplayName("阶段3：空白不敏感模糊匹配，容忍水平缩进差异")
        void whitespaceInsensitiveMatchSuccess() {
            // 文件实际为 4 空格缩进
            String content = "class App {\n    int value = 42;\n}\n";
            // 待替换文本为 2 空格缩进
            String oldContent = "class App {\n  int value = 42;\n}";

            MatchLocateResult result = FileEditMatcher.locateMatch(
                    content, oldContent, oldContent, oldContent,
                    "class App {\n    int value = 100;\n}", content, "文件 [Test.java]", false
            );

            assertTrue(result.isSuccess());
            assertTrue(result.startOffset() >= 0);
            assertTrue(result.endOffset() > result.startOffset());
        }

        @Test
        @DisplayName("阶段3：空白不敏感匹配存在多处，拦截并提示缩窄范围")
        void whitespaceInsensitiveMultipleMatches() {
            // 原文只有单空格
            String content = "int a = 1; int b = 2;\nint a = 1; int b = 2;\n";
            // 期望替换内容使用双空格（精确匹配和 EOL 变体均找不到，只有空白不敏感正则命中）
            String oldContent = "int a = 1;  int b = 2;";

            MatchLocateResult result = FileEditMatcher.locateMatch(
                    content, oldContent, oldContent, oldContent,
                    "int x = 2;", content, "文件 [Test.java]", false
            );

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().contains("空白不敏感匹配"));
        }

        @Test
        @DisplayName("回归：oldContent 以换行结尾时，不得吞掉目标文本下一行的前导缩进")
        void trailingNewlineMustNotSwallowNextLineIndent() {
            // 阶段一/二均不命中（缩进形态为 tab，与 oldContent 的空格不同），走阶段三空白不敏感匹配。
            // 该场景曾因末尾换行后无条件追加 [ \t]* 而把下一行缩进并入匹配区间，
            // 替换后下一行缩进被静默删除（Python/YAML 等缩进敏感文件语义被改变）
            String content = "\tfoo();\n    bar();\n";
            String oldContent = "foo();\n";

            MatchLocateResult result = FileEditMatcher.locateMatch(
                    content, oldContent, oldContent, oldContent,
                    "baz();\n", content, "文件 [app.py]", false
            );

            assertTrue(result.isSuccess(), "应命中唯一位置");
            String replaced = content.substring(0, result.startOffset())
                    + "baz();\n"
                    + content.substring(result.endOffset());
            assertTrue(replaced.contains("    bar();"),
                    "下一行的前导缩进必须完整保留，实际结果: " + replaced.replace("\n", "\\n"));
        }

        @Test
        @DisplayName("回归：阶段三匹配命中区间止于末尾换行，不含下一行缩进")
        void whitespaceInsensitiveRangeStopsAtTrailingNewline() {
            // 文件首行无缩进，oldContent 为 tab 缩进且以换行结尾：
            // 阶段一/二均不命中，必然走阶段三正则路径
            String content = "foo();\n  bar();\n";
            String oldContent = "\tfoo();\n";

            MatchLocateResult result = FileEditMatcher.locateMatch(
                    content, oldContent, oldContent, oldContent,
                    "baz();\n", content, "文件 [app.py]", false
            );

            assertTrue(result.isSuccess());
            // 命中区间必须恰为 "foo();\n"，不得延伸吞掉 "  bar();" 的缩进
            assertEquals("foo();\n", content.substring(result.startOffset(), result.endOffset()));
        }

        @Test
        @DisplayName("阶段4：防颠倒拦截，当 oldContent 未命中但 newContent 存在于文件时给出精准指引")
        void invertedArgsDefense() {
            String content = "final int MAX_COUNT = 100;\n";
            String oldContent = "final int MAX_COUNT = 50;";
            String newContent = "final int MAX_COUNT = 100;";

            MatchLocateResult result = FileEditMatcher.locateMatch(
                    content, oldContent, oldContent, oldContent,
                    newContent, content, "文件 [App.java]", false
            );

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().contains("疑似参数顺序写反"));
        }

        @Test
        @DisplayName("阶段4：行号范围内未找到匹配，报错附带实际切片与期望内容")
        void notFoundInRangeEchoesActualContent() {
            String rangeContent = "int a = 10;\nint b = 20;\n";
            String oldContent = "int c = 30;";

            MatchLocateResult result = FileEditMatcher.locateMatch(
                    rangeContent, oldContent, oldContent, oldContent,
                    "int c = 40;", "whole file", "指定的行号范围 [1, 2]", true
            );

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().contains("指定的行号范围 [1, 2]"));
            assertTrue(result.errorMessage().contains(rangeContent));
            assertTrue(result.errorMessage().contains(oldContent));
        }

        @Test
        @DisplayName("阶段4：全文件均未命中匹配，报错给出检查指引")
        void notFoundInWholeFile() {
            String content = "hello world\n";
            String oldContent = "foo bar";

            MatchLocateResult result = FileEditMatcher.locateMatch(
                    content, oldContent, oldContent, oldContent,
                    "replacement", content, "文件 [App.java]", false
            );

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().contains("未找到要替换的 'oldContent' 匹配片段"));
        }
    }

    @Nested
    @DisplayName("行号范围与字符偏移量工具测试")
    class LineRangeAndOffsetsTests {

        @Test
        @DisplayName("getLineRangeOffsets 跨多行偏移量计算与换行符包含")
        void testGetLineRangeOffsets() {
            String text = "Line 1\nLine 2\nLine 3\nLine 4\n";
            // 提取 [2, 3] 行，即 "Line 2\nLine 3\n"
            LineRangeOffsets offsets = FileEditMatcher.getLineRangeOffsets(text, 2, 3);
            assertNotNull(offsets);

            String sub = text.substring(offsets.startOffset(), offsets.endOffset());
            assertEquals("Line 2\nLine 3\n", sub);
        }

        @Test
        @DisplayName("getLineRangeOffsets 支持 CRLF 换行文件")
        void testGetLineRangeOffsetsCrlf() {
            String text = "Line 1\r\nLine 2\r\nLine 3\r\n";
            LineRangeOffsets offsets = FileEditMatcher.getLineRangeOffsets(text, 2, 2);
            assertNotNull(offsets);

            String sub = text.substring(offsets.startOffset(), offsets.endOffset());
            assertEquals("Line 2\r\n", sub);
        }

        @ParameterizedTest(name = "文本为 \"{0}\" 时总行数为 {1}")
        @CsvSource(value = {
                "'', 0",
                "'Single line', 1",
                "'Line 1\nLine 2', 2",
                "'Line 1\nLine 2\n', 2",
                "'Line 1\r\nLine 2\r\nLine 3', 3",
                "'A\rB\nC\r\n', 3"
        })
        @DisplayName("countLines 各种换行形态下的行数统计")
        void testCountLines(String input, int expectedLines) {
            assertEquals(expectedLines, FileEditMatcher.countLines(input));
        }

        @Test
        @DisplayName("offsetToLineNumber 字符偏移量精准映射到 1-indexed 行号")
        void testOffsetToLineNumber() {
            String text = "First\nSecond\r\nThird\n";
            // 'F' 偏移 0 -> 第 1 行
            assertEquals(1, FileEditMatcher.offsetToLineNumber(text, 0));
            // 'S' 偏移 6 -> 第 2 行
            assertEquals(2, FileEditMatcher.offsetToLineNumber(text, 6));
            // 'T' 偏移 14 -> 第 3 行
            assertEquals(3, FileEditMatcher.offsetToLineNumber(text, 14));
            // 超出长度或末尾，收敛至总行数上限
            assertEquals(3, FileEditMatcher.offsetToLineNumber(text, 100));
        }

        @Test
        @DisplayName("getContextSnippet 截取修改位置前后各指定行数的上下文")
        void testGetContextSnippet() {
            String content = "line 1\nline 2\nline 3\nline 4\nline 5\n";
            int startPos = content.indexOf("line 3");
            int endPos = startPos + "line 3".length();

            // 传入 2：向前向后各跨越 2 道换行符边界，截取包含 line 2, line 3, line 4
            String snippet = FileEditMatcher.getContextSnippet(content, startPos, endPos, 2);
            assertTrue(snippet.contains("line 2\n"));
            assertTrue(snippet.contains("line 3"));
            assertTrue(snippet.contains("line 4\n"));
            assertFalse(snippet.contains("line 1"));
            assertFalse(snippet.contains("line 5"));
        }
    }
}
