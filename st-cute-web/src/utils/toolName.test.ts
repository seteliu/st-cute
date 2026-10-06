/**
 * 【toolName 单元测试 — 工具名本地化展示】
 *
 * 覆盖对象：src/utils/toolName.ts —— 工具名 → 中文译名映射与 MCP 收敛展示
 * 验证策略：
 *   1. 中文环境：协议名/历史类名双写映射命中、未命中回退原名
 *   2. MCP 动态工具统一收敛「MCP调用」
 *   3. 非中文环境：去 Tool 后缀回退
 *   4. 空值防御
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { formatToolName } from './toolName'
import { setLanguage, getLanguage } from '@/i18n'

describe('formatToolName — 中文环境', () => {
  beforeEach(() => setLanguage('zh-CN'))

  it('协议名映射命中', () => {
    expect(formatToolName('read_file')).toBe('读取文件')
    expect(formatToolName('edit_file')).toBe('修改文件')
    expect(formatToolName('execute_command')).toBe('执行命令')
    expect(formatToolName('web_search')).toBe('联网搜索')
    expect(formatToolName('invoke_subagent')).toBe('调用子智能体')
  })

  it('历史类名映射命中（双写 key）', () => {
    expect(formatToolName('ReadFileTool')).toBe('读取文件')
    expect(formatToolName('WriteFileTool')).toBe('写入文件')
  })

  it('未命中映射回退原名', () => {
    expect(formatToolName('unknown_tool')).toBe('unknown_tool')
  })

  it('MCP 动态工具统一收敛为「MCP调用」', () => {
    expect(formatToolName('mcp__github__create_issue')).toBe('MCP调用')
    expect(formatToolName('mcp__mock-mcp-stdio__mock_greet')).toBe('MCP调用')
  })
})

describe('formatToolName — 非中文环境', () => {
  beforeEach(() => setLanguage('en-US'))

  it('去 Tool 后缀回退', () => {
    expect(formatToolName('ReadFileTool')).toBe('ReadFile')
    expect(formatToolName('SearxngSearchTool')).toBe('SearxngSearch')
  })

  it('协议名原样返回', () => {
    expect(formatToolName('read_file')).toBe('read_file')
  })

  it('MCP 工具收敛为「MCP Call」', () => {
    expect(formatToolName('mcp__github__create_issue')).toBe('MCP Call')
  })
})

describe('formatToolName — 空值防御', () => {
  it('undefined/null/空串返回空串', () => {
    expect(formatToolName(undefined)).toBe('')
    expect(formatToolName(null)).toBe('')
    expect(formatToolName('')).toBe('')
  })
})

describe('语言状态恢复', () => {
  it('测试后语言还原为中文（避免污染其他测试文件）', () => {
    setLanguage('zh-CN')
    expect(getLanguage()).toBe('zh-CN')
  })
})
