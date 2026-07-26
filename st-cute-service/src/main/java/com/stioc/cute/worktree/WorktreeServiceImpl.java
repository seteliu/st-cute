package com.stioc.cute.worktree;

import com.stioc.cute.worktree.access.FileDiffVo;
import com.stioc.cute.worktree.access.ActiveWorktreeVo;
import com.stioc.cute.worktree.access.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import com.stioc.cute.platform.contract.ContractFile;

/**
 * Git Worktree 物理隔离生命周期服务
 */
@Slf4j
@Service
public class WorktreeServiceImpl implements WorktreeService {

    /**
     * 校验 40 位 Git Commit 哈希值的正则表达式
     */
    private static final Pattern SHA_PATTERN = Pattern.compile("^[a-fA-F0-9]{40}$");

    /**
     * 递归向上寻找包含 .git 的仓库根目录
     */
    public File findRepoRoot(File startDir) {
        File cur = startDir;
        while (cur != null) {
            File gitIndicator = new File(cur, ".git");
            if (gitIndicator.exists()) {
                return cur;
            }
            cur = cur.getParentFile();
        }
        return null;
    }

    /**
     * 解析并返回实际的 git 数据目录
     */
    public File resolveGitDir(File repoRoot) {
        File gitIndicator = new File(repoRoot, ".git");
        if (!gitIndicator.exists()) {
            return null;
        }
        if (gitIndicator.isDirectory()) {
            return gitIndicator;
        }
        // .git 为文件，可能是 worktree 或者 submodule，格式为 "gitdir: /absolute/path"
        try (BufferedReader reader = new BufferedReader(new FileReader(gitIndicator))) {
            String line = reader.readLine();
            if (line != null && line.startsWith("gitdir:")) {
                String rawPath = line.substring(7).trim();
                File gitDir = new File(rawPath);
                if (!gitDir.isAbsolute()) {
                    gitDir = new File(repoRoot, rawPath).getAbsoluteFile();
                }
                return gitDir;
            }
        } catch (Exception e) {
            log.error("解析 .git 文件指针异常: {}", gitIndicator.getAbsolutePath(), e);
        }
        return null;
    }

    /**
     * 纯文件系统读取 HEAD 的 Commit SHA
     */
    public String readHeadCommit(File gitDir) {
        if (gitDir == null || !gitDir.exists()) {
            return "";
        }
        File headFile = new File(gitDir, "HEAD");
        if (!headFile.exists()) {
            return "";
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(headFile))) {
            String line = reader.readLine();
            if (line == null) {
                return "";
            }
            line = line.trim();
            if (line.startsWith("ref:")) {
                String refPath = line.substring(4).trim();
                return safeResolveRef(gitDir, refPath);
            } else if (SHA_PATTERN.matcher(line).matches()) {
                return line;
            }
        } catch (Exception e) {
            log.warn("纯文件读取 HEAD 发生异常: {}", headFile.getAbsolutePath(), e);
        }
        return "";
    }

    /**
     * 纯文件系统读取当前所在的分支名
     */
    public String readCurrentBranch(File gitDir) {
        if (gitDir == null || !gitDir.exists()) {
            return "";
        }
        File headFile = new File(gitDir, "HEAD");
        if (!headFile.exists()) {
            return "";
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(headFile))) {
            String line = reader.readLine();
            if (line != null) {
                line = line.trim();
                if (line.startsWith("ref:")) {
                    String refPath = line.substring(4).trim();
                    if (isValidRefName(refPath) && refPath.startsWith("refs/heads/")) {
                        return refPath.substring(11); // 提取出 master, main 等分支名
                    }
                }
            }
        } catch (Exception e) {
            log.warn("纯文件读取分支名异常: {}", headFile.getAbsolutePath(), e);
        }
        return "";
    }

    /**
     * 解析 ref 指向的 commit SHA
     */
    public String safeResolveRef(File gitDir, String refPath) {
        if (!isValidRefName(refPath)) {
            log.warn("拒绝解析不安全的 ref 名称: {}", refPath);
            return "";
        }

        // 1. 优先在当前 Worktree 专属 git 目录下查松散 ref
        File looseFile = new File(gitDir, refPath);
        if (looseFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(looseFile))) {
                String line = reader.readLine();
                if (line != null) {
                    line = line.trim();
                    if (SHA_PATTERN.matcher(line).matches()) {
                        return line;
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        }

        // 获取 commondir 共享目录
        File commonGitDir = getCommonGitDir(gitDir);

        // 2. 退查主仓 commondir 共享目录下的松散 ref
        if (!commonGitDir.equals(gitDir)) {
            File commonLooseFile = new File(commonGitDir, refPath);
            if (commonLooseFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(commonLooseFile))) {
                    String line = reader.readLine();
                    if (line != null) {
                        line = line.trim();
                        if (SHA_PATTERN.matcher(line).matches()) {
                            return line;
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }

        // 3. 扫描主仓 commondir 目录下的 packed-refs
        File packedFile = new File(commonGitDir, "packed-refs");
        if (packedFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(packedFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("^")) {
                        continue;
                    }
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        String sha = parts[0];
                        String name = parts[1];
                        if (name.equals(refPath) && SHA_PATTERN.matcher(sha).matches()) {
                            return sha;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("读取 packed-refs 异常: {}", packedFile.getAbsolutePath(), e);
            }
        }

        return "";
    }

    /**
     * 校验 ref 名的字符安全
     */
    public boolean isValidRefName(String ref) {
        if (ref == null || ref.isEmpty()) return false;
        if (ref.startsWith("-") || ref.startsWith("/")) return false;
        if (ref.contains("..")) return false;
        for (String segment : ref.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) return false;
        }
        return ref.matches("^[a-zA-Z0-9_\\-\\./]+$");
    }

    private File getCommonGitDir(File gitDir) {
        File commondirFile = new File(gitDir, "commondir");
        if (commondirFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(commondirFile))) {
                String line = reader.readLine();
                if (line != null) {
                    String rawPath = line.trim();
                    File commonDir = new File(rawPath);
                    if (!commonDir.isAbsolute()) {
                        commonDir = new File(gitDir, rawPath).getAbsoluteFile();
                    }
                    return commonDir;
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return gitDir;
    }

    /**
     * 创建隔离工作区
     */
    public Map<String, String> createWorktree(String projectBasePath, String slug, String baseCommit) throws Exception {
        if (!StringUtils.hasText(projectBasePath)) {
            throw new IllegalArgumentException("项目基准路径 projectBasePath 不能为空。");
        }
        File repoRoot = findRepoRoot(new File(projectBasePath).getAbsoluteFile());
        if (repoRoot == null) {
            throw new FileNotFoundException("未找到 Git 仓库根目录，无法建立 Worktree。项目路径: " + projectBasePath);
        }

        File projectDir = ContractFile.getProjectDir(repoRoot);
        if (projectDir == null) {
            throw new IllegalStateException("无法获取项目级 st-cute 目录，可能是 repoRoot 为空。");
        }
        File worktreeDir = new File(projectDir, "worktrees/" + slug).getAbsoluteFile();
        String branchName = "worktree-" + slug;

        // 快速恢复路径
        if (worktreeDir.exists() && new File(worktreeDir, ".git").exists()) {
            File subGitDir = resolveGitDir(worktreeDir);
            String currentHead = readHeadCommit(subGitDir);
            if (StringUtils.hasText(currentHead)) {
                log.info("发现已存在的工作区隔离目录 {}, 命中快速恢复路径！", worktreeDir.getAbsolutePath());
                Map<String, String> res = new HashMap<>();
                res.put("worktreePath", worktreeDir.getAbsolutePath());
                res.put("branch", branchName);
                res.put("head", currentHead);
                res.put("repoRoot", repoRoot.getAbsolutePath());
                return res;
            }
        }

        log.info("准备在后台创建 Git Worktree，目录: {}, 基准: {}", worktreeDir.getAbsolutePath(), baseCommit);

        // 如果基准为空，默认以 HEAD 启动
        String base = StringUtils.hasText(baseCommit) ? baseCommit : "HEAD";

        // 执行 git worktree add
        List<String> cmd = new ArrayList<>(List.of("git", "worktree", "add", "-B", branchName, worktreeDir.getAbsolutePath(), base));
        runSystemCommand(repoRoot, cmd);

        File subGitDir = resolveGitDir(worktreeDir);
        String head = readHeadCommit(subGitDir);

        // 触发 best-effort 初始化
        initializeWorktreeEnvironment(repoRoot, worktreeDir, branchName);

        Map<String, String> res = new HashMap<>();
        res.put("worktreePath", worktreeDir.getAbsolutePath());
        res.put("branch", branchName);
        res.put("head", head);
        res.put("repoRoot", repoRoot.getAbsolutePath());
        return res;
    }

    /**
     * 退出并幂等清理工作区
     */
    public void cleanupWorktree(String projectBasePath, String worktreePath, String branchName) {
        log.info("开始清理 Worktree，目录: {}, 分支: {}", worktreePath, branchName);
        File repoRoot = null;
        if (StringUtils.hasText(projectBasePath)) {
            repoRoot = findRepoRoot(new File(projectBasePath).getAbsoluteFile());
        }
        if (repoRoot == null) {
            repoRoot = findRepoRoot(new File("."));
        }
        if (repoRoot == null) {
            log.warn("无法探测仓库根，直接退出清理");
            return;
        }

        // 1. 删除 worktree 目录映射
        try {
            List<String> cmd = List.of("git", "worktree", "remove", "--force", worktreePath);
            runSystemCommand(repoRoot, cmd);
            log.info("成功执行 git worktree remove --force");
        } catch (Exception e) {
            log.warn("执行 git worktree remove 报错（此步容错）: {}", e.getMessage());
            // 兜底硬删除文件系统
            deleteDirBestEffort(new File(worktreePath));
        }

        // 2. 删除分支
        try {
            List<String> cmd = List.of("git", "branch", "-D", branchName);
            runSystemCommand(repoRoot, cmd);
            log.info("成功执行 git branch -D {}", branchName);
        } catch (Exception e) {
            log.warn("执行 git branch -D 报错（此步容错）: {}", e.getMessage());
        }
    }

    /**
     * 变更检测
     */
    public boolean detectChanges(File worktreeDir, String baseCommit) {
        try {
            // 1. 检验工作区状态
            List<String> cmdStatus = List.of("git", "status", "--porcelain");
            String statusOut = runSystemCommandWithOutput(worktreeDir, cmdStatus);
            if (StringUtils.hasText(statusOut.trim())) {
                log.info("检测到隔离工作区中有未提交修改");
                return true;
            }

            // 2. 校验 HEAD SHA
            if (StringUtils.hasText(baseCommit)) {
                File subGitDir = resolveGitDir(worktreeDir);
                String currentHead = readHeadCommit(subGitDir);

                // 为了比对，将 baseCommit 进行 Git 解析，以便支持 'HEAD', 'master' 等符号
                String resolvedBase = runSystemCommandWithOutput(worktreeDir, List.of("git", "rev-parse", baseCommit)).trim();

                if (!currentHead.equalsIgnoreCase(resolvedBase)) {
                    log.info("检测到 HEAD 已前进，当前 SHA: {}, 基准: {}", currentHead, resolvedBase);
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("变更探测中捕获异常，为安全起见，统一判为[有变更]: {}", e.getMessage());
            return true; // Fail-closed
        }
        return false;
    }

    /**
     * 辅助拼接 Worktree 系统提示词
     */
    public String buildWorktreePromptHint(String worktreePath, String branch, String head) {
        return String.format("""
                <worktree-context>
                【隔离副本提示】
                你目前正处于物理隔离的工作副本空间内运行：
                - 工作副本路径：%s
                - 当前隔离分支：%s
                - 当前基准 commit SHA：%s

                你在本环境中调用的任何只读/修改文件工具，均只在此副本生效，与主仓完全隔离。
                当你结束开发并需要同步时，请使用 exit_worktree 退出并汇报。
                </worktree-context>
                """, worktreePath, branch, head);
    }

    /**
     * Best-Effort 环境拷贝与初始化
     */
    private void initializeWorktreeEnvironment(File repoRoot, File worktreeDir, String branchName) {
        // Step A: 复制项目级配置目录到 Worktree 里同名位置（共存复制）
        try {
            for (File sourceConfig : ContractFile.getProjectDirs(repoRoot)) {
                if (sourceConfig != null && sourceConfig.exists()) {
                    File targetConfig = new File(worktreeDir, sourceConfig.getName());
                    // 递归复制，但跳过自身 worktrees 目录，以防死循环！
                    copyDirectoryFiltered(sourceConfig, targetConfig, "worktrees");
                    log.info("成功复制项目级配置 {} 目录到 Worktree", sourceConfig.getName());
                }
            }
        } catch (Exception e) {
            log.warn("环境初始化 A 步骤复制配置失败（容错）: {}", e.getMessage());
        }

        // Step B: 配置 Hooks 路径
        try {
            File projectDir = ContractFile.getProjectDir(repoRoot);
            if (projectDir != null) {
                File sourceHooks = new File(projectDir, "hooks");
                if (!sourceHooks.exists()) {
                    sourceHooks = new File(repoRoot, ".git/hooks");
                }
                if (sourceHooks.exists()) {
                    List<String> cmd = List.of("git", "config", "core.hooksPath", sourceHooks.getAbsolutePath());
                    runSystemCommand(worktreeDir, cmd);
                    log.info("成功配置 Worktree 的 hooks 路径: {}", sourceHooks.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            log.warn("环境初始化 B 步骤设置 Hooks 路径失败（容错）: {}", e.getMessage());
        }

        // Step C: 软链接大依赖目录 (node_modules 等)
        try {
            String[] dependencyDirs = {"node_modules", "target", ".pnpm-store"};
            for (String dep : dependencyDirs) {
                File sourceDep = new File(repoRoot, dep);
                File targetDep = new File(worktreeDir, dep);
                if (sourceDep.exists() && !targetDep.exists()) {
                    createSymbolicLinkOrJunction(sourceDep, targetDep);
                }
            }
        } catch (Exception e) {
            log.warn("环境初始化 C 步骤软链依赖失败（容错）: {}", e.getMessage());
        }

        // Step D: 读取 .worktreeinclude 复制忽略文件
        try {
            File includeFile = new File(repoRoot, ".worktreeinclude");
            if (includeFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(includeFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#") || line.contains("..")) {
                            continue;
                        }
                        File src = new File(repoRoot, line);
                        File dest = new File(worktreeDir, line);
                        if (src.exists()) {
                            if (src.isDirectory()) {
                                copyDirectoryFiltered(src, dest, null);
                            } else {
                                Files.createDirectories(dest.getParentFile().toPath());
                                Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            }
                            log.info("从 .worktreeinclude 成功拷贝文件: {}", line);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("环境初始化 D 步骤拷贝 include 文件失败（容错）: {}", e.getMessage());
        }
    }

    private void createSymbolicLinkOrJunction(File source, File target) {
        try {
            // 优先尝试标准 Java 符号链接
            Files.createSymbolicLink(target.toPath(), source.toPath());
            log.info("建立软链接成功: {} -> {}", target.getName(), source.getName());
        } catch (Exception e) {
            // Windows 下无开发者模式/管理员权限时，利用 cmd mklink /j 建立目录联接
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "mklink", "/j", target.getAbsolutePath(), source.getAbsolutePath());
                    Process p = pb.start();
                    p.waitFor();
                    log.info("Windows mklink /j 联接成功: {}", target.getName());
                } catch (Exception ex) {
                    log.warn("Windows Junction 联接失败: {}", ex.getMessage());
                }
            } else {
                log.warn("创建软链接失败: {}", e.getMessage());
            }
        }
    }

    private void copyDirectoryFiltered(File source, File dest, String excludeFolder) throws IOException {
        if (source.getName().equals(excludeFolder)) {
            return;
        }
        if (source.isDirectory()) {
            if (!dest.exists()) {
                dest.mkdirs();
            }
            String[] files = source.list();
            if (files != null) {
                for (String file : files) {
                    copyDirectoryFiltered(new File(source, file), new File(dest, file), excludeFolder);
                }
            }
        } else {
            Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteDirBestEffort(File file) {
        if (file.isDirectory()) {
            File[] list = file.listFiles();
            if (list != null) {
                for (File f : list) {
                    deleteDirBestEffort(f);
                }
            }
        }
        file.delete();
    }

    private void runSystemCommand(File cwd, List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 必须在 waitFor 之前（或同时）读取并消费输入流，避免缓冲区满导致子进程挂起
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new TimeoutException("执行系统命令超时: " + String.join(" ", cmd));
        }
        if (process.exitValue() != 0) {
            throw new IOException("系统命令返回非 0 退出码: " + process.exitValue() + "\n错误详情:\n" + output.toString());
        }
    }

    private String runSystemCommandWithOutput(File cwd, List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 必须在 waitFor 之前（或同时）读取并消费输入流，避免缓冲区满导致子进程挂起
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new TimeoutException("执行系统命令超时: " + String.join(" ", cmd));
        }
        if (process.exitValue() != 0) {
            throw new IOException("系统命令返回非 0 退出码: " + process.exitValue() + "\n错误详情:\n" + output.toString());
        }
        return output.toString();
    }

    public List<FileDiffVo> getWorktreeDiff(String projectBasePath, String branchName, String baseCommit) throws Exception {
        File repoRoot = null;
        if (StringUtils.hasText(projectBasePath)) {
            repoRoot = findRepoRoot(new File(projectBasePath).getAbsoluteFile());
        }
        if (repoRoot == null) {
            repoRoot = findRepoRoot(new File("."));
        }
        if (repoRoot == null) return Collections.emptyList();

        String base = StringUtils.hasText(baseCommit) ? baseCommit : "HEAD";
        String resolvedBase = "HEAD";
        try {
            resolvedBase = runSystemCommandWithOutput(repoRoot, List.of("git", "rev-parse", base)).trim();
        } catch (Exception e) {
            log.warn("解析 baseCommit 失败，fallback 至 HEAD: {}", e.getMessage());
        }

        // 优先在对应分支的物理 worktree 目录下比对（以包含未提交的本地更改）
        List<ActiveWorktreeVo> activeWorktrees = getActiveWorktrees(projectBasePath);
        String targetWorktreePath = null;
        if (activeWorktrees != null) {
            for (ActiveWorktreeVo wt : activeWorktrees) {
                if (branchName.equals(wt.getBranch())) {
                    targetWorktreePath = wt.getPath();
                    break;
                }
            }
        }

        String rawDiff = "";
        if (StringUtils.hasText(targetWorktreePath)) {
            File targetDir = new File(targetWorktreePath);
            if (targetDir.exists() && targetDir.isDirectory()) {
                try {
                    List<String> cmd = List.of("git", "diff", resolvedBase);
                    rawDiff = runSystemCommandWithOutput(targetDir, cmd);
                } catch (Exception e) {
                    log.warn("获取物理隔离工作区 diff 失败, path={}: {}", targetWorktreePath, e.getMessage());
                }
            }
        }

        // 如果没有匹配到物理工作区，或者获取失败，则 fallback 到原来的分支指针比对逻辑
        if (!StringUtils.hasText(rawDiff)) {
            try {
                List<String> cmd = List.of("git", "diff", resolvedBase, branchName);
                rawDiff = runSystemCommandWithOutput(repoRoot, cmd);
            } catch (Exception e) {
                log.warn("退化比较分支指针失败: {}", e.getMessage());
            }
        }

        List<FileDiffVo> diffs = new ArrayList<>();
        if (StringUtils.hasText(rawDiff)) {
            String[] parts = rawDiff.split("(?=diff --git a/)");
            for (String part : parts) {
                if (part.trim().isEmpty() || !part.contains("diff --git")) continue;

                String filename = null;
                try (BufferedReader reader = new BufferedReader(new StringReader(part))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("diff --git")) {
                            String[] tokens = line.split("\\s+");
                            if (tokens.length >= 4) {
                                String bFile = tokens[3];
                                if (bFile.startsWith("b/")) {
                                    filename = bFile.substring(2);
                                } else {
                                    filename = bFile;
                                }
                                break;
                            }
                        }
                    }
                } catch (Exception ex) {
                    // ignore
                }

                if (filename == null || "unknown".equals(filename)) {
                    continue;
                }

                String changeType = "MODIFY";
                if (part.contains("new file mode")) {
                    changeType = "ADD";
                } else if (part.contains("deleted file mode")) {
                    changeType = "DELETE";
                }

                diffs.add(FileDiffVo.builder()
                        .filename(filename)
                        .diffContent(part)
                        .changeType(changeType)
                        .build());
            }
        }

        // 自动探测并追加 Untracked 文件的虚拟 ADD Diff 记录（解决新创建的本地文件普通改时在版本中看不见的痛点）
        try {
            File workDir = StringUtils.hasText(targetWorktreePath) ? new File(targetWorktreePath) : repoRoot;
            if (workDir != null && workDir.exists() && workDir.isDirectory()) {
                List<String> cmd = List.of("git", "ls-files", "--others", "--exclude-standard");
                String untrackedOut = runSystemCommandWithOutput(workDir, cmd);
                if (StringUtils.hasText(untrackedOut)) {
                    String[] lines = untrackedOut.split("\\r?\\n");
                    
                    // 允许解析正文的常用文本文件后缀扩展名白名单
                    Set<String> textExtensions = Set.of(
                            "java", "xml", "yml", "yaml", "properties", "json", "vue", "ts", "js", "html", "css", "md", "txt", "sql"
                    );

                    int processedCount = 0;
                    for (String line : lines) {
                        line = line.trim();
                        // 过滤 st-cute 自身缓存与 worktree 目录
                        if (line.isEmpty() || line.startsWith(".st-cute") || line.startsWith(".agents") || line.contains("worktrees")) {
                            continue;
                        }
                        File untrackedFile = new File(workDir, line);
                        if (untrackedFile.exists() && untrackedFile.isFile()) {
                            processedCount++;
                            String virtualDiff;
                            
                            // 熔断与防大文件保护：前 20 个文件、大小在 500KB 以内、且属于文本格式时，才读取内容拼装 Diff
                            boolean shouldReadContent = processedCount <= 20 
                                    && untrackedFile.length() <= 500 * 1024
                                    && textExtensions.contains(getFileExtension(untrackedFile.getName()));
                            
                            if (shouldReadContent) {
                                String fileContent = Files.readString(untrackedFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                                virtualDiff = buildVirtualAddDiff(line, fileContent);
                            } else {
                                String reason;
                                if (processedCount > 20) {
                                    reason = "工作区未跟踪文件较多，已启动性能保护。您可以通过 git add 纳管已完成的文件，以便自动解锁后续文件的 Diff 详情。";
                                } else if (untrackedFile.length() > 500 * 1024) {
                                    reason = "此文件体积超限 (大小: " + (untrackedFile.length() / 1024) + " KB)，跳过全文展示。";
                                } else {
                                    reason = "此文件非代码或文本格式，跳过全文展示。";
                                }
                                virtualDiff = buildVirtualPlaceholderDiff(line, reason);
                            }

                            diffs.add(FileDiffVo.builder()
                                    .filename(line)
                                    .diffContent(virtualDiff)
                                    .changeType("ADD")
                                    .build());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("追加 untracked 文件的虚拟 diff 记录失败（容错）: {}", e.getMessage());
        }

        return diffs;
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) return "";
        int lastIdx = fileName.lastIndexOf('.');
        if (lastIdx == -1 || lastIdx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastIdx + 1).toLowerCase();
    }

    private String buildVirtualAddDiff(String filename, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(filename).append(" b/").append(filename).append("\n");
        sb.append("new file mode 100644\n");
        sb.append("--- /dev/null\n");
        sb.append("+++ b/").append(filename).append("\n");

        if (content != null && !content.isEmpty()) {
            String[] lines = content.split("\\r?\\n", -1);
            sb.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
            for (String line : lines) {
                sb.append("+").append(line).append("\n");
            }
        } else {
            sb.append("@@ -0,0 +0,0 @@\n");
        }
        return sb.toString();
    }

    private String buildVirtualPlaceholderDiff(String filename, String hintMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(filename).append(" b/").append(filename).append("\n");
        sb.append("new file mode 100644\n");
        sb.append("--- /dev/null\n");
        sb.append("+++ b/").append(filename).append("\n");
        sb.append("@@ -0,0 +1,1 @@\n");
        sb.append("+ [").append(hintMessage).append("]\n");
        return sb.toString();
    }

    public List<ActiveWorktreeVo> getActiveWorktrees(String projectBasePath) {
        List<ActiveWorktreeVo> list = new ArrayList<>();
        File repoRoot = null;
        if (StringUtils.hasText(projectBasePath)) {
            repoRoot = findRepoRoot(new File(projectBasePath).getAbsoluteFile());
        }
        if (repoRoot == null) {
            repoRoot = findRepoRoot(new File("."));
        }
        if (repoRoot == null) return list;

        try {
            String out = runSystemCommandWithOutput(repoRoot, List.of("git", "worktree", "list"));
            if (StringUtils.hasText(out)) {
                String[] lines = out.split("\\r?\\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] tokens = line.split("\\s+");
                    if (tokens.length >= 3) {
                        String path = tokens[0];
                        String branchPart = tokens[2];
                        if (branchPart.startsWith("[") && branchPart.endsWith("]")) {
                            String branch = branchPart.substring(1, branchPart.length() - 1);
                            list.add(new ActiveWorktreeVo(path, branch));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("获取活跃 worktree 列表失败", e);
        }
        return list;
    }

    private static class TimeoutException extends Exception {
        public TimeoutException(String msg) {
            super(msg);
        }
    }
}
