package com.stioc.cute.security.access;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 权限规则定义实体，支持 Glob 通配符模式匹配
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PermissionRule {
    
    /**
     * 作用的目标工具协议名，如 read_file、execute_command
     */
    private String toolName;

    /**
     * 内容模式匹配串，支持 Glob 通配符，如 "*.java" 或 "git status"
     */
    private String contentPattern;

    /**
     * 裁决效力: allow (放行) 或 deny (拒绝)
     */
    private String effect;
}
