import { marked } from 'marked'

/**
 * 统一的 Markdown 渲染入口
 *
 * 所有要经 v-html 注入 DOM 的 Markdown 内容（消息正文、规则详情等）
 * 统一走本函数：marked 解析后交给 v-html。
 * 解析异常时回退为纯文本转义 + 换行转 <br> 的展示。
 * 后续如果要做安全消毒，可以在这里统一处理。
 */
export const renderMarkdownSafe = (text: string | undefined | null): string => {
  if (!text) return ''
  try {
    // marked 同步解析 markdown
    return marked.parse(text, { async: false, gfm: true, breaks: true }) as string
  } catch (e) {
    console.error('Markdown 解析错误:', e)
    return text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/\n/g, '<br>')
  }
}
