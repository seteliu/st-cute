import axios from 'axios'
import { Result } from '@/types'

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
      if ((window as any).$message) {
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

    const errMsg = error.response?.data?.msg || error.message || '系统连接网络异常'
    if ((window as any).$message) {
      ;(window as any).$message.error(errMsg)
    }
    return Promise.reject(error)
  }
)

export default service
