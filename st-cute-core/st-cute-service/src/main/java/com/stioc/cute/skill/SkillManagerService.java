package com.stioc.cute.skill;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.platform.common.CharsetAwareFileKit;
import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.runtime.loop.RuntimeContext;
import com.stioc.cute.skill.types.Skill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能体动态 Skill 技能包热扫描与管理中心。
 * 负责扫描全局级与项目级目录下的技能定义、热重载解析，并往 SlashCommandRegistry 热挂载命令。
 */
@Slf4j
@Service
public class SkillManagerService {

    /**
     * 内存中维护的已加载全局级公共技能缓存（~/.st-cute/skills，Key 为技能名，Value 为 Skill 实例）
     */
    private final Map<String, Skill> globalSkillsCache = new ConcurrentHashMap<>();

    /**
     * 动态热装载指定项目工作区下的全部专属技能包元数据与提示词。
     * <p>
     * Skill 为覆盖型契约：装载顺序 全局级 → 项目通用级 → 项目级，同名技能后写覆盖，项目级最终生效。
     * </p>
     */
    public synchronized void loadProjectSkills(AgentContext context, String projectBasePath) {
        if (context == null) {
            return;
        }

        RuntimeContext runtimeCtx = context.extra(RuntimeContext.class);
        if (runtimeCtx == null) {
            return;
        }

        runtimeCtx.getSkills().clear();

        // 1. 重新扫描装填全局级公共技能：每次重扫物理目录并刷新缓存以支持热重载（对齐 HookService 的做法），
        //    避免惰性缓存导致磁盘上新增或修改的全局级技能（如新装的技能包）永远无法被感知
        globalSkillsCache.clear();
        File globalSkillsDir = ContractFile.getGlobalSkillsDir();
        if (globalSkillsDir != null && globalSkillsDir.exists() && globalSkillsDir.isDirectory()) {
            scanDirectory(globalSkillsDir.toPath(), "GLOBAL", globalSkillsCache);
        }
        runtimeCtx.getSkills().addAll(globalSkillsCache.values());

        // 2. 装填并覆盖项目专属技能：按 项目通用级 → 项目级 遍历（后读覆盖，项目级最终生效）
        if (StringUtils.hasText(projectBasePath)) {
            ContractFile.forEachProjectFile(projectBasePath, ContractFile.DIR_SKILLS, projectSkillsDir -> {
                if (projectSkillsDir.isDirectory()) {
                    Map<String, Skill> tempMap = new LinkedHashMap<>();
                    scanDirectory(projectSkillsDir.toPath(), "PROJECT", tempMap);
                    for (Skill ps : tempMap.values()) {
                        // 若存在同名，覆盖之
                        runtimeCtx.getSkills().removeIf(s -> s.getName().equalsIgnoreCase(ps.getName()));
                        runtimeCtx.getSkills().add(ps);
                    }
                    log.info("会话 {} 从 {} 成功装载了 {} 个项目专属技能", context.getCid(), projectSkillsDir.getParentFile().getName(), tempMap.size());
                }
            });
        }
    }

    /**
     * 获取全局静态注册的全部技能包列表
     */
    public List<Skill> getSkills() {
        return getSkills(null);
    }

    /**
     * 获取指定会话环境下可见的全部技能包列表（含项目专属及全局）
     */
    public List<Skill> getSkills(AgentContext context) {
        RuntimeContext runtimeCtx = context != null ? context.extra(RuntimeContext.class) : null;
        if (runtimeCtx != null && !runtimeCtx.getSkills().isEmpty()) {
            return runtimeCtx.getSkills();
        }
        return new ArrayList<>(globalSkillsCache.values());
    }

    /**
     * 获取全局指定名称的技能包实体数据
     */
    public Skill getSkill(String name) {
        return getSkill(name, null);
    }

    /**
     * 获取指定会话环境下可见的特定技能包数据
     */
    public Skill getSkill(String name, AgentContext context) {
        if (name == null) {
            return null;
        }
        RuntimeContext runtimeCtx = context != null ? context.extra(RuntimeContext.class) : null;
        if (runtimeCtx != null) {
            for (Skill s : runtimeCtx.getSkills()) {
                if (s.getName().equalsIgnoreCase(name)) {
                    return s;
                }
            }
        }
        return globalSkillsCache.get(name);
    }

    private void scanDirectory(Path baseDir, String source, Map<String, Skill> targetMap) {
        if (!Files.exists(baseDir) || !Files.isDirectory(baseDir)) {
            log.info("{} 技能目录不存在，跳过扫描: {}", source, baseDir.toAbsolutePath());
            return;
        }

        File[] subDirs = baseDir.toFile().listFiles(File::isDirectory);
        if (subDirs == null) {
            return;
        }

        for (File subDir : subDirs) {
            Path skillMdPath = subDir.toPath().resolve("SKILL.md");
            if (Files.exists(skillMdPath) && !Files.isDirectory(skillMdPath)) {
                try {
                    Skill skill = parseSkillFile(skillMdPath, source);
                    if (skill != null) {
                        targetMap.put(skill.getName(), skill);
                        log.info("成功加载技能包 [{}]，来源: {}", skill.getName(), source);
                    }
                } catch (Exception e) {
                    log.warn("解析技能包 SKILL.md 异常，路径: {}, 错误: {}", skillMdPath, e.getMessage());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Skill parseSkillFile(Path path, String source) throws IOException {
        String content = CharsetAwareFileKit.readString(path);
        if (!content.startsWith("---")) {
            return null;
        }
        int secondSep = content.indexOf("---", 3);
        if (secondSep == -1) {
            return null;
        }
        String yamlStr = content.substring(3, secondSep).trim();
        String markdownPrompt = content.substring(secondSep + 3).trim();

        Yaml yaml = new Yaml();
        Map<String, Object> meta = yaml.load(yamlStr);
        if (meta == null || !meta.containsKey("name")) {
            return null;
        }

        Skill skill = new Skill();
        skill.setName((String) meta.get("name"));
        skill.setDescription((String) meta.getOrDefault("description", ""));
        skill.setCommand((String) meta.get("command"));
        skill.setSystemPrompt(markdownPrompt);
        skill.setPath(path.toAbsolutePath().normalize().toString());
        skill.setSource(source);
        skill.setMode((String) meta.getOrDefault("mode", "inline"));

        if (meta.containsKey("tools")) {
            Object toolsObj = meta.get("tools");
            if (toolsObj instanceof List) {
                List<?> rawList = (List<?>) toolsObj;
                List<String> tools = new ArrayList<>();
                for (Object o : rawList) {
                    if (o != null) {
                        tools.add(String.valueOf(o));
                    }
                }
                skill.setTools(tools);
            }
        }

        return skill;
    }
}
