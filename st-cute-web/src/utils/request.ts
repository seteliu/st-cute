import axios from 'axios'
import { Result } from '@/types'

// 扩展 axios 请求配置：silent 标识后台静默请求（如轮询），失败时拦截器不弹全局错误提示，
// 仅通过 Promise.reject 交由调用方自行决定处理方式
declare module 'axios' {
  export interface AxiosRequestConfig {
    silent?: boolean
  }
}

// 401 统一处理：清除本地登录态后跳转登录页。
// 采用「改 hash + 整页 reload」而非 SPA 内路由跳转：后端重启等场景会导致内存态
// （Pinia store、wsService 单例、会话缓存等）与后端状态脱节且无法自行复位，
// SPA 内跳转会让 Home 带着脏状态挂载而渲染空白；整页刷新等价于用户手动 F5，
// 让应用以全新状态重新初始化，这是会话失效场景最稳妥的处理方式。
const redirectToLogin = () => {
  sessionStorage.removeItem('st_cute_user')
  if (window.location.hash !== '#/login') {
    window.location.hash = '#/login'
    window.location.reload()
  }
}

// HTTP 层错误消息中文化：axios 原生 error.message 为英文（如 Network Error、timeout of xxx ms exceeded），
// 按场景映射为中文提示；后端有业务消息（msg）时优先展示后端内容
const humanizeHttpError = (error: any): string => {
  if (error.response) {
    // 后端有响应但状态码非 2xx：优先取后端业务消息，其次按状态码兜底
    return error.response.data?.msg || `请求失败（HTTP ${error.response.status}）`
  }
  const message: string = error.message || ''
  if (error.code === 'ECONNABORTED' || message.includes('timeout')) {
    return '请求超时，请检查后端服务是否可用'
  }
  return '网络连接异常，请检查后端服务'
}

const service = axios.create({
  timeout: 60000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截器
service.interceptors.request.use(
  (config) => {
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// 响应拦截器
service.interceptors.response.use(
  (response) => {
    const res = response.data as Result
    if (res.code !== 0) {
      if (res.code === 401) {
        redirectToLogin()
        return Promise.reject(new Error(res.msg || '未登录'))
      }
      const errMsg = res.msg || '后端返回业务错误'
      // 静默请求（后台轮询）失败不弹窗，避免轮询期间错误弹窗轰炸
      if (!response.config.silent && (window as any).$message) {
        ;(window as any).$message.error(errMsg)
      }
      return Promise.reject(new Error(errMsg))
    }
    return res.data
  },
  (error) => {
    const status = error.response?.status
    const code = error.response?.data?.code
    if (status === 401 || code === 401) {
      redirectToLogin()
      return Promise.reject(error)
    }

    const errMsg = humanizeHttpError(error)
    // 静默请求（后台轮询）失败不弹窗，仅记录后由调用方自行兜底
    if (!error.config?.silent && (window as any).$message) {
      ;(window as any).$message.error(errMsg)
    }
    return Promise.reject(error)
  }
)

export default service
