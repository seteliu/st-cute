package com.stioc.cute.permission;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.stioc.cute.permission.types.PermissionRule;
import com.stioc.cute.platform.common.CharsetAwareFileKit;
import com.stioc.cute.platform.contract.ContractFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限规则存取组件：三级规则文件的合并读取、元数据指纹缓存与本地级写盘。
 * <p>
 * 自 {@link PermissionService} 拆出的独立职责：只负责「规则从哪来、怎么缓存、怎么写回」，
 * 不参与任何裁决决策（裁决链留在 {@link PermissionService}）。
 * </p>
 * <p>
 * 权限契约的特殊性：仅认 全局级 + 项目级 + 本地级，项目通用级 .agents 完全不参与。
 * </p>
 */
@Slf4j
@Component
public class PermissionRuleStore {

    /**
     * 规则合并结果缓存（键：项目根路径，值：三级合并后的规则快照）。
     * <p>
     * 权限规则文件的更新时机已收敛为「装载 + 权限文件改写」两处，故正常链路下无需每次
     * 工具调用重复读盘。缓存自洽性完全依赖文件元数据校验（见 {@link RulesCacheEntry}），
     * 不依赖任何外部刷新调用：手工编辑或热重载后，元数据变化即自动重读。
     * </p>
     * <p>
     * 键为项目路径而非会话 cid：同一项目下的所有会话共享同一份合并结果（含全局级规则，
     * 故各项目各存一份合并快照）。键的数量等于用户访问过的项目数，量级极小，暂不设淘汰。
     * </p>
     */
    private final Map<String, RulesCacheEntry> rulesCache = new ConcurrentHashMap<>();

    /**
     * 规则缓存条目：缓存快照 + 构成该快照的文件元数据指纹。
     * <p>
     * 指纹为「路径 → (mtime, size)」列表；任一文件不存在则记录为空指纹（含"项目从未配置过"的
     * 正常情形）。校验时逐项比对：全部一致才复用缓存，否则重读并替换。
     * </p>
     */
    private record RulesCacheEntry(List<PermissionRule> rules, List<FileFingerprint> fingerprints) {

        /**
         * 指纹是否与当前磁盘状态一致
         */
        boolean matches(List<FileFingerprint> current) {
            return fingerprints.equals(current);
        }
    }

    /**
     * 单个规则文件的元数据指纹：路径 + 最后修改时间 + 文件长度。
     * <p>
     * 长度参与指纹是必需的：文件系统 mtime 粒度有限（部分平台为秒级，Windows 约 10ms），
     * 同一时间窗内的连续改写可能不改变 mtime，叠加长度可显著降低漏判概率。
     * </p>
     */
    private record FileFingerprint(String path, long lastModified, long size) {

        /**
         * 采集指定文件当前指纹；文件不存在时返回空值占位指纹
         * （不存在的文件同样参与比对，"文件被创建/被删除"都是需要重读的变化）
         */
        static FileFingerprint of(File file) {
            if (file == null || !file.exists()) {
                return new FileFingerprint(file != null ? file.getAbsolutePath() : "", -1L, -1L);
            }
            return new FileFingerprint(file.getAbsolutePath(), file.lastModified(), file.length());
        }
    }

    /**
     * 加载合并权限规则（顺序：全局级 → 项目级 → 本地级，裁决时末条优先）。
     * <p>
     * 走文件元数据指纹校验的缓存：指纹一致直接复用合并结果，避免每次工具调用重复读盘；
     * 指纹不符（文件被编辑/创建/删除、或热重载）才重读并替换缓存，故更新时机天然收敛为
     * 「装载 + 权限文件改写」两处，无需任何外部刷新调用。
     * </p>
     *
     * @param projectBasePath 项目根路径（可为 null，表示无项目归属）
     * @return 三级合并后的规则快照（不可变）
     */
    public List<PermissionRule> loadAll(String projectBasePath) {
        String cacheKey = projectBasePath != null ? projectBasePath : "";
        List<FileFingerprint> current = collectFingerprints(projectBasePath);

        RulesCacheEntry cached = rulesCache.get(cacheKey);
        if (cached != null && cached.matches(current)) {
            return cached.rules();
        }

        List<PermissionRule> merged = new ArrayList<>();

        // 1. 全局级：~/.st-cute/permission.json
        File globalConfig = ContractFile.getGlobalPermissionFile();
        if (globalConfig != null) {
            merged.addAll(loadFromFile(globalConfig.toPath()));
        }

        // 2. 项目级与本地级：固定读取 {project}/.st-cute/ 下文件（项目通用级 .agents 完全不参与）
        // 项目级路径收敛在 ContractFile 中解析；本地级（人在回路加白自动写入）由其专用方法给出
        File projConfig = ContractFile.getProjectPermissionFile(projectBasePath);
        if (projConfig != null && projConfig.exists()) {
            merged.addAll(loadFromFile(projConfig.toPath()));
        }

        File localConfig = ContractFile.getProjectPermissionLocalFile(projectBasePath);
        if (localConfig != null && localConfig.exists()) {
            merged.addAll(loadFromFile(localConfig.toPath()));
        }

        // 空结果同样入缓存：项目从未配置过属正常情形，若不入缓存则每次调用都会重新探测磁盘
        List<PermissionRule> snapshot = List.copyOf(merged);
        rulesCache.put(cacheKey, new RulesCacheEntry(snapshot, current));
        return snapshot;
    }

    /**
     * 将放行规则写入本地级配置文件（{project}/.st-cute/permission_local.json）。
     * <p>
     * 写盘后立即失效该项目的规则缓存，保证「总是放行」本次写盘的规则在同一会话的后续工具
     * 调用中立即可见（无需等待 mtime 变化，也无需用户手动热重载）。
     * </p>
     */
    public synchronized void writeLocalRule(PermissionRule rule) {
        writeLocalRule(rule, null);
    }

    /**
     * 将放行规则写入指定项目的本地级配置文件
     *
     * @param rule            待写入的规则
     * @param projectBasePath 项目根路径（null 表示无项目归属）
     */
    public synchronized void writeLocalRule(PermissionRule rule, String projectBasePath) {
        File localFile = ContractFile.getProjectPermissionLocalFile(projectBasePath);
        if (localFile == null) {
            log.warn("当前会话未绑定具体项目路径，跳过写入本地级权限加白文件。");
            return;
        }
        Path localPath = localFile.toPath();
        try {
            File parent = localFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            List<PermissionRule> existing = loadFromFile(localPath);
            existing.add(rule);

            JSONObject wrapper = new JSONObject();
            wrapper.put("rules", existing);

            Files.writeString(localPath, JSON.toJSONString(wrapper, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8);
            // 主动失效缓存：mtime 粒度有限，写盘后立即读可能拿到与旧值相同的 mtime，不能依赖指纹自动失效
            evict(projectBasePath);
            log.info("成功持久化权限规则到本地级配置: {}", rule);
        } catch (IOException e) {
            log.error("写入本地级配置规则失败", e);
        }
    }

    /**
     * 失效指定项目的规则缓存（键与 {@link #loadAll} 保持一致）
     */
    private void evict(String projectBasePath) {
        rulesCache.remove(projectBasePath != null ? projectBasePath : "");
    }

    /**
     * 采集当前项目相关的三个规则文件指纹（全局级 / 项目级 / 本地级，顺序与合并顺序一致）。
     * <p>
     * 不存在的文件以空值指纹占位参与比对——"文件由不存在变为存在"（用户新建配置）与
     * "由存在变为不存在"（用户删除配置）都是必须触发重读的变化。
     * </p>
     */
    private List<FileFingerprint> collectFingerprints(String projectBasePath) {
        return List.of(
                FileFingerprint.of(ContractFile.getGlobalPermissionFile()),
                FileFingerprint.of(ContractFile.getProjectPermissionFile(projectBasePath)),
                FileFingerprint.of(ContractFile.getProjectPermissionLocalFile(projectBasePath))
        );
    }

    /**
     * 解析单个规则文件为规则列表（编码感知读取；三要素缺失的脏规则跳过）
     */
    private List<PermissionRule> loadFromFile(Path path) {
        List<PermissionRule> list = new ArrayList<>();
        if (!Files.exists(path)) {
            return list;
        }
        try {
            String jsonStr = CharsetAwareFileKit.readString(path);
            if (!StringUtils.hasText(jsonStr)) {
                return list;
            }

            JSONObject obj = JSON.parseObject(jsonStr);
            if (obj != null && obj.containsKey("rules")) {
                JSONArray arr = obj.getJSONArray("rules");
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        JSONObject rObj = arr.getJSONObject(i);
                        // 三要素任一缺失（含 null 值）的规则直接跳过：避免 {"toolName": null} 类脏数据
                        // 在裁决链中触发 NPE 中断整个权限评估
                        if (rObj != null && StringUtils.hasText(rObj.getString("toolName"))
                                && rObj.getString("contentPattern") != null
                                && StringUtils.hasText(rObj.getString("effect"))) {
                            list.add(new PermissionRule(
                                    rObj.getString("toolName"),
                                    rObj.getString("contentPattern"),
                                    rObj.getString("effect")
                            ));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("加载配置文件规则失败: {}, 降级为空。异常={}", path.toAbsolutePath(), e.getMessage());
        }
        return list;
    }
}
