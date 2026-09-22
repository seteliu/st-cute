package com.stioc.cute.testkit;

import com.stioc.cute.platform.contract.ContractFile;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试临时目录统一工厂：把全量 {@code @TempDir} 锁定到测试隔离目录下的 testfiles 子目录。
 * <p>
 * 动机：JUnit 默认把 {@code @TempDir} 落在系统临时目录（{@code java.io.tmpdir}）。该目录
 * 由操作系统与其它程序共享，用例产物与系统噪声混放，失败现场难定位、残留难区分归属；
 * 且系统临时目录本身又是权限沙箱白名单成员，用例沙箱与真实环境的边界因此变得模糊。
 * </p>
 * <p>
 * 落点口径：{@code {测试隔离全局目录}/testfiles/junit-<随机后缀>}。测试隔离全局目录由
 * {@link ContractFile} 单点解析（测试期经 surefire 注入 {@code .st-cute-test}），
 * 故本类不自行拼装目录名，与全工程的隔离口径保持一致。
 * </p>
 * <p>
 * 经 {@code src/test/resources/junit-platform.properties} 的
 * {@code junit.jupiter.tempdir.factory.default} 生效，由 JUnit 反射实例化
 * （故要求公开无参构造，本类使用默认构造满足）。
 * </p>
 */
public class TestTempDirFactory implements TempDirFactory {

    /**
     * 测试临时文件根目录名：位于测试隔离全局目录内，与生产数据彻底隔离
     */
    public static final String TEST_TEMP_DIR_NAME = "testfiles";

    /**
     * 单个用例临时目录名前缀，保持与 JUnit 内建风格一致的连续观感
     */
    private static final String TEMP_DIR_PREFIX = "junit-";

    @Override
    public Path createTempDirectory(AnnotatedElementContext elementContext, ExtensionContext extensionContext)
            throws IOException {
        return Files.createTempDirectory(resolveTestTempRoot(), TEMP_DIR_PREFIX);
    }

    /**
     * 解析测试临时文件根目录（{测试隔离全局目录}/testfiles），不存在时按需创建。
     * <p>
     * 供 {@code @TempDir} 工厂与需自行创建临时目录的测试基类共用，
     * 避免目录名与解析口径在多处重复拼装。
     * </p>
     *
     * @return 测试临时文件根目录（已确保物理存在）
     */
    public static Path resolveTestTempRoot() throws IOException {
        Path root = new File(ContractFile.getGlobalDir(), TEST_TEMP_DIR_NAME).toPath();
        Files.createDirectories(root);
        return root;
    }
}
