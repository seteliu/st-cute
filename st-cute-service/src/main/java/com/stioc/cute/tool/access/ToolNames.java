package com.stioc.cute.tool.access;

/**
 * 系统内置工具名称常量，统一作为工具 getName() 的返回值来源，
 * 避免各处（权限引擎、注释、日志等）出现硬编码字符串散落。
 */
public final class ToolNames {

    private ToolNames() {}

    /**
     * 读取文件内容工具协议名
     */
    public static final String READ_FILE     = "read_file";

    /**
     * 遍历目录结构工具协议名
     */
    public static final String LIST_DIR      = "list_dir";

    /**
     * 正则全文检索工具协议名
     */
    public static final String GREP_SEARCH   = "grep_search";

    /**
     * 探测解锁高级工具工具协议名
     */
    public static final String DISCOVER_TOOLS = "discover_tools";

    /**
     * 新建/覆写文件工具协议名
     */
    public static final String WRITE_TO_FILE        = "write_to_file";

    /**
     * 替换文件部分内容工具协议名
     */
    public static final String REPLACE_FILE_CONTENT = "replace_file_content";

    /**
     * 执行命令工具协议名
     */
    public static final String EXECUTE_COMMAND = "execute_command";

    /**
     * 唤醒拉起子智能体开发工具协议名
     */
    public static final String INVOKE_SUBAGENT = "invoke_subagent";

    /**
     * 绑定并切入工作区分支隔离环境工具协议名
     */
    public static final String ENTER_WORKTREE  = "enter_worktree";

    /**
     * 回收并撤出工作区隔离环境工具协议名
     */
    public static final String EXIT_WORKTREE   = "exit_worktree";

    /**
     * 加载技能包详细指令工具协议名
     */
    public static final String LOAD_SKILL      = "load_skill";

    /**
     * 获取平台规约文档工具协议名
     */
    public static final String GET_DOC         = "get_doc";
}
