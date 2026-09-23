import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * 统一的 Markdown 渲染入口
 *
 * 所有要经 v-html 注入 DOM 的 Markdown 内容（消息正文、规则详情等）
 * 统一走本函数：marked 解析后经 DOMPurify 消毒，再交给 v-html。
 * <p>
 * 消毒是强制的：消息正文来源是大模型输出（可能转述网页/文件内容），
 * 未消毒的 HTML 经 v-html 注入会形成存储型 XSS（如 img onerror、javascript: 链接）。
 * </p>
 * 解析异常时回退为纯文本转义 + 换行转 <br> 的展示。
 */

// 链接安全加固：消毒后统一给 <a> 补 target=_blank 与 rel，防止 window.opener 劫持；
// 同时在此收口链接协议（DOMPurify 默认已拦截 javascript:/vbscript:/data: 等危险协议）
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})

/** 消毒配置：保持常规富文本渲染能力，禁用一切主动执行能力 */
const SANITIZE_CONFIG = {
  // 允许 markdown 常规产出的标签集合之外的主动执行类标签（script/iframe/object/embed/style/form 等）
  // 均不在 DOMPurify 默认白名单内，会被自动剥离；此处显式声明 Forbidden 以明示意图
  FORBID_TAGS: ['style', 'form', 'input', 'button', 'iframe', 'object', 'embed', 'script'],
  FORBID_ATTR: ['style', 'onerror', 'onload', 'onclick', 'onmouseover']
}

export const renderMarkdownSafe = (text: string | undefined | null): string => {
  if (!text) return ''
  try {
    // marked 同步解析 markdown，随后经 DOMPurify 消毒剥离危险标签/属性/协议
    const rawHtml = marked.parse(text, { async: false, gfm: true, breaks: true }) as string
    return DOMPurify.sanitize(rawHtml, SANITIZE_CONFIG)
  } catch (e) {
    console.error('Markdown 解析错误:', e)
    return text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/\n/g, '<br>')
  }
}
