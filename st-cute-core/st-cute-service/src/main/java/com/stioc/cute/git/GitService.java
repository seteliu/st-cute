package com.stioc.cute.git;

import com.stioc.cute.git.types.FileDiffVo;
import com.stioc.cute.git.types.GitBranchVo;
import com.stioc.cute.git.types.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import com.stioc.cute.platform.common.CharsetAwareFileKit;

/**
 * Git 状态查询服务。
 * <p>
 * 物理隔离副本的创建/退出/变更检测机制（会话绑定 workspaceId 语义化后废弃）已删除，
 * 仅保留只读的 git 查询能力：供前端 /api/git/list|diff 展示分支与文件变动。
 * </p>
 */
@Slf4j
@Service
public class GitService {

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
        if (ref == null || ref.isEmpty()) {
            return false;
        }
        if (ref.startsWith("-") || ref.startsWith("/")) {
            return false;
        }
        if (ref.contains("..")) {
            return false;
        }
        for (String segment : ref.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                return false;
            }
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
     * 获取指定分支相对于基础 Commit 节点的完整物理差异
     */
    public List<FileDiffVo> getBranchDiff(String projectBasePath, String branchName, String baseCommit) throws Exception {
        File repoRoot = null;
        if (StringUtils.hasText(projectBasePath)) {
            repoRoot = findRepoRoot(new File(projectBasePath).getAbsoluteFile());
        }
        if (repoRoot == null) {
            repoRoot = findRepoRoot(new File("."));
        }
        if (repoRoot == null) {
            return Collections.emptyList();
        }

        String base = StringUtils.hasText(baseCommit) ? baseCommit : "HEAD";
        String resolvedBase = "HEAD";
        try {
            resolvedBase = runSystemCommandWithOutput(repoRoot, List.of("git", "rev-parse", base)).trim();
        } catch (Exception e) {
            log.warn("解析 baseCommit 失败，fallback 至 HEAD: {}", e.getMessage());
        }

        // 优先在对应分支的工作区目录下比对（以包含未提交的本地更改）
        List<GitBranchVo> branches = getBranches(projectBasePath);
        String targetBranchPath = null;
        if (branches != null) {
            for (GitBranchVo b : branches) {
                if (branchName.equals(b.getBranch())) {
                    targetBranchPath = b.getPath();
                    break;
                }
            }
        }

        String rawDiff = "";
        if (StringUtils.hasText(targetBranchPath)) {
            File targetDir = new File(targetBranchPath);
            if (targetDir.exists() && targetDir.isDirectory()) {
                try {
                    List<String> cmd = List.of("git", "diff", resolvedBase);
                    rawDiff = runSystemCommandWithOutput(targetDir, cmd);
                } catch (Exception e) {
                    log.warn("获取分支工作区 diff 失败, path={}: {}", targetBranchPath, e.getMessage());
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
                if (part.trim().isEmpty() || !part.contains("diff --git")) {
                    continue;
                }

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
            File workDir = StringUtils.hasText(targetBranchPath) ? new File(targetBranchPath) : repoRoot;
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
                                String fileContent = CharsetAwareFileKit.readString(untrackedFile.toPath());
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
        if (fileName == null) {
            return "";
        }
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

    /**
     * 执行系统命令并返回输出（供 git 查询族使用）
     */
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

    public List<GitBranchVo> getBranches(String projectBasePath) {
        List<GitBranchVo> list = new ArrayList<>();
        File repoRoot = null;
        if (StringUtils.hasText(projectBasePath)) {
            repoRoot = findRepoRoot(new File(projectBasePath).getAbsoluteFile());
        }
        if (repoRoot == null) {
            repoRoot = findRepoRoot(new File("."));
        }
        if (repoRoot == null) {
            return list;
        }

        try {
            // 两段式查询：
            // 1) git branch --format 拿全量本地分支（git worktree list 只输出被物理工作区占用的分支，
            //    单工作区仓库只会输出当前分支，无法覆盖全量分支）
            //    %(HEAD)=当前分支标记(*)、%(refname:short)=短分支名
            // 2) git worktree list --porcelain 拿分支→物理工作区路径映射（多工作区场景下 diff 可进入
            //    对应工作区目录执行以包含未提交改动；老版本 git 不支持 %(worktreedir) 字段，故单独查询）
            List<String> cmd = List.of("git", "branch", "--format=%(HEAD)|%(refname:short)");
            String out = runSystemCommandWithOutput(repoRoot, cmd);
            if (StringUtils.hasText(out)) {
                // 先构建工作区路径映射：分支短名 → 工作区绝对路径
                Map<String, String> worktreeDirMap = buildWorktreeDirMap(repoRoot);

                String[] lines = out.split("\\r?\\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] tokens = line.split("\\|");
                    if (tokens.length < 2) {
                        continue;
                    }
                    // HEAD 标记（* 表示当前检出分支，空格表示普通分支）
                    boolean current = tokens[0].trim().equals("*");
                    String branch = tokens[1].trim();
                    if (branch.isEmpty()) {
                        continue;
                    }
                    // 分支工作区路径：有物理工作区取实际路径，否则回退仓库根目录（diff 命令的执行目录）
                    String path = worktreeDirMap.getOrDefault(branch, repoRoot.getAbsolutePath());
                    list.add(new GitBranchVo(path, branch, current));
                }
            }
        } catch (Exception e) {
            log.error("获取分支列表失败", e);
        }
        return list;
    }

    /**
     * 解析 git worktree list --porcelain 输出，构建「分支短名 → 工作区绝对路径」映射。
     * porcelain 输出形如：
     * <pre>
     * worktree /path/to/main
     * HEAD abc123...
     * branch refs/heads/master
     * (空行分隔下一个 worktree)
     * </pre>
     */
    private Map<String, String> buildWorktreeDirMap(File repoRoot) {
        Map<String, String> map = new HashMap<>();
        try {
            String out = runSystemCommandWithOutput(repoRoot, List.of("git", "worktree", "list", "--porcelain"));
            if (!StringUtils.hasText(out)) {
                return map;
            }
            String currentPath = null;
            for (String rawLine : out.split("\\r?\\n")) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    currentPath = null;
                    continue;
                }
                if (line.startsWith("worktree ")) {
                    currentPath = line.substring("worktree ".length()).trim();
                } else if (line.startsWith("branch refs/heads/") && currentPath != null) {
                    String branchShort = line.substring("branch refs/heads/".length()).trim();
                    map.put(branchShort, currentPath);
                    currentPath = null;
                }
            }
        } catch (Exception e) {
            // 工作区映射查询失败不影响分支列表主流程（getBranchDiff 有 fallback 到分支指针比对）
            log.warn("解析 worktree 工作区映射失败（容错）: {}", e.getMessage());
        }
        return map;
    }

    private static class TimeoutException extends Exception {
        public TimeoutException(String msg) {
            super(msg);
        }
    }
}
