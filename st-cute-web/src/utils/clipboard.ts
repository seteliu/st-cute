// 剪贴板统一封装。
// 背景：navigator.clipboard 仅在安全上下文（HTTPS / localhost）下存在，
// 通过 HTTP 域名部署访问时该对象为 undefined，直接调用会抛出
// "Cannot read properties of undefined (reading 'writeText')"，且是同步异常，
// 不会进入 .catch 兜底，导致点击复制无任何反馈。
// 此处统一收口：优先走 Clipboard API，不可用时降级为隐藏 textarea + execCommand 方案，
// 并以 boolean 返回结果，由调用方决定成功/失败提示。

/**
 * 复制文本到剪贴板
 * @param text 待复制的文本内容
 * @returns 是否复制成功
 */
export async function copyTextToClipboard(text: string): Promise<boolean> {
  const content = text ?? ''
  // 优先走异步 Clipboard API（安全上下文下可用）
  if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
    try {
      await navigator.clipboard.writeText(content)
      return true
    } catch (e) {
      // 权限被拒等场景继续尝试降级方案
      console.warn('Clipboard API 复制失败，尝试降级方案:', e)
    }
  }
  return copyViaExecCommand(content)
}

/**
 * 降级方案：借助隐藏 textarea + document.execCommand('copy') 复制（非安全上下文下可用）
 */
function copyViaExecCommand(content: string): boolean {
  try {
    const textarea = document.createElement('textarea')
    textarea.value = content
    // 移到视口外并隐藏，避免复制时页面闪现或滚动跳动
    textarea.style.position = 'fixed'
    textarea.style.top = '-9999px'
    textarea.style.left = '-9999px'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.focus()
    textarea.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    if (!ok) {
      console.error('execCommand 降级复制失败')
    }
    return ok
  } catch (e) {
    console.error('降级复制异常:', e)
    return false
  }
}
