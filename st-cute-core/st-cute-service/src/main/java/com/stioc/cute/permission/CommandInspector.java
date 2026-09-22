package com.stioc.cute.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 命令安全检查器：从命令文本中识别危险形态与破坏性删除目标。
 * <p>
 * 自 {@link PermissionService} 拆出的纯函数组件（零状态、零依赖、无 Spring 注解）：
 * 只做命令文本的词法分析与形态判定，不参与裁决决策——是否拦截由调用方按裁决链决定，
 * 本类只回答「这条命令是否命中某条危险形态」与「删除目标是否是破坏性路径」。
 * </p>
 * <p>
 * 设计原则：判据优先取「目标路径」而非「命令形态」。形如 rm / chmod / chown 这类
 * 「危险与否完全取决于目标路径」的命令不做形态一刀切——沙箱内的常规清理（rm -rf dist、
 * rm -rf node_modules）应放行交给审批矩阵裁决，避免正常运维被无差别误拦。
 * </p>
 */
public final class CommandInspector {

    /**
     * 危险命令形态黑名单：命令形态本身即具破坏性、无法靠路径沙箱兜底的场景。
     * <p>
     * 收录原则是「是否危险与目标路径无关」；形如 rm / chmod / chown 这类
     * 「危险与否完全取决于目标路径」的命令不在此列：其沙箱外访问由路径沙箱层拦截，
     * 沙箱内的正常操作则交由审批矩阵裁决，不做形态一刀切，避免正常运维被无差别误拦。
     * </p>
     */
    private static final List<DangerousCommandPattern> DANGEROUS_COMMAND_PATTERNS = List.of(
            new DangerousCommandPattern(Pattern.compile("mkfs(\\..*)?\\s+.*", Pattern.CASE_INSENSITIVE),
                    "mkfs 会格式化磁盘分区，造成不可逆的数据销毁"),
            new DangerousCommandPattern(Pattern.compile("dd\\s+.*if=.*", Pattern.CASE_INSENSITIVE),
                    "dd 可向裸设备或分区直接写入，存在不可逆的数据销毁风险"),
            new DangerousCommandPattern(Pattern.compile("(curl|wget)\\s+.*\\|\\s*(bash|sh|zsh|python3?|perl).*", Pattern.CASE_INSENSITIVE),
                    "将网络下载内容直接管道给解释器执行，其真实行为无法审计"),
            // 强制推送会覆盖远端提交历史：(?![-\w]) 用于排除 --force-with-lease —— 该写法会在
            // 覆盖前校验远端是否已被他人更新，是官方推荐的更安全替代，不应与 --force 一视同仁
            new DangerousCommandPattern(Pattern.compile("git\\s+push\\s+.*(--force(?![\\-\\w])|-f)(\\s|$).*", Pattern.CASE_INSENSITIVE),
                    "git push 强制推送会覆盖远端提交历史，可能导致他人提交永久丢失（如需安全强推请用 --force-with-lease）"),
            new DangerousCommandPattern(Pattern.compile("git\\s+reset\\s+--hard.*", Pattern.CASE_INSENSITIVE),
                    "git reset --hard 会丢弃工作区与暂存区的全部未提交改动"),
            new DangerousCommandPattern(Pattern.compile("git\\s+clean\\s+(-[a-zA-Z]*f[a-zA-Z]*|.*--force).*", Pattern.CASE_INSENSITIVE),
                    "git clean 带 -f/--force 会永久删除未跟踪文件，删除后无法通过 Git 恢复"),
            // fork bomb 形态为 ":(){ :|:& };:"，递归自调用部分位于串中而非串尾，
            // 故首尾均需 .* 兜住，不能用 ^:...:&$ 形态的锚定写法。
            // 但要收敛误报面：原写法 ".*:.*\|.*:&.*" 命中任何同时含 : 与 :& 的字符串
            // （如含 URL 与 shell 重定向的普通命令），故要求函数定义特征 "()" 与
            // 递归调用特征 "|:" 同时出现，才判定为 fork bomb
            new DangerousCommandPattern(Pattern.compile(".*\\(\\)\\s*\\{.*\\|.*:&.*", Pattern.CASE_INSENSITIVE),
                    "疑似 fork bomb，会耗尽系统进程资源")
    );

    /**
     * 破坏性删除目标的精确匹配集合：系统根及其通配写法
     */
    private static final Set<String> DESTRUCTIVE_TARGETS = Set.of(
            "/", "/*"
    );

    /**
     * 主目录展开前缀集合：以这些前缀开头的删除目标一律按破坏性处理。
     * <p>
     * 采用前缀而非精确匹配的原因：{@code ~/Documents} 这类路径会被路径解析器
     * 当作项目内相对路径（解析为 {@code {项目根}/~/Documents}）而误判为沙箱内合法，
     * 但 shell 实际展开后指向真实的主目录，存在真实的数据丢失风险。
     * 因此凡是以主目录展开语义开头的目标，均在其未被展开的形态下拦截。
     * </p>
     */
    private static final Set<String> HOME_EXPANSION_PREFIXES = Set.of(
            "~", "$HOME", "${HOME}", "%USERPROFILE%", "%HOMEDRIVE%%HOMEPATH%"
    );

    /**
     * 驱动器根路径形态（如 C:\、C:/、D:），用于识别整盘级破坏性删除目标
     */
    private static final Pattern DRIVE_ROOT_PATTERN = Pattern.compile("(?i)^[a-z]:[\\\\/]?$");

    /**
     * 删除类程序名集合（跨平台）：仅这些程序参与「破坏性删除目标」判定，
     * 避免把 cat、echo 等程序路径参数里的 "/" 误判为删除目标
     */
    private static final Set<String> DELETE_PROGRAMS = Set.of(
            "rm", "rmdir", "rd", "del", "erase"
    );

    /**
     * 命令分隔符集合：用于在链式命令中切分出各个独立的命令位置
     */
    private static final Set<String> COMMAND_SEPARATORS = Set.of(
            "&&", "||", ";", "|", "&"
    );

    /**
     * 前置包装命令集合：其后的 token 仍处于命令位置（如 {@code sudo rm -rf /}）
     */
    private static final Set<String> COMMAND_WRAPPERS = Set.of(
            "sudo", "doas", "command", "nohup", "setsid", "time", "env", "xargs"
    );

    private CommandInspector() {
    }

    /**
     * 危险命令形态及其可读风险说明
     *
     * @param pattern 命令形态正则（对整个命令串做全串匹配）
     * @param reason  面向模型与用户的拒绝原因（人类可读，刻意不回显裸正则，
     *                避免把排查方向误导到正则语义而非真实风险上）
     */
    private record DangerousCommandPattern(Pattern pattern, String reason) {
    }

    /**
     * 危险命令形态判定结果
     *
     * @param reason 风险说明（人类可读）
     */
    public record DangerFinding(String reason) {
    }

    /**
     * 对命令文本做词法分析并返回命中的危险形态；无命中返回 null。
     * <p>
     * 两条判据按固定顺序检查：先查「破坏性删除目标」（判据是目标路径），
     * 再查「命令形态黑名单」（判据是形态本身）。
     * </p>
     *
     * @param command 原始命令文本
     * @return 命中的风险说明；未命中返回 null
     */
    public static DangerFinding inspectDanger(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        List<String> cmdParts = splitCommand(command);
        if (cmdParts.isEmpty()) {
            return null;
        }

        // a. 递归删除指向破坏性目标（系统根、用户主目录、驱动器根）时命中
        String destructiveTarget = findDestructiveDeleteTarget(cmdParts);
        if (destructiveTarget != null) {
            return new DangerFinding("命令尝试递归删除重要系统路径或用户主目录（目标: "
                    + destructiveTarget + "）。该操作可能造成不可逆的数据丢失，已被安全拦截。");
        }

        // b. 命令形态本身即具破坏性的场景（是否危险与目标路径无关）
        for (DangerousCommandPattern danger : DANGEROUS_COMMAND_PATTERNS) {
            if (danger.pattern().matcher(command).matches()) {
                return new DangerFinding("该命令被判定为危险操作：" + danger.reason()
                        + "。如确需执行，请改用影响范围明确、风险更小的等价命令。");
            }
        }
        return null;
    }

    /**
     * 判定命令是否包含 shell 元字符（分号、管道、后台运行、重定向、子命令执行等）。
     * 供安全命令快速放行前的旁路防御使用。
     */
    public static boolean containsShellMetaCharacters(String cmd) {
        if (cmd == null) {
            return true;
        }
        return cmd.contains(";") || cmd.contains("|") || cmd.contains("&")
                || cmd.contains(">") || cmd.contains("<") || cmd.contains("$(")
                || cmd.contains("`") || cmd.contains("\n") || cmd.contains("\r");
    }

    /**
     * 按空白切分命令文本，但保留引号内的空白（引号本身不剥离，由后续步骤处理）。
     */
    public static List<String> splitCommand(String command) {
        List<String> list = new ArrayList<>();
        if (command == null) {
            return list;
        }
        StringBuilder sb = new StringBuilder();
        boolean inDoubleQuotes = false;
        boolean inSingleQuotes = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (Character.isWhitespace(c) && !inDoubleQuotes && !inSingleQuotes) {
                if (sb.length() > 0) {
                    list.add(sb.toString());
                    sb.setLength(0);
                }
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            list.add(sb.toString());
        }
        return list;
    }

    /**
     * 从命令 token 中识别指向破坏性目标的删除操作。
     * <p>
     * 判据是「删除程序 + 破坏性目标」的组合，而非对整条命令串做形态正则：
     * 这样「rm -rf dist」保持放行、「rm -rf /」被拦截，不会因命令长得像 rm -rf 就无差别拒绝。
     * </p>
     * <p>
     * 扫描范围覆盖命令中<b>所有</b>命令位置而非仅首 token：命令可以链式拼接
     * （如 {@code echo x && rm -rf ~}、{@code ls ; rm -rf /}），若只看首 token，
     * 把危险命令接在无害命令之后即可绕过本层拦截。
     * </p>
     * <p>
     * 目标一律取自命令 token 的真实参数（经 {@link #splitCommand} 分词，引号已被剥离）。
     * </p>
     *
     * @param cmdParts 已分词的命令（首元素为程序名）
     * @return 命中的破坏性目标原文；未命中返回 null
     */
    public static String findDestructiveDeleteTarget(List<String> cmdParts) {
        // 先规范化 token 序列：粘连写法（如 fs&&rm）会把分隔符与下一个程序名并入同一 token，
        // 不切开就识别不出「命令位置」，危险命令藏在无害命令后会整体漏检
        List<String> tokens = normalizeCommandTokens(cmdParts);
        for (int i = 0; i < tokens.size(); i++) {
            if (!isCommandPosition(tokens, i)) {
                continue;
            }
            String programName = extractProgramName(tokens.get(i));
            if (!DELETE_PROGRAMS.contains(programName)) {
                continue;
            }
            // 仅在该删除程序的参数范围内查找目标，直到遇到下一个命令分隔符为止
            for (int j = i + 1; j < tokens.size(); j++) {
                String arg = tokens.get(j).trim();
                if (isCommandSeparator(arg)) {
                    break;
                }
                if (arg.isEmpty() || isOptionArg(arg, programName)) {
                    continue;
                }
                // 分词阶段已剥离引号，此处再兜一层，防止其它调用路径传入带引号的目标
                String target = stripQuotes(arg);
                if (isDestructiveDeleteTarget(target)) {
                    return target;
                }
            }
        }
        return null;
    }

    /**
     * 将命令 token 序列规范化：把 token 内部粘连的命令分隔符切开为独立 token。
     * <p>
     * 例如 {@code ls&&rm} 拆为 {@code ls}、{@code &&}、{@code rm}，
     * 使后续「命令位置」判定能正确识别出分隔符之后的新命令。
     * </p>
     *
     * @param cmdParts 原始分词结果
     * @return 分隔符已独立成项的 token 序列
     */
    private static List<String> normalizeCommandTokens(List<String> cmdParts) {
        List<String> normalized = new ArrayList<>();
        for (String token : cmdParts) {
            StringBuilder buffer = new StringBuilder();
            int cursor = 0;
            while (cursor < token.length()) {
                String matched = matchSeparatorAt(token, cursor);
                if (matched == null) {
                    buffer.append(token.charAt(cursor));
                    cursor++;
                    continue;
                }
                if (buffer.length() > 0) {
                    normalized.add(buffer.toString());
                    buffer.setLength(0);
                }
                normalized.add(matched);
                cursor += matched.length();
            }
            if (buffer.length() > 0) {
                normalized.add(buffer.toString());
            }
        }
        return normalized;
    }

    /**
     * 在 token 的指定位置匹配命令分隔符，按最长优先（{@code &&} 优先于 {@code &}）
     *
     * @param token 待匹配 token
     * @param index 匹配起始位置
     * @return 命中的分隔符；无命中返回 null
     */
    private static String matchSeparatorAt(String token, int index) {
        String matched = null;
        for (String separator : COMMAND_SEPARATORS) {
            if (token.startsWith(separator, index)
                    && (matched == null || separator.length() > matched.length())) {
                matched = separator;
            }
        }
        return matched;
    }

    /**
     * 判定 token 是否处于「命令位置」（即真正会被 shell 执行的程序名所在位置）。
     * <p>
     * 命令位置包括：整条命令的首 token；紧跟命令分隔符（{@code &&}、{@code ||}、
     * {@code ;}、{@code |}、{@code &}）之后的 token；以及 {@code sudo} 等前置包装命令之后的 token。
     * </p>
     * <p>
     * 该判定用于排除 {@code git rm}、{@code echo rm -rf /} 这类 token 恰好叫 rm、
     * 但并非真正执行删除的场景，避免将非删除语义误判为破坏性操作。
     * </p>
     *
     * @param cmdParts 已分词的命令
     * @param index    待判定的 token 下标
     * @return 处于命令位置返回 true
     */
    private static boolean isCommandPosition(List<String> cmdParts, int index) {
        if (index == 0) {
            return true;
        }
        String previous = cmdParts.get(index - 1).trim();
        return isCommandSeparator(previous) || COMMAND_WRAPPERS.contains(previous.toLowerCase());
    }

    /**
     * 判定 token 是否为命令分隔符或前置包装命令。
     * <p>
     * 分隔符兼容粘写形态（如 {@code a&&b}、{@code a;b}），只要 token 以分隔符结尾即认定为分隔符。
     * </p>
     *
     * @param token 待判定 token
     * @return 是分隔符或前置包装命令返回 true
     */
    private static boolean isCommandSeparator(String token) {
        if (COMMAND_SEPARATORS.contains(token)) {
            return true;
        }
        for (String separator : COMMAND_SEPARATORS) {
            if (separator.length() > 1 && token.endsWith(separator)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定删除目标是否为破坏性目标（系统根、驱动器根、主目录及其下任意子路径）。
     * <p>
     * 三重判据：精确命中系统根；形如驱动器根（{@code C:\}）；或以主目录展开语义开头
     * （{@code ~/Documents} 这类须在其未展开形态下拦截，见 {@link #HOME_EXPANSION_PREFIXES}）。
     * </p>
     *
     * @param target 已剥离引号的删除目标
     * @return 属破坏性目标返回 true
     */
    private static boolean isDestructiveDeleteTarget(String target) {
        if (DESTRUCTIVE_TARGETS.contains(target) || DRIVE_ROOT_PATTERN.matcher(target).matches()) {
            return true;
        }
        for (String prefix : HOME_EXPANSION_PREFIXES) {
            if (target.equals(prefix) || target.startsWith(prefix + "/") || target.startsWith(prefix + "\\")
                    || target.startsWith(prefix + "*")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提取程序名（去掉目录前缀与 Windows 可执行后缀），统一小写。
     * 兼容 {@code /bin/rm}、{@code C:\tools\rm.exe} 等带路径的写法。
     *
     * @param programToken 命令首 token
     * @return 归一化后的程序名（如 rm、rmdir）
     */
    private static String extractProgramName(String programToken) {
        String name = programToken;
        int slashIdx = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slashIdx >= 0 && slashIdx < name.length() - 1) {
            name = name.substring(slashIdx + 1);
        }
        if (name.toLowerCase().endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.toLowerCase();
    }

    /**
     * 判定是否为命令行选项参数，避免把 {@code -rf}、{@code /s} 这类开关误当作删除目标。
     * <p>
     * Windows 风格的 {@code /x} 开关仅在删除类程序下识别，且限定 1~2 个字母，
     * 以免把 {@code /tmp} 这类真实路径误判为开关而漏检。
     * </p>
     *
     * @param arg         参数 token
     * @param programName 归一化程序名
     * @return 是选项返回 true
     */
    private static boolean isOptionArg(String arg, String programName) {
        if (arg.startsWith("-")) {
            return true;
        }
        return DELETE_PROGRAMS.contains(programName) && arg.matches("/[a-zA-Z]{1,2}");
    }

    /**
     * 剥离参数两端成对的引号（单引号或双引号）
     *
     * @param value 原始参数
     * @return 去引号结果
     */
    private static String stripQuotes(String value) {
        String result = value;
        while (result.length() >= 2
                && ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("'") && result.endsWith("'")))) {
            result = result.substring(1, result.length() - 1);
        }
        return result;
    }
}
