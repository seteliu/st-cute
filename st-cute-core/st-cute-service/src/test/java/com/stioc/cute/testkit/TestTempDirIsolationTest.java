package com.stioc.cute.testkit;

import com.stioc.cute.platform.contract.ContractFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试临时目录落点隔离的守护测试。
 * <p>
 * 测试产物必须锁定在 {@code ~/.st-cute-test/testfiles} 之下，不得散落到系统临时目录：
 * 后者与操作系统及其它程序共享，产物与噪声混放导致失败现场难定位、残留难辨识归属。
 * 本测试为该红线提供机械守护：一旦有人改坏 {@code junit-platform.properties} 的工厂配置
 * 或 {@link TestTempDirFactory} 的解析口径，立即在此处爆红。
 * </p>
 */
class TestTempDirIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("@TempDir 必须落在测试隔离目录的 testfiles 之下，而非系统临时目录")
    void tempDirLandsUnderIsolatedTestfiles() {
        Path expectedRoot = new File(ContractFile.getGlobalDir(), TestTempDirFactory.TEST_TEMP_DIR_NAME)
                .toPath().toAbsolutePath().normalize();
        Path actual = tempDir.toAbsolutePath().normalize();

        assertTrue(actual.startsWith(expectedRoot),
                "临时目录应落在测试隔离目录内，期望前缀: " + expectedRoot + "，实际: " + actual);

        // 防污染红线：绝不得落在系统临时目录
        Path systemTemp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        assertFalse(actual.startsWith(systemTemp),
                "临时目录不得落在系统临时目录: " + systemTemp + "，实际: " + actual);

        assertTrue(Files.isDirectory(tempDir), "临时目录必须真实存在且为目录");
    }
}
