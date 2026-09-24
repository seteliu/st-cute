import { createApp } from 'vue'
import { createPinia } from 'pinia'
import naive from 'naive-ui'
import App from './App.vue'
import router from './router'
import { applyTheme } from './styles/themeVars'
import { currentTheme } from './styles/theme'
import '@/assets/styles/main.css'

// 应用初始主题变量：优先采用本地快照（默认为 dark），确保首屏在网络请求前以正确主题就位
applyTheme(currentTheme.value)

// 平台标记：NSelect 文字垂直居中的 -1.5px 补偿仅针对 Windows 系统字体度量（文字视觉偏下），
// 启动时按 UA 检测在根节点挂 platform-windows 类，main.css 的补偿规则仅在该类下生效，
// 避免 macOS/Linux 与移动端被错误上移（UA 在各平台浏览器中仍稳定携带系统标识，足够做此粒度判定）
if (/Windows/i.test(navigator.userAgent)) {
  document.documentElement.classList.add('platform-windows')
}

const app = createApp(App)

// 全局错误边界：任何组件渲染/生命周期异常在此收口，避免整页白屏且无任何感知。
// 与后端 GlobalExceptionHandler 对位：记录完整错误栈便于排查，同时给用户一次性提示
app.config.errorHandler = (err, _instance, info) => {
  console.error(`[全局异常] ${info}:`, err)
  // $message 由 App.vue 的 provider 挂载，应用启动极早期可能尚未就绪，做存在性守卫
  ;(window as any).$message?.error('界面发生异常，请刷新重试；若持续出现请查看控制台日志')
}

// Promise 未捕获拒绝兜底：request.ts 拦截器与各调用方已 catch 绝大多数，
// 漏网的拒绝在此仅记日志不打扰用户（避免与局部 catch 的提示重复轰炸）
window.addEventListener('unhandledrejection', (event) => {
  console.error('[未捕获的 Promise 拒绝]:', event.reason)
})

app.use(createPinia())
app.use(router)
app.use(naive)
app.mount('#app')
