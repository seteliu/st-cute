import axios from 'axios'
import { Result } from '@/types'

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
        sessionStorage.removeItem('st_cute_user')
        window.location.hash = '#/login'
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
      sessionStorage.removeItem('st_cute_user')
      window.location.hash = '#/login'
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
