/**
 * 工具名称本地化展示工具
 *
 * 后端工具协议名（ToolNames.java 中定义的 snake_case 名）与历史类名（xxxTool）在前端聊天流、
 * 审批弹窗、原始日志等场景直接以英文展示，中文环境下不友好。
 * 此处维护一张「工具名 → 中文简短译名」的静态映射表，由各展示组件统一调用，
 * 保证全局译名一致、后续新增工具只改这一处。
 *
 * 说明：
 * 1. 中文环境返回简短译名，非中文环境原样返回（兜底去除 Tool 后缀）；
 * 2. MCP 动态接入的工具名称不可枚举，无映射时回退展示原名；
 * 3. 译名仅用于界面展示，不影响任何逻辑判断与上报的原始 toolName。
 */
import { getLanguage } from '@/i18n'

/**
 * 工具名映射表：key 支持后端协议名（如 read_file）与历史类名（如 ReadFileTool）双写，
 * 值为简短中文译名，尽量 2~6 字体现工具核心作用。
 */
const TOOL_NAME_MAP: Record<string, string> = {
  // 文件与检索类
  'read_file': '读取文件',
  'ReadFileTool': '读取文件',
  'list_dir': '遍历目录',
  'FindFilesTool': '遍历目录',
  'grep_search': '全文检索',
  'write_to_file': '写入文件',
  'WriteFileTool': '写入文件',
  'replace_file_content': '修改文件',
  'ModifyFileTool': '修改文件',
  'load_attachment': '加载附件',
  'LoadAttachmentTool': '加载附件',

  // 命令执行类
  'execute_command': '执行命令',
  'RunCommandTool': '执行命令',

  // 智能体扩展类
  'discover_tools': '发现工具',
  'invoke_subagent': '调用子智能体',
  'load_skill': '加载技能',
  'get_doc': '获取规约文档',
  'get_time': '获取时间',

  // 工作区隔离类
  'enter_worktree': '进入工作树',
  'EnterWorktreeTool': '进入工作树',
  'exit_worktree': '退出工作树',
  'ExitWorktreeTool': '退出工作树'
}

/**
 * 将后端工具名翻译为当前语言下的展示名
 *
 * @param name 后端工具协议名或历史类名（允许空值）
 * @returns 中文环境返回映射译名（无映射回退原名）；其他语言回退去除 Tool 后缀的原名
 */
export function formatToolName(name: string | undefined | null): string {
  if (!name) return ''
  // 中文环境下优先查映射表，未命中（如 MCP 动态工具）保持原名
  if (getLanguage() === 'zh-CN') {
    return TOOL_NAME_MAP[name] || name
  }
  // 非中文环境维持原有处理：历史类名去掉 Tool 后缀展示
  if (name.endsWith('Tool')) {
    return name.slice(0, -4)
  }
  return name
}
