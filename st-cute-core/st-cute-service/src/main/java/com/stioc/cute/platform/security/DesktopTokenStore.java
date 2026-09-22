package com.stioc.cute.platform.security;

import com.stioc.cute.platform.contract.ContractFile;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 桌面端凭证仓库：为桌面壳与后端之间的生命周期接口（/api/ping、/api/shutdown）提供内置鉴权凭证
 * <p>
 * 凭证流转机制（仅当后端由桌面壳托管启动时激活，即环境变量 ST_CUTE_DESKTOP_MANAGED=1）：
 * <ol>
 *     <li>后端启动时生成 32 字节 SecureRandom 随机凭证，Base64url 编码后原子写入全局配置目录
 *     下的 {@link #TOKEN_FILE_NAME}（POSIX 权限 600 / Windows 默认 ACL 限当前用户可读）</li>
 *     <li>桌面壳轮询读取该文件取得凭证，后续请求通过 {@link #TOKEN_HEADER} 请求头携带</li>
 *     <li>后台守护线程每秒轮询凭证文件，发现文件被外部删除（桌面壳异常退出后的清理信号）
 *     立即销毁内存凭证，实现凭证生命周期与桌面壳严格绑定</li>
 *     <li>停机指令受理或 JVM 退出时主动销毁凭证并删除凭证文件</li>
 * </ol>
 * 非托管模式（IDE 手动启动）下本组件完全不激活、不落任何文件，行为与历史版本一致
 * </p>
 */
@Slf4j
@Component
public class DesktopTokenStore {

    /**
     * 桌面壳托管模式启动标志环境变量：仅当值为 1 时激活凭证机制
     */
    private static final String MANAGED_ENV = "ST_CUTE_DESKTOP_MANAGED";

    /**
     * 桌面端凭证请求头名称：桌面壳调用生命周期接口时携带
     */
    public static final String TOKEN_HEADER = "X-Desktop-Token";

    /**
     * 凭证文件名：位于全局配置目录（~/.st-cute）下。
     * <p>
     * 公开供权限沙箱排除该文件时引用（凭证可读即等价于停机权限泄露），
     * 避免字面量在多处重复。
     * </p>
     */
    public static final String TOKEN_FILE_NAME = ".desktop-token";

    /**
     * 凭证原始随机字节数：Base64url 编码后长度为 43 字符
     */
    private static final int TOKEN_BYTES = 32;

    /**
     * 凭证文件监视轮询间隔（毫秒）
     */
    private static final long WATCH_INTERVAL_MS = 1000;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 内存中的凭证（Base64url 字符串）：未激活或已销毁时为 null
     */
    private volatile String token;

    /**
     * 凭证文件物理路径：未激活时为 null
     */
    private File tokenFile;

    /**
     * 凭证文件监视线程运行标志
     */
    private volatile boolean watching;

    /**
     * 初始化入口：仅桌面壳托管模式下生成凭证、原子落盘并启动文件监视线程
     */
    @PostConstruct
    public void init() {
        if (!"1".equals(System.getenv(MANAGED_ENV))) {
            log.info("非桌面壳托管模式启动，桌面端凭证机制未激活（/api/ping 开放访问，/api/shutdown 拒绝调用）");
            return;
        }
        try {
            token = generateToken();
            tokenFile = new File(ContractFile.getGlobalDir(), TOKEN_FILE_NAME);
            writeFileAtomically();
            startWatchThread();
            // JVM 退出兜底清理：覆盖绕过 Spring 生命周期的直接退出场景
            Runtime.getRuntime().addShutdownHook(new Thread(this::deleteTokenFileQuietly, "desktop-token-clean"));
            log.info("桌面端凭证已生成并落盘: {}", tokenFile.getAbsolutePath());
        } catch (Exception e) {
            // 凭证落盘失败严禁带着半成品继续运行：清空内存凭证（fail-closed），此时 /api/shutdown 将恒被拒绝，
            // 桌面壳停机时自动降级为超时强杀兜底路径
            token = null;
            log.error("桌面端凭证生成或落盘失败，凭证机制未激活", e);
        }
    }

    /**
     * 销毁凭证：清空内存凭证、停止文件监视并删除凭证文件（幂等，可安全重复调用）
     */
    @PreDestroy
    public void destroy() {
        token = null;
        watching = false;
        deleteTokenFileQuietly();
        log.info("桌面端凭证已销毁");
    }

    /**
     * 凭证机制是否处于激活状态（托管模式且凭证有效存续）
     */
    public boolean isActive() {
        return token != null;
    }

    /**
     * 校验外部传入的凭证是否与当前内存凭证一致
     * <p>
     * 使用常量时间比较，避免逐字节短路返回造成的时序侧信道泄漏
     * </p>
     *
     * @param candidate 请求头传入的候选凭证
     * @return 凭证机制激活且候选凭证完全一致时返回 true
     */
    public boolean matches(String candidate) {
        String current = this.token;
        if (current == null || candidate == null || candidate.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                current.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 32 字节加密安全随机凭证并编码为 Base64url 字符串（43 字符，URL 安全无填充）
     */
    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 原子写入凭证文件：同目录临时文件写入 + 原子重命名，杜绝桌面壳读到半写状态
     */
    private void writeFileAtomically() throws IOException {
        File globalDir = tokenFile.getParentFile();
        if (!globalDir.exists() && !globalDir.mkdirs()) {
            throw new IOException("无法创建全局配置目录: " + globalDir.getAbsolutePath());
        }
        Path tmpPath = Files.createTempFile(globalDir.toPath(), TOKEN_FILE_NAME, ".tmp");
        try {
            // POSIX 平台显式收紧为仅属主可读写；Windows 平台默认 ACL 已限制为当前用户，直接忽略不支持的操作
            try {
                Files.setPosixFilePermissions(tmpPath, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows 文件系统不支持 POSIX 权限模型
            }
            Files.writeString(tmpPath, token, StandardCharsets.UTF_8);
            Files.move(tmpPath, tokenFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmpPath);
        }
    }

    /**
     * 启动凭证文件监视守护线程：检测到文件被外部删除时立即销毁内存凭证
     * <p>
     * 典型场景为桌面壳进程异常退出前的清理动作，此时凭证持有者已消失，
     * 继续保留凭证将扩大未授权停机风险
     * </p>
     */
    private void startWatchThread() {
        watching = true;
        Thread.ofVirtual().name("desktop-token-watch").start(() -> {
            while (watching) {
                try {
                    Thread.sleep(WATCH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (token != null && !tokenFile.exists()) {
                    log.warn("检测到凭证文件被外部删除（桌面壳可能已异常退出），立即销毁内存凭证");
                    token = null;
                    watching = false;
                }
            }
        });
    }

    /**
     * 静默删除凭证文件：删除失败仅记录警告，不抛出异常影响调用方
     */
    private void deleteTokenFileQuietly() {
        if (tokenFile != null && tokenFile.exists() && !tokenFile.delete()) {
            log.warn("凭证文件删除失败: {}", tokenFile.getAbsolutePath());
        }
    }
}
