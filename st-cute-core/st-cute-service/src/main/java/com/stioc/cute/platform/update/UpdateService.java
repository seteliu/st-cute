package com.stioc.cute.platform.update;

import com.alibaba.fastjson2.JSON;
import com.stioc.cute.websocket.WebSocketBroadcast;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 升级检查与执行服务：拉取远端 latest.json、比对版本、下载校验新包并编排升级重启
 * <p>
 * 元数据带 5 分钟内存缓存（关于页每次打开都会触发检查，频繁打开不应每次打外部更新源）；
 * 拉取失败不缓存、不抛异常，以 CHECK_FAIL 状态降级返回交由前端展示重试入口。
 * </p>
 * <p>
 * apply 升级流程（仅 DESKTOP / SCRIPT 接力形态可用）：
 * 会话锁互斥 → 定位运行 jar 真实路径 → 下载新包（jarUrl 兼容裸 jar / base 包 zip，
 * 先落 .download 临时文件、识别落位为 updates/app.jar.new，进度经 WebSocket 广播）
 * → sha256 校验（latest.json 声明为空则跳过）→ 写 pending-restart.json 握手文件
 * → 广播重启预告 → 优雅停机并以退出码 88 结束进程（接力者完成换包重启，
 * 见 script/gen-launcher-scripts.mjs 与桌面壳 update.rs）。
 * </p>
 */
@Slf4j
@Service
public class UpdateService {

    /** 元数据缓存有效期（毫秒）：期间内重复检查直接命中内存缓存 */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    /** 升级失败记录保留时长：超过后 check 不再携带（懒过期，读取时顺手清理） */
    private static final long FAILURE_TTL_MS = 24 * 60 * 60 * 1000L;

    /** 升级失败记录文件名（位于 updates/ 目录，与握手文件同域） */
    private static final String LAST_RESULT_FILE = "last-upgrade-result.json";

    /** 检查结论常量 */
    public static final String STATUS_CHECK_FAIL = "CHECK_FAIL";
    public static final String STATUS_UNSUPPORTED = "UNSUPPORTED";
    public static final String STATUS_OLD_BASE = "OLD_BASE";
    public static final String STATUS_UPDATABLE = "UPDATABLE";
    public static final String STATUS_LATEST = "LATEST";

    /** 升级重启专用退出码：接力者（官方脚本 / 桌面壳）据此完成换包并重启 */
    public static final int EXIT_CODE_UPGRADE_RESTART = 88;

    /** 升级会话锁：同一时刻仅允许一个升级流程（多标签页/多客户端并发点击防护） */
    private final AtomicBoolean upgrading = new AtomicBoolean(false);

    /** 元数据地址（latest.json 托管位置，可经配置覆盖） */
    @Value("${st-cute.update.latest-url:https://meta.61jun.com/st-cute/latest.json}")
    private String latestUrl;

    /** 当前 jar 版本（构建期由 Maven 资源过滤注入） */
    @Value("${st-cute.version:unknown}")
    private String currentVersion;

    @Resource
    private ConfigurableApplicationContext applicationContext;

    @Resource
    private WebSocketBroadcast broadcast;

    /** 元数据专用 HTTP 客户端：连接与读取均短超时，避免关于页检查被慢源拖住 */
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    /** 下载专用 HTTP 客户端：读超时放宽（大文件慢速网络） */
    private final OkHttpClient downloadClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    /** 缓存的元数据与写入时间（volatile 双检：读多写少，锁仅保护回源） */
    private volatile UpdateMeta cachedMeta;
    private volatile long cachedAtMillis;

    // ─────────────────────────── 检查链路 ───────────────────────────

    /**
     * 执行更新检查
     *
     * @param refresh true 表示绕过缓存强制回源（前端「重新检查」入口）
     * @return 检查结果（含运行方式、版本信息与结论状态）
     */
    public UpdateCheckDto check(boolean refresh) {
        LauncherType type = LauncherType.fromEnv();
        UpdateCheckDto dto = new UpdateCheckDto();
        dto.setLauncherType(type.name());
        dto.setLauncherTypeName(type.getDisplayName());
        dto.setLauncherVersion(LauncherType.launcherVersionFromEnv());
        dto.setCurrentVersion(currentVersion);

        // 远端元数据：拉取失败降级为 CHECK_FAIL（保留本地信息供前端展示运行方式与当前版本）
        UpdateMeta meta = fetchLatest(refresh);
        if (meta == null) {
            dto.setStatus(STATUS_CHECK_FAIL);
            return dto;
        }
        dto.setLatestVersion(meta.getJarVersion());
        dto.setNotes(meta.getNotes());
        dto.setOldBaseTip(meta.getOldBaseTip());
        dto.setReleaseUrl(meta.getReleaseUrl());
        dto.setMinBaseVersion(meta.getMinBaseVersion());

        // 手动运行：无论远端是否有新版，均不支持自动升级
        if (type == LauncherType.MANUAL) {
            dto.setStatus(STATUS_UNSUPPORTED);
            return dto;
        }

        // 底座过旧：底座版本缺失（环境变量异常）同样按过旧处理，保守降级引导整包更新
        if (!StringUtils.hasText(dto.getLauncherVersion())
                || compareVersion(dto.getLauncherVersion(), meta.getMinBaseVersion()) < 0) {
            dto.setStatus(STATUS_OLD_BASE);
            return dto;
        }

        dto.setStatus(compareVersion(meta.getJarVersion(), currentVersion) > 0
                ? STATUS_UPDATABLE : STATUS_LATEST);

        // 上次升级失败信息（24h 内有效）：浏览器离线错过的失败事件经文件状态化后在 check 捎带
        attachLastFailure(dto);
        return dto;
    }

    // ─────────────────────────── 升级执行链路 ───────────────────────────

    /**
     * 是否有升级流程正在进行（前端按钮防重入与状态展示）
     */
    public boolean isUpgrading() {
        return upgrading.get();
    }

    /**
     * 执行升级：下载 → 校验 → 握手文件 → 广播 → 退出码 88 重启
     * <p>
     * 前置校验失败（含并发占用）同步抛出异常交由 HTTP 层返回业务错误；
     * 下载与校验失败会释放会话锁并广播失败原因。触发停机后本方法即返回，
     * 实际进程退出由 {@link #scheduleExit} 异步执行。
     * </p>
     *
     * @return 目标版本号（供调用方回显）
     */
    public String apply() {
        // 1. 会话锁互斥：抢不到即已有升级流程进行中
        if (!upgrading.compareAndSet(false, true)) {
            throw new IllegalStateException("升级流程已在进行中，请勿重复触发");
        }
        try {
            // 2. 前置条件复验：仅接力形态可用（前端已按 check 结论隐藏按钮，此处为纵深防御）
            LauncherType type = LauncherType.fromEnv();
            if (type == LauncherType.MANUAL) {
                throw new IllegalStateException("当前运行方式不支持自动升级（手动运行）");
            }

            // 3. 拉取最新元数据（强制回源：升级动作必须基于最新清单，不吃缓存）
            UpdateMeta meta = fetchLatest(true);
            if (meta == null) {
                throw new IllegalStateException("升级元数据拉取失败，请稍后重试");
            }
            if (compareVersion(meta.getJarVersion(), currentVersion) <= 0) {
                throw new IllegalStateException("远端版本不高于当前版本，无需升级");
            }
            if (!StringUtils.hasText(meta.getJarUrl())) {
                throw new IllegalStateException("升级元数据缺少 jarUrl，无法下载");
            }

            // 4. 定位运行 jar 真实路径（下载目录锚定在其旁的 updates/）
            Path jarPath = resolveRunningJarPath();
            Path updatesDir = jarPath.getParent().resolve("updates");
            Files.createDirectories(updatesDir);
            Path pendingFile = updatesDir.resolve("app.jar.new");

            // 5. 下载新包（zip 分片流式下载 + sha256 边下边算 + 进度广播）
            broadcastProgress("downloading", 0, null);
            String actualSha256 = downloadAndVerify(meta, pendingFile);
            broadcastProgress("downloaded", 100, meta.getJarVersion());
            log.info("[Update] 新包下载完成: {} (sha256={})", pendingFile, actualSha256);

            // 6. 写升级握手文件（接力者换包后由新版启动时自确认闭环）
            writePendingRestart(updatesDir, meta.getJarVersion());

            // 7. 广播重启预告（前端挂重启遮罩并开始轮询 /api/ping）
            broadcast.broadcast(WebSocketBroadcast.EventType.UPDATE_RESTARTING, null,
                    Map.of("fromVersion", currentVersion, "toVersion", meta.getJarVersion()));

            // 8. 优雅停机并退出（退出码 88 交由接力者接力）。
            // 先返回 HTTP 响应让发起方挂上遮罩，停机动作延后 3 秒执行——
            // 给 RESTARTING 广播送达与遮罩渲染留足用户可感知的窗口
            scheduleExit(3, TimeUnit.SECONDS);
            return meta.getJarVersion();
        } catch (Exception e) {
            upgrading.set(false);
            broadcastProgress("failed", -1, e.getMessage());
            // 失败状态落盘：浏览器离线错过 failed 广播时，经此文件在下次 check 捎带（24h 过期）
            writeLastFailureQuietly(e.getMessage());
            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException("升级失败: " + e.getMessage(), e);
        }
    }

    /** 上次升级失败信息（浏览器离线错过的失败事件状态化载体，24h 过期） */
    @lombok.Data
    public static class LastFailure {
        /** 失败原因（异常消息） */
        private String reason;
        /** 失败时间戳（毫秒） */
        private long at;
        /** 失败时瞄准的目标版本 */
        private String toVersion;
    }

    /** check 链路读取失败记录：存在且未过期则填充 DTO，过期则顺手删除（懒过期） */
    private void attachLastFailure(UpdateCheckDto dto) {
        try {
            Path dir = resolveUpdatesDir();
            if (dir == null) {
                return;
            }
            Path file = dir.resolve(LAST_RESULT_FILE);
            if (!Files.exists(file)) {
                return;
            }
            LastFailure failure = JSON.parseObject(Files.readString(file), LastFailure.class);
            if (failure == null) {
                Files.deleteIfExists(file);
                return;
            }
            if (System.currentTimeMillis() - failure.getAt() > FAILURE_TTL_MS) {
                Files.deleteIfExists(file);
                return;
            }
            dto.setLastFailureReason(failure.getReason());
        } catch (Exception e) {
            log.debug("[Update] 读取上次升级失败记录出错（忽略）: {}", e.getMessage());
        }
    }

    /** 失败记录落盘（静默：落盘失败不影响主流程，仅记日志） */
    private void writeLastFailureQuietly(String reason) {
        try {
            Path dir = resolveUpdatesDir();
            if (dir == null) {
                return;
            }
            Path file = dir.resolve(LAST_RESULT_FILE);
            LastFailure failure = new LastFailure();
            failure.setReason(reason);
            failure.setAt(System.currentTimeMillis());
            failure.setToVersion(cachedMeta != null ? cachedMeta.getJarVersion() : null);
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.toJSONString(failure));
        } catch (Exception e) {
            log.warn("[Update] 升级失败记录落盘失败（忽略）: {}", e.getMessage());
        }
    }

    /** 清除失败记录（升级成功闭环时调用） */
    private void clearLastFailure() {
        try {
            Path dir = resolveUpdatesDir();
            if (dir != null) {
                Files.deleteIfExists(dir.resolve(LAST_RESULT_FILE));
            }
        } catch (Exception e) {
            log.debug("[Update] 清理升级失败记录出错（忽略）: {}", e.getMessage());
        }
    }

    /** 定位 updates/ 目录（运行 jar 同级；定位失败返回 null 由调用方兜底） */
    private Path resolveUpdatesDir() {
        try {
            return resolveRunningJarPath().getParent().resolve("updates");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 流式下载新包并校验 sha256
     * <p>
     * jarUrl 兼容两种形态，按文件头魔数自动识别（用户在 latest.json 里怎么填都能用）：
     * <ul>
     *   <li>裸 jar（PK 魔数 + Spring Boot zip 结构）：直接落位 app.jar.new</li>
     *   <li>base 包 zip（内含单文件 app.jar）：下载后提取首条非目录条目落位</li>
     * </ul>
     * 下载先写 .download 临时文件，成功后才改名/提取为 app.jar.new——半成品永不以正式
     * 待装名示人，杜绝中断残留物被接力者误当有效新包就位。进度按已读字节数节流广播。
     * </p>
     */
    private String downloadAndVerify(UpdateMeta meta, Path pendingFile) throws IOException {
        Path tempFile = pendingFile.resolveSibling("app.jar.new.download");
        try {
            // 禁用透明 gzip：jar 本身已是 zip 压缩体，二次 gzip 徒耗服务器 CPU，
            // 且解压流 contentLength()=-1 会令进度广播失效（总长未知）
            Request request = new Request.Builder().url(meta.getJarUrl()).get()
                    .header("Accept-Encoding", "identity").build();
            Files.deleteIfExists(tempFile);
            long totalBytes;
            try (Response response = downloadClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("下载失败: HTTP " + response.code());
                }
                totalBytes = response.body().contentLength();
                long read = 0;
                long lastBroadcast = -1;
                try (InputStream in = response.body().byteStream();
                     OutputStream out = Files.newOutputStream(tempFile)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                        read += n;
                        // 进度节流广播：每累计 256KB 推送一次。总长已知推百分比；
                        // 总长未知（代理强制 gzip 等）退化为已读字节数，前端展示下载量
                        if (read - lastBroadcast >= 256 * 1024) {
                            lastBroadcast = read;
                            if (totalBytes > 0) {
                                broadcastProgress("downloading", (int) (read * 100 / totalBytes), null);
                            } else {
                                broadcastProgress("downloading", null, String.format(Locale.ROOT, "%.1f MB", read / 1024.0 / 1024.0));
                            }
                        }
                    }
                }
            }
            // 魔数识别：bare jar（PK 头）与 base 包 zip 同为 PK 开头，但裸 jar 的下载即最终产物，
            // zip 下载包需提取——统一交给 normalize 判定并落位 app.jar.new
            normalizeDownloadToPending(tempFile, pendingFile);
            // 对最终待装 jar 计算摘要并校验（发布口径 = app.jar 文件本身的摘要；声明为空则跳过）
            String actual = sha256Of(pendingFile);
            verifySha256(meta, actual);
            return actual;
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /** 魔数识别下载形态并落位待装文件：PK 开头视为裸 jar 直接改名，其余按 zip 提取 */
    private void normalizeDownloadToPending(Path tempFile, Path pendingFile) throws IOException {
        int magic = readLeadingMagic(tempFile);
        if (magic == 0x504B0304) {
            // PK\u0003\u0004：zip 家族（裸 jar 与 base 包 zip 均以此开头）。
            // Spring Boot 装配 jar 的首个条目为 META-INF/（目录），而 base 包 zip 的首条目即 app.jar——
            // 以首个非目录条目名区分：含路径分隔符/以 META-INF 开头的属 jar 内部结构 → 裸 jar
            if (looksLikeBareJar(tempFile)) {
                Files.move(tempFile, pendingFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                extractJarFromZip(tempFile, pendingFile);
            }
        } else {
            // 非 zip 家族（理论不该出现，如 tar.gz 误填）：按 zip 提取报错兜底
            extractJarFromZip(tempFile, pendingFile);
        }
    }

    /** 读取文件头四字节魔数（文件过短时按可读到的字节返回） */
    private int readLeadingMagic(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            int b0 = in.read();
            int b1 = in.read();
            int b2 = in.read();
            int b3 = in.read();
            if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
                return -1;
            }
            return (b0 << 24) | (b1 << 16) | (b2 <<  8) | b3;
        }
    }

    /** 判定 PK 文件是否为裸 jar（而非仅含 app.jar 的 base 包 zip）：首个非目录条目含路径层级即 jar 内部结构 */
    private boolean looksLikeBareJar(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                return entry.getName().contains("/") || entry.getName().startsWith("META-INF");
            }
        } catch (IOException ignored) {
            // 非法 zip 结构：交由 extractJarFromZip 统一抛错
        }
        return true;
    }

    /** 从 base 包 zip 中提取 app.jar 至目标路径（zip 内首个非目录条目即 jar） */
    private void extractJarFromZip(Path zipPath, Path target) throws IOException {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().contains("__MACOSX")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            }
        }
        throw new IOException("升级包 zip 中未找到 jar 条目");
    }

    /** 计算文件 sha256（十六进制小写） */
    private String sha256Of(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 算法不可用", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 校验远端声明的 sha256 与实际值一致（声明缺失时跳过校验并告警） */
    private void verifySha256(UpdateMeta meta, String actual) {
        String declared = meta.getJarSha256();
        if (!StringUtils.hasText(declared)) {
            log.warn("[Update] latest.json 未声明 jarSha256，跳过完整性校验");
            return;
        }
        String normalized = declared.trim()
                .replaceFirst("(?i)^sha256[:\\s]*", "")
                .toLowerCase(Locale.ROOT);
        if (!normalized.equals(actual)) {
            throw new IllegalStateException("sha256 校验失败：声明值与下载内容不一致");
        }
    }

    /**
     * 定位运行中 jar 的真实路径
     * <p>
     * 优先读 {@code java.class.path}：{@code java -jar app.jar} 启动时它恰为 jar 路径本身，
     * 最直接且天然避开嵌套 URI 问题。兜底解析 CodeSource：Spring Boot fat jar 内类的
     * CodeSource 是 {@code jar:file:/path/app.jar!/BOOT-INF/classes/} 嵌套形态，
     * {@code Paths.get} 无法直接消费（报 Provider "jar" not installed），
     * 需由 {@link #parseCodeSourceJarPath} 手工剥离外层 file 段。
     * </p>
     */
    private Path resolveRunningJarPath() {
        // 优先：-jar 启动时 java.class.path 即为 jar 路径
        String classPath = System.getProperty("java.class.path");
        if (classPath != null && !classPath.isBlank()) {
            for (String entry : classPath.split(File.pathSeparator)) {
                if (entry.endsWith(".jar")) {
                    Path candidate = Paths.get(entry);
                    if (Files.isRegularFile(candidate)) {
                        return candidate.toAbsolutePath();
                    }
                }
            }
        }
        // 兜底：CodeSource 解析（覆盖非 -jar 形态的 fat jar 运行方式）
        try {
            URI location = getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            Path parsed = parseCodeSourceJarPath(location);
            if (parsed != null) {
                return parsed;
            }
        } catch (Exception ignored) {
            // 解析失败落入下方统一报错
        }
        throw new IllegalStateException("定位运行 jar 路径失败（java.class.path 无 jar 条目且 CodeSource 无法解析）");
    }

    /**
     * 解析 CodeSource 位置为外层 jar 文件路径
     *
     * @param location 类装载位置 URI（可能是 jar: 嵌套形态或裸 file 形态）
     * @return jar 文件路径；非文件系统 jar 形态（如 IDE exploded 目录）返回 null
     */
    static Path parseCodeSourceJarPath(URI location) {
        if (location == null) {
            return null;
        }
        try {
            if ("jar".equals(location.getScheme())) {
                // Spring Boot fat jar 布局：jar:file:/path/app.jar!/BOOT-INF/classes/ —— 取 !/ 前的外层 file 段
                String ssp = location.getSchemeSpecificPart();
                int bangIndex = ssp.indexOf("!/");
                String filePart = bangIndex >= 0 ? ssp.substring(0, bangIndex) : ssp;
                Path path = Paths.get(URI.create(filePart));
                return Files.isRegularFile(path) ? path : null;
            }
            if ("file".equals(location.getScheme())) {
                Path path = Paths.get(location);
                if (Files.isRegularFile(path)
                        && path.getFileName() != null
                        && path.getFileName().toString().endsWith(".jar")) {
                    return path;
                }
            }
        } catch (Exception ignored) {
            // 非法 URI 等场景交由调用方统一报错
        }
        return null;
    }

    /** 写升级握手文件（记录 from→to 版本，新版启动时据此确认升级闭环） */
    private void writePendingRestart(Path updatesDir, String toVersion) throws IOException {
        Path file = updatesDir.resolve("pending-restart.json");
        String content = "{\"fromVersion\":\"" + currentVersion + "\",\"toVersion\":\"" + toVersion + "\"}";
        Files.writeString(file, content);
    }

    /** 广播升级进度（phase: downloading/downloaded/failed 等，percent: 0~100） */
    private void broadcastProgress(String phase, Integer percent, String detail) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("phase", phase);
            payload.put("percent", percent);
            payload.put("detail", detail);
            broadcast.broadcast(WebSocketBroadcast.EventType.UPDATE_PROGRESS, null, payload);
        } catch (Exception e) {
            log.warn("[Update] 广播升级进度失败: {}", e.getMessage());
        }
    }

    /**
     * 优雅停机并退出：延迟执行确保响应与广播先行送达，随后以退出码 88 结束进程
     * <p>
     * 等待时长固定 3 秒（{@code delay=3, unit=SECONDS} 由调用方传入）：
     * RESTARTING 广播送达 + 前端遮罩渲染 + 用户看清提示的合理窗口，
     * 与前端"先通知再重启"的交互预期对齐。异步线程执行，不阻塞 apply 返回响应。
     * </p>
     */
    private void scheduleExit(long delay, TimeUnit unit) {
        Thread closer = new Thread(() -> {
            try {
                unit.sleep(delay);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            log.info("[Update] 升级就绪，开始优雅停机并退出 (退出码 {})...", EXIT_CODE_UPGRADE_RESTART);
            try {
                applicationContext.close();
            } catch (Exception e) {
                log.error("[Update] 关闭 Spring 上下文时发生异常", e);
            } finally {
                System.exit(EXIT_CODE_UPGRADE_RESTART);
            }
        }, "update-exit");
        closer.setDaemon(false);
        closer.start();
    }

    /**
     * 启动自确认（升级闭环握手）：成功启动后清理升级痕迹
     * <p>
     * 判定链：pending-restart.json 存在且 toVersion == 当前版本 → 升级闭环成功：
     * 删除握手文件与备份（官方脚本场景的 app.jar.bak / 桌面壳场景的 updates/app.jar.bak），
     * 广播升级完成事件；toVersion 不匹配 → 上次接力异常，保留现场并告警（下次启动复检）。
     * </p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartupConfirmUpgrade() {
        try {
            Path jarPath = resolveRunningJarPath();
            Path updatesDir = jarPath.getParent().resolve("updates");
            Path pendingFile = updatesDir.resolve("pending-restart.json");
            if (!Files.exists(pendingFile)) {
                return;
            }
            String content = Files.readString(pendingFile).trim();
            String toVersion = JSON.parseObject(content).getString("toVersion");
            if (currentVersion.equals(toVersion)) {
                Files.deleteIfExists(pendingFile);
                Files.deleteIfExists(updatesDir.resolve("app.jar.bak"));
                Files.deleteIfExists(jarPath.resolveSibling("app.jar.bak"));
                clearLastFailure();
                log.info("[Update] 升级闭环确认：{} → {}，已清理备份、握手文件与失败记录", JSON.parseObject(content).getString("fromVersion"), currentVersion);
                broadcastProgress("completed", 100, currentVersion);
            } else {
                log.warn("[Update] 检测到异常升级残留: 握手指向 {} 但当前为 {}，保留现场待查（判定为新版启动失败已回滚）", toVersion, currentVersion);
                // 回滚场景状态化：浏览器离线错过 ws 事件时，经失败记录在下次 check 捎带（覆盖旧记录）
                writeLastFailureQuietly("新版本启动失败，已自动回滚至 " + currentVersion);
            }
        } catch (Exception e) {
            log.warn("[Update] 启动自确认升级闭环时出错（不影响启动）: {}", e.getMessage());
        }
    }

    // ─────────────────────────── 元数据链路 ───────────────────────────

    /**
     * 拉取远端元数据（带缓存）
     *
     * @return 元数据；拉取失败或内容非法时返回 null
     */
    private UpdateMeta fetchLatest(boolean refresh) {
        if (!refresh && isCacheFresh()) {
            return cachedMeta;
        }
        synchronized (this) {
            if (!refresh && isCacheFresh()) {
                return cachedMeta;
            }
            try {
                Request request = new Request.Builder().url(latestUrl).get().build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        log.warn("[Update] 拉取升级元数据失败: HTTP {} ({}).", response.code(), latestUrl);
                        return null;
                }
                    UpdateMeta meta = JSON.parseObject(response.body().string(), UpdateMeta.class);
                    if (meta == null || !StringUtils.hasText(meta.getJarVersion())) {
                        log.warn("[Update] 升级元数据内容非法（缺 jarVersion）: {}.", latestUrl);
                        return null;
                    }
                    cachedMeta = meta;
                    cachedAtMillis = System.currentTimeMillis();
                    return meta;
                }
            } catch (Exception e) {
                log.warn("[Update] 拉取升级元数据异常: {} ({}).", e.getMessage(), latestUrl);
                return null;
            }
        }
    }

    private boolean isCacheFresh() {
        UpdateMeta meta = cachedMeta;
        return meta != null && System.currentTimeMillis() - cachedAtMillis < CACHE_TTL_MS;
    }

    /**
     * 三段语义化版本比较（0.3.0 形态；不足三段按 0 补齐，非数字后缀截断忽略）
     *
     * @return 负数表示 left 小于 right；0 相等；正数大于
     */
    static int compareVersion(String left, String right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        String[] leftParts = left.trim().split("\\.");
        String[] rightParts = right.trim().split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int compared = Integer.compare(numericSegment(leftParts, i), numericSegment(rightParts, i));
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    /** 提取版本段的主导数字（越界按 0；带预发布后缀如 0.3.0-beta 取 0） */
    private static int numericSegment(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        String segment = parts[index].replaceAll("[^0-9].*$", "");
        if (segment.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
