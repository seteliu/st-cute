package com.stioc.cute.agent.access;

import lombok.Builder;
import lombok.Data;

/**
 * 外部子进程信息视图实体 VO (用于返回前端展示)
 */
@Data
@Builder
public class ActiveProcessVo {

    /**
     * 所属会话 ID
     */
    private Long cid;

    /**
     * 会话标题 / 子智能体角色名
     */
    private String sessionTitle;

    /**
     * 工具调用 ID
     */
    private String toolCallId;

    /**
     * 系统进程 PID
     */
    private Long pid;

    /**
     * 执行的命令行语句
     */
    private String command;

    /**
     * 命令执行的物理工作目录
     */
    private String cwd;

    /**
     * 启动的时间戳（毫秒）
     */
    private Long startTime;

    /**
     * 已经运行时长（毫秒）
     */
    private Long runningTimeMs;
}
