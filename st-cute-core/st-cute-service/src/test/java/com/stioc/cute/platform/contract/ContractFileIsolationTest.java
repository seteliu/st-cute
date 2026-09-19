package com.stioc.cute.platform.contract;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局配置目录隔离机制的守卫测试。
 * <p>
 * 测试期必须把全局目录整体切到 {@code ~/.st-cute-test}，否则一切落盘类测试
 * （数据库、config.json、附件、临时目录）都会直接改写生产数据。
 * 本测试为该红线提供机械化守护：一旦有人改坏构建注入或目录解析逻辑，
 * 立即在此处爆红，而不是等到生产配置被测试覆盖后才发现。
 * </p>
 */
class ContractFileIsolationTest {

    /**
     * 隔离属性必须由构建期注入且取值正确
     */
    @Test
    void isolationPropertyInjectedByBuild() {
        String injected = System.getProperty(ContractFile.PROP_GLOBAL_DIR_NAME);
        assertEquals(".st-cute-test", injected,
                "测试期必须由 surefire 注入隔离目录名，否则测试将污染生产配置目录");
    }

    /**
     * 全局配置目录必须落在隔离目录上，且不得指向生产目录
     */
    @Test
    void globalDirPointsToIsolatedDir() {
        File globalDir = ContractFile.getGlobalDir();

        assertTrue(globalDir.getAbsolutePath().endsWith(".st-cute-test"),
                "全局配置目录应指向隔离目录，实际为: " + globalDir.getAbsolutePath());

        // 防污染红线：绝不能等于生产目录 ~/.st-cute
        File productionDir = new File(System.getProperty("user.home"), ContractFile.DEFAULT_GLOBAL_DIR_NAME);
        assertFalse(globalDir.getAbsolutePath().equalsIgnoreCase(productionDir.getAbsolutePath()),
                "全局配置目录不得指向生产目录: " + productionDir.getAbsolutePath());
    }

    /**
     * 库文件、附件、临时等衍生路径必须随全局目录一同联动切换
     */
    @Test
    void derivedPathsFollowGlobalDir() {
        String isolatedRoot = ContractFile.getGlobalDir().getAbsolutePath();

        assertTrue(ContractFile.getGlobalDbFile().getAbsolutePath().startsWith(isolatedRoot),
                "数据库文件必须位于隔离目录内");
        assertTrue(new File(ContractFile.getGlobalDir(), "files").getAbsolutePath().startsWith(isolatedRoot),
                "附件根目录必须位于隔离目录内");
        assertTrue(new File(ContractFile.getGlobalDir(), "tmp").getAbsolutePath().startsWith(isolatedRoot),
                "临时根目录必须位于隔离目录内");
    }

    /**
     * 属性缺省时必须回退默认目录名（保证生产行为零变化）
     */
    @Test
    void fallsBackToDefaultWhenPropertyAbsent() {
        String original = System.getProperty(ContractFile.PROP_GLOBAL_DIR_NAME);
        try {
            System.clearProperty(ContractFile.PROP_GLOBAL_DIR_NAME);
            assertEquals(ContractFile.DEFAULT_GLOBAL_DIR_NAME, ContractFile.resolveGlobalDirName(),
                    "属性缺省时应回退默认全局目录名");

            // 空白值同样回退，防止配置误填空格导致目录名异常
            System.setProperty(ContractFile.PROP_GLOBAL_DIR_NAME, "   ");
            assertEquals(ContractFile.DEFAULT_GLOBAL_DIR_NAME, ContractFile.resolveGlobalDirName(),
                    "属性为空白时应回退默认全局目录名");
        } finally {
            // 还原构建期注入值，避免影响同 JVM 内其他测试
            if (original == null) {
                System.clearProperty(ContractFile.PROP_GLOBAL_DIR_NAME);
            } else {
                System.setProperty(ContractFile.PROP_GLOBAL_DIR_NAME, original);
            }
        }
    }

    /**
     * 数据源 URL 必须随隔离属性联动切换：测试期不得指向生产库。
     * <p>
     * 这是最高危的一条红线——application.yml 中的占位符一旦写错，
     * 落库类测试会直接在用户生产数据库上执行（历史迁移脚本首行是 DROP TABLE）。
     * </p>
     */
    @Test
    void datasourceUrlFollowsIsolationProperty() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        assertFalse(sources.isEmpty(), "必须能加载到 application.yml");

        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(env.getPropertySources()::addLast);

        String url = env.getProperty("spring.datasource.url");
        assertNotNull(url, "application.yml 必须声明 spring.datasource.url");

        // 占位符必须能被真实解析（未解析时会残留 ${...} 字面量）
        assertFalse(url.contains("${"),
                "数据源 URL 占位符未被解析，实际值: " + url);

        // 当前测试 JVM 已注入隔离属性，解析结果必须落在隔离目录
        assertTrue(url.endsWith(".st-cute-test/st-cute.db") || url.endsWith(".st-cute-test\\st-cute.db"),
                "测试期数据源必须指向隔离目录，实际值: " + url);
        assertFalse(url.contains("/.st-cute/st-cute.db"),
                "测试期数据源绝不能指向生产库，实际值: " + url);
    }
}
