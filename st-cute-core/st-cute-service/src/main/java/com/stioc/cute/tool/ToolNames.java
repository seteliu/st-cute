package com.stioc.cute.tool;

/**
 * 宿主工具名称常量，统一作为工具 getName() 的返回值来源，
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
     * 加载技能包详细指令工具协议名
     */
    public static final String LOAD_SKILL      = "load_skill";

    /**
     * 获取平台规约文档工具协议名
     */
    public static final String GET_DOC         = "get_doc";

    /**
     * 获取当前系统时间工具协议名
     */
    public static final String GET_TIME        = "get_time";

    /**
     * 加载历史附件文件内容工具协议名
     */
    public static final String LOAD_ATTACHMENT = "load_attachment";

    /**
     * 删除文件/空目录工具协议名
     */
    public static final String DELETE_FILE = "delete_file";

    /**
     * 移动/复制文件工具协议名
     */
    public static final String MOVE_FILE = "move_file";
}
