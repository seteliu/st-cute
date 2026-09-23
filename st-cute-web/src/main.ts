import { createApp } from 'vue'
import { createPinia } from 'pinia'
import naive from 'naive-ui'
import App from './App.vue'
import router from './router'
import { applyTheme } from './styles/themeVars'
import '@/assets/styles/main.css'

// 应用默认主题变量：CSS 自定义属性写入文档根，全站 var(--xxx) 引用即生效（多主题切换的单一入口）
applyTheme('dark')

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
