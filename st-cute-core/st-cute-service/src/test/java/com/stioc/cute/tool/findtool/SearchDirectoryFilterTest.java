package com.stioc.cute.tool.findtool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.FileVisitResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索目录过滤器单元测试。
 * 覆盖永久排除目录（.git 等）、常规产物依赖缓存目录（target 等）、
 * 显式点名目录放行与合法用户点开头资产目录放行策略。
 */
class SearchDirectoryFilterTest {

    @Nested
    @DisplayName("isAlwaysExcluded 永久排除目录测试")
    class AlwaysExcludedTests {

        @ParameterizedTest(name = "永久排除目录: {0}")
        @ValueSource(strings = {".git", ".svn", ".hg", ".GIT", ".Git"})
        @DisplayName("版本控制内部目录判定为永久排除，大小写不敏感")
        void alwaysExcludedDirectories(String dirName) {
            assertTrue(SearchDirectoryFilter.isAlwaysExcluded(dirName));
        }

        @ParameterizedTest(name = "非永久排除目录: {0}")
        @ValueSource(strings = {"target", "node_modules", ".idea", "src", ".ai-work", ".agents", ""})
        @DisplayName("非版本库目录返回 false")
        void nonAlwaysExcludedDirectories(String dirName) {
            assertFalse(SearchDirectoryFilter.isAlwaysExcluded(dirName));
        }

        @Test
        @DisplayName("null 安全防御返回 false")
        void nullSafe() {
            assertFalse(SearchDirectoryFilter.isAlwaysExcluded(null));
        }
    }

    @Nested
    @DisplayName("shouldSkipDirectory 与 evaluatePreVisit 遍历过滤裁决测试")
    class DirectorySkipEvaluationTests {

        @ParameterizedTest(name = "永久排除目录任何情况均跳过: {0}")
        @ValueSource(strings = {".git", ".svn", ".hg"})
        @DisplayName("永久排除目录在 includeExcludedDirs=true 或显式点名时依然被跳过")
        void alwaysExcludedNeverPassed(String dirName) {
            // 默认跳过
            assertTrue(SearchDirectoryFilter.shouldSkipDirectory(dirName, false, null));
            assertEquals(FileVisitResult.SKIP_SUBTREE, SearchDirectoryFilter.evaluatePreVisit(dirName, false, null));

            // includeExcludedDirs 为 true 也跳过
            assertTrue(SearchDirectoryFilter.shouldSkipDirectory(dirName, true, null));
            assertEquals(FileVisitResult.SKIP_SUBTREE, SearchDirectoryFilter.evaluatePreVisit(dirName, true, null));

            // 显式点名也跳过
            assertTrue(SearchDirectoryFilter.shouldSkipDirectory(dirName, false, dirName));
            assertEquals(FileVisitResult.SKIP_SUBTREE, SearchDirectoryFilter.evaluatePreVisit(dirName, false, dirName));
        }

        @ParameterizedTest(name = "默认跳过的常规排除目录: {0}")
        @ValueSource(strings = {
                "target", "Target", "build", "out", "bin",
                "node_modules", "dist", ".idea", ".vscode",
                "venv", "__pycache__", ".gradle"
        })
        @DisplayName("常规排除清单在 includeExcludedDirs=false 且无显式点名时默认跳过")
        void regularExcludedSkippedByDefault(String dirName) {
            assertTrue(SearchDirectoryFilter.shouldSkipDirectory(dirName, false, null));
            assertEquals(FileVisitResult.SKIP_SUBTREE, SearchDirectoryFilter.evaluatePreVisit(dirName, false, null));
        }

        @ParameterizedTest(name = "开关打开时放行的常规排除目录: {0}")
        @ValueSource(strings = {"target", "node_modules", "dist", ".idea", "build"})
        @DisplayName("当 includeExcludedDirs=true 时常规排除目录被放行")
        void regularExcludedPassedWhenFlagEnabled(String dirName) {
            assertFalse(SearchDirectoryFilter.shouldSkipDirectory(dirName, true, null));
            assertEquals(FileVisitResult.CONTINUE, SearchDirectoryFilter.evaluatePreVisit(dirName, true, null));
        }

        @Test
        @DisplayName("显式点名目录（explicitAllowedHead）放行，其余排除目录依然跳过")
        void explicitAllowedHeadPassesSpecificDir() {
            // 用户 pattern 为 "node_modules/**"，explicitAllowedHead 为 "node_modules"
            assertFalse(SearchDirectoryFilter.shouldSkipDirectory("node_modules", false, "node_modules"));
            // 忽略大小写点名
            assertFalse(SearchDirectoryFilter.shouldSkipDirectory("NODE_MODULES", false, "node_modules"));

            // 其它常规排除目录依然跳过
            assertTrue(SearchDirectoryFilter.shouldSkipDirectory("target", false, "node_modules"));
            assertTrue(SearchDirectoryFilter.shouldSkipDirectory("dist", false, "node_modules"));
        }

        @ParameterizedTest(name = "正常业务资产目录放行: {0}")
        @ValueSource(strings = {"src", "main", "test", "docs", ".ai-work", ".agents", ".github", "components"})
        @DisplayName("常规资产目录（含未列入排除清单的点开头目录）任何情况均放行遍历")
        void normalDirectoriesAlwaysPassed(String dirName) {
            assertFalse(SearchDirectoryFilter.shouldSkipDirectory(dirName, false, null));
            assertEquals(FileVisitResult.CONTINUE, SearchDirectoryFilter.evaluatePreVisit(dirName, false, null));
        }

        @Test
        @DisplayName("null 目录名安全防御")
        void nullDirNameSafe() {
            assertFalse(SearchDirectoryFilter.shouldSkipDirectory(null, false, null));
            assertEquals(FileVisitResult.CONTINUE, SearchDirectoryFilter.evaluatePreVisit(null, false, null));
        }
    }
}
