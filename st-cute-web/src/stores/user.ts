import { acceptHMRUpdate, defineStore } from 'pinia'
import { ref } from 'vue'
import { UserInfo, getUserInfoApi, loginApi, logoutApi } from '@/api/auth'

export const useUserStore = defineStore('user', () => {
  const userInfo = ref<UserInfo | null>(null)

  const setUserInfo = (info: UserInfo | null) => {
    userInfo.value = info
    if (info) {
      sessionStorage.setItem('st_cute_user', JSON.stringify(info))
    } else {
      sessionStorage.removeItem('st_cute_user')
    }
  }

  const fetchUserInfo = async (): Promise<UserInfo | null> => {
    try {
      const data = await getUserInfoApi()
      setUserInfo(data)
      return data
    } catch (e) {
      setUserInfo(null)
      return null
    }
  }

  const login = async (password: string): Promise<UserInfo> => {
    const data = await loginApi(password)
    setUserInfo(data)
    return data
  }

  /**
   * 登出：先通知服务端销毁会话，再清理本地状态。
   * <p>本地状态清理不依赖服务端调用成功：网络异常时依然要退出登录态，
   * 避免"服务端会话已失效但本地仍显示已登录"的错位</p>
   */
  const logout = () => {
    logoutApi().catch(err => {
      console.warn('通知服务端登出失败，本地登录态照常清理:', err)
    })
    setUserInfo(null)
  }

  // 从 sessionStorage 恢复用户信息
  const initFromStorage = () => {
    const stored = sessionStorage.getItem('st_cute_user')
    if (stored) {
      try {
        userInfo.value = JSON.parse(stored)
      } catch (e) {
        sessionStorage.removeItem('st_cute_user')
      }
    }
  }

  return {
    userInfo,
    setUserInfo,
    fetchUserInfo,
    login,
    logout,
    initFromStorage
  }
})

// 启用 Pinia store 热更新：dev 热替换时复用原 store 实例，避免新旧实例并存导致组件状态分裂、刷新链断裂
if (import.meta.hot) {
  acceptHMRUpdate(useUserStore, import.meta.hot)
}
