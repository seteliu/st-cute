package com.stioc.cute.tool.findtool;

import java.nio.file.FileVisitResult;
import java.util.Locale;

/**
 * 搜索目录过滤器，统一处理文件遍历时的目录排除判定逻辑
 */
public final class SearchDirectoryFilter {

    private SearchDirectoryFilter() {
    }

    /**
     * 判断指定目录是否属于永久排除目录（如 .git, .svn, .hg 等版本库内部目录）
     *
     * @param dirName 目录名
     * @return 若是永久排除目录返回 true
     */
    public static boolean isAlwaysExcluded(String dirName) {
        return dirName != null && FileSearchConstants.ALWAYS_EXCLUDE_DIRS.contains(dirName.toLowerCase(Locale.ROOT));
    }

    /**
     * 判断在递归遍历目录时，是否应当跳过该子树
     *
     * @param dirName              当前访问目录的名称
     * @param includeExcludedDirs  是否放行常规排除清单（target, node_modules 等）
     * @param explicitAllowedHead  pattern 首段显式点名的目录名（如 "node_modules/**" 时为 "node_modules"），可为 null
     * @return 若应当跳过该子树返回 true，放行遍历返回 false
     */
    public static boolean shouldSkipDirectory(String dirName, boolean includeExcludedDirs, String explicitAllowedHead) {
        if (dirName == null) {
            return false;
        }
        // 统一小写化匹配，防止 Windows 大小写不敏感文件系统下因 Target/Build 等首字母大写导致排除穿透
        String lowerDirName = dirName.toLowerCase(Locale.ROOT);
        // 永久排除目录（.git/.svn/.hg 等版本库内部）：任何情况都跳过
        if (FileSearchConstants.ALWAYS_EXCLUDE_DIRS.contains(lowerDirName)) {
            return true;
        }
        // 常规排除清单：纯产物/依赖/IDE 缓存，默认跳过防海量文件拖垮搜索；
        // 显式点名（firstSegment）或 includeExcludedDirs = true 时放行（如验证编译产物场景）
        boolean isExplicitHead = explicitAllowedHead != null && dirName.equalsIgnoreCase(explicitAllowedHead);
        return !isExplicitHead && FileSearchConstants.EXCLUDE_DIRS.contains(lowerDirName) && !includeExcludedDirs;
    }

    /**
     * 将目录排除判定结果转换为 FileVisitResult（用于 SimpleFileVisitor.preVisitDirectory）
     */
    public static FileVisitResult evaluatePreVisit(String dirName, boolean includeExcludedDirs, String explicitAllowedHead) {
        return shouldSkipDirectory(dirName, includeExcludedDirs, explicitAllowedHead)
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
    }
}
