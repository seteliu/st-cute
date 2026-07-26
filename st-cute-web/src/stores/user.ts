import { defineStore } from 'pinia'
import { ref } from 'vue'
import { UserInfo, getUserInfoApi, loginApi } from '@/api/auth'

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

  const logout = () => {
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
