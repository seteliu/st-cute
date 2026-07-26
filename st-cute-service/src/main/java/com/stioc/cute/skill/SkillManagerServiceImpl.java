package com.stioc.cute.skill;

import com.stioc.cute.skill.access.Skill;
import com.stioc.cute.skill.access.SkillManagerService;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.security.access.WorkspacePathResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.stioc.cute.platform.contract.ContractFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.Resource;

/**
 * 智能体动态 Skill 技能包热扫描与管理中心。
 * 负责扫描全局级与项目级目录下的技能定义、热重载解析，并往 SlashCommandRegistry 热挂载命令。
 */
@Slf4j
@Service
public class SkillManagerServiceImpl implements SkillManagerService {

    @Resource
    private WorkspacePathResolver workspacePathResolver;

    /**
     * 内存中维护的已加载项目级专属技能缓存（Key 为项目物理根路径，Value 为技能名对应的 Skill 实例映射）
     */
    private final Map<String, Map<String, Skill>> projectSkills = new ConcurrentHashMap<>();

    private String resolveProjectPath(AgentContext context) {
        if (context != null) {
            try {
                return workspacePathResolver.getProjectBasePath(context);
            } catch (Exception e) {
                // ignore
            }
        }
        return "";
    }

    /**
     * 为特定的会话上下文扫描并装填专属技能
     */
    public synchronized void loadProjectSkills(AgentContext context, String projectBasePath) {
        if (context == null) return;

        context.getSkills().clear();

        // 1. 惰性装填全局公共技能
        Map<String, Skill> globalSkills = projectSkills.computeIfAbsent("", k -> {
            Map<String, Skill> temp = new ConcurrentHashMap<>();
            File globalSkillsDir = ContractFile.getGlobalSkillsDir();
            if (globalSkillsDir != null && globalSkillsDir.exists() && globalSkillsDir.isDirectory()) {
                scanDirectory(globalSkillsDir.toPath(), "GLOBAL", temp);
            }
            return temp;
        });
        context.getSkills().addAll(globalSkills.values());

        // 2. 装填并覆盖项目专属技能
        if (StringUtils.hasText(projectBasePath)) {
            ContractFile.forEachProjectFile(projectBasePath, ContractFile.DIR_SKILLS, projectSkillsDir -> {
                if (projectSkillsDir.isDirectory()) {
                    Map<String, Skill> tempMap = new LinkedHashMap<>();
                    scanDirectory(projectSkillsDir.toPath(), "PROJECT", tempMap);
                    for (Skill ps : tempMap.values()) {
                        // 若存在同名，覆盖之
                        context.getSkills().removeIf(s -> s.getName().equalsIgnoreCase(ps.getName()));
                        context.getSkills().add(ps);
                    }
                    log.info("会话 {} 从 {} 成功装载了 {} 个项目专属技能", context.getCid(), projectSkillsDir.getParentFile().getName(), tempMap.size());
                }
            });
        }
    }

    /**
     * 获取所有可用技能列表
     */
    public List<Skill> getSkills() {
        return getSkills(null);
    }

    public List<Skill> getSkills(AgentContext context) {
        if (context != null && !context.getSkills().isEmpty()) {
            return context.getSkills();
        }
        Map<String, Skill> globalSkills = projectSkills.get("");
        return globalSkills != null ? new ArrayList<>(globalSkills.values()) : new ArrayList<>();
    }

    /**
     * 依据技能名称获取技能
     */
    public Skill getSkill(String name) {
        return getSkill(name, null);
    }

    public Skill getSkill(String name, AgentContext context) {
        if (name == null) return null;
        if (context != null) {
            for (Skill s : context.getSkills()) {
                if (s.getName().equalsIgnoreCase(name)) {
                    return s;
                }
            }
        }
        Map<String, Skill> globalSkills = projectSkills.get("");
        return globalSkills != null ? globalSkills.get(name) : null;
    }

    /**
     * 直接热更新物理文件中的 Prompt 内容，并同步刷新内存缓存
     */
    public synchronized void updateSkillPrompt(String name, String newPrompt) throws IOException {
        Skill skill = getSkill(name);
        if (skill == null) {
            throw new IllegalArgumentException("未找到技能包: " + name);
        }

        Path path = Paths.get(skill.getPath());
        if (!Files.exists(path)) {
            throw new IOException("技能包配置文件已不存在于磁盘: " + skill.getPath());
        }

        String rawContent = Files.readString(path, StandardCharsets.UTF_8);
        int secondSep = rawContent.indexOf("---", 3);
        if (secondSep == -1) {
            throw new IllegalStateException("SKILL.md YAML Frontmatter 格式异常，无法解析覆盖");
        }

        // 保留原 frontmatter
        String frontmatter = rawContent.substring(0, secondSep + 3);
        String finalContent = frontmatter + "\n\n" + newPrompt;

        Files.writeString(path, finalContent, StandardCharsets.UTF_8);
        skill.setSystemPrompt(newPrompt);
        log.info("技能 {} Prompt 修改写盘并热更新完成", name);
    }

    private void scanDirectory(Path baseDir, String source, Map<String, Skill> targetMap) {
        if (!Files.exists(baseDir) || !Files.isDirectory(baseDir)) {
            log.info("{} 技能目录不存在，跳过扫描: {}", source, baseDir.toAbsolutePath());
            return;
        }

        File[] subDirs = baseDir.toFile().listFiles(File::isDirectory);
        if (subDirs == null) return;

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
        String content = Files.readString(path, StandardCharsets.UTF_8);
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
