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
     * 按 Glob 表达式查找文件工具协议名
     */
    public static final String FIND_FILES    = "find_files";

    /**
     * 正则全文检索工具协议名
     */
    public static final String GREP_SEARCH   = "grep_search";


    /**
     * 新建/覆写文件工具协议名
     */
    public static final String WRITE_FILE    = "write_file";

    /**
     * 局部替换文件内容工具协议名
     */
    public static final String EDIT_FILE     = "edit_file";

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
     * 加载历史附件文件内容工具协议名
     */
    public static final String LOAD_ATTACHMENT = "load_attachment";

    /**
     * SearXNG 联网聚合搜索工具协议名
     */
    public static final String WEB_SEARCH = "web_search";
}
