package com.stioc.cute.runtime.store;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.datasource.FlexDataSource;
import com.mybatisflex.core.mybatis.FlexConfiguration;
import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.common.EngineLock;
import com.stioc.cute.engine.facade.ContextFacade;
import com.stioc.cute.engine.facade.ConversationFacade;
import com.stioc.cute.engine.facade.LoopFacade;
import com.stioc.cute.engine.facade.ToolFacade;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.platform.config.SqliteLocalDateTimeTypeHandler;
import com.stioc.cute.repository.ConversationMapper;
import com.stioc.cute.repository.MessageMapper;
import com.stioc.cute.runtime.common.GuavaStripedLockProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 宿主持久化落库测试抽象基类。
 * <p>
 * 特性与保障：
 * 1. 类级别独立临时库：每个测试类创建独立临时目录与 SQLite 文件，生命周期与测试类绑定；
 * 2. 方法级别毫秒级隔离：通过 {@link #cleanTablesEach()} 每次测试前清空业务表与自增序列，保障用例纯净；
 * 3. 真实 Flyway 迁移建表：直接读取 classpath 下 V0.2.0__init.sql 真实脚本建表，严禁改动生产脚本；
 * 4. 纯净 MyBatis-Flex 启动：不拉起 Spring 容器与全局定时任务，彻底阻断僵死消息与日志清理副作用；
 * 5. Windows 文件句柄安全释放：在 {@link #tearDownAll()} 先关闭连接池，再安全清理目录（附 deleteOnExit 兜底）。
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractStoreIntegrationTest {

    protected Path tempDir;
    protected HikariDataSource dataSource;
    protected ConversationMapper conversationMapper;
    protected MessageMapper messageMapper;
    protected ConversationStoreImpl conversationStore;
    protected MessageStoreImpl messageStore;

    @BeforeAll
    void setUpAll() throws Exception {
        // 1. 初始化独立临时目录与单连接 HikariCP 数据源
        this.tempDir = Files.createTempDirectory("st-cute-store-test-");
        Path dbPath = tempDir.resolve("test-store-" + UUID.randomUUID() + ".db");
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath().toString().replace('\\', '/');

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setConnectionInitSql("PRAGMA journal_mode=WAL;");
        this.dataSource = new HikariDataSource(hikariConfig);

        // 2. 运行 Flyway 执行真实数据库版本迁移建表
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .cleanDisabled(true)
                .load();
        flyway.migrate();

        // 3. 构建隔离环境的 MyBatis-Flex 配置并注入类型处理器
        String envId = "env-" + UUID.randomUUID();
        FlexDataSource flexDataSource = new FlexDataSource(envId, dataSource);
        JdbcTransactionFactory transactionFactory = new JdbcTransactionFactory();
        Environment environment = new Environment(envId, transactionFactory, flexDataSource);

        FlexConfiguration flexConfig = new FlexConfiguration(environment);
        flexConfig.getTypeHandlerRegistry().register(LocalDateTime.class, SqliteLocalDateTimeTypeHandler.class);
        flexConfig.setMapUnderscoreToCamelCase(true);

        MybatisFlexBootstrap bootstrap = new MybatisFlexBootstrap();
        bootstrap.setEnvironmentId(envId);
        bootstrap.setDataSource(flexDataSource);
        bootstrap.setConfiguration(flexConfig);
        bootstrap.addMapper(ConversationMapper.class);
        bootstrap.addMapper(MessageMapper.class);
        bootstrap.start();

        this.conversationMapper = bootstrap.getMapper(ConversationMapper.class);
        this.messageMapper = bootstrap.getMapper(MessageMapper.class);

        // 4. 组装 ConversationStoreImpl（注入真实 Mapper 与 GuavaStripedLockProvider）
        this.conversationStore = new ConversationStoreImpl();
        ReflectionTestUtils.setField(conversationStore, "conversationMapper", conversationMapper);

        EngineLock lockProvider = new GuavaStripedLockProvider();
        AgentEngine mockEngine;
        try {
            Constructor<AgentEngine> constructor = AgentEngine.class.getDeclaredConstructor(
                    ContextFacade.class, LoopFacade.class, ConversationFacade.class, ToolFacade.class,
                    ConversationStore.class, MessageStore.class, EngineLock.class
            );
            constructor.setAccessible(true);
            mockEngine = constructor.newInstance(null, null, null, null, null, null, lockProvider);
        } catch (Exception e) {
            throw new IllegalStateException("实例化测试用 AgentEngine 失败", e);
        }
        ReflectionTestUtils.setField(conversationStore, "agentEngine", mockEngine);

        // 5. 组装 MessageStoreImpl（注入真实 Mapper）
        this.messageStore = new MessageStoreImpl();
        ReflectionTestUtils.setField(messageStore, "messageMapper", messageMapper);
    }

    @BeforeEach
    void cleanTablesEach() throws Exception {
        // 每个测试方法前快速清空业务表与自增计数器，保证用例完全隔离
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM t_conversation");
            stmt.executeUpdate("DELETE FROM t_message");
            stmt.executeUpdate("DELETE FROM sqlite_sequence WHERE name IN ('t_conversation', 't_message')");
        }
    }

    @AfterAll
    void tearDownAll() {
        // 1. 关闭数据库连接池与文件句柄
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }

        // 2. 递归安全清理临时目录与 SQLite 文件
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception e) {
                        p.toFile().deleteOnExit();
                    }
                });
            } catch (Exception ignored) {
            }
        }
    }
}
