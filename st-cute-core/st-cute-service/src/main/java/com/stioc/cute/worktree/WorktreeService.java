package com.stioc.cute.worktree;

import com.stioc.cute.worktree.types.FileDiffVo;
import com.stioc.cute.worktree.types.ActiveWorktreeVo;
import com.stioc.cute.worktree.types.*;

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
 * Git Worktree 状态查询服务。
 * <p>
 * 物理隔离副本的创建/退出/变更检测机制已删除（会话绑定 workspaceId 语义化后废弃），
 * 仅保留只读的 git 查询能力：供前端 /api/worktree/list|diff 展示分支与文件变动。
 * </p>
 */
@Slf4j
@Service
public class WorktreeService {

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
     * 获取指定隔离分支相对于基础 Commit 节点的完整物理差异
     */
    public List<FileDiffVo> getWorktreeDiff(String projectBasePath, String branchName, String baseCommit) throws Exception {
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

    public List<ActiveWorktreeVo> getActiveWorktrees(String projectBasePath) {
        List<ActiveWorktreeVo> list = new ArrayList<>();
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
            String out = runSystemCommandWithOutput(repoRoot, List.of("git", "worktree", "list"));
            if (StringUtils.hasText(out)) {
                String[] lines = out.split("\\r?\\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
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
