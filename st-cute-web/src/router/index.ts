import { createRouter, createWebHashHistory } from 'vue-router'
import Home from '@/views/Home.vue'
import Login from '@/views/login/Login.vue'
import { useUserStore } from '@/stores/user'

const routes = [
  {
    path: '/',
    name: 'Home',
    component: Home
  },
  {
    path: '/login',
    name: 'Login',
    component: Login
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach(async (to, from, next) => {
  const userStore = useUserStore()

  // 如果发现 sessionStorage 里的登录缓存已被清空（如请求 401 拦截），但内存中仍有用户信息，则同步清空内存
  if (!sessionStorage.getItem('st_cute_user') && userStore.userInfo) {
    userStore.logout()
  }

  // 先尝试从 sessionStorage 恢复用户信息到 store 中
  if (!userStore.userInfo) {
    userStore.initFromStorage()
  }

  // 如果依然没有用户信息，且目标路由不是登录页，则静默调用用户信息接口进行获取
  if (!userStore.userInfo && to.path !== '/login') {
    const info = await userStore.fetchUserInfo()
    if (!info) {
      next('/login')
      return
    }
  }

  // 如果已有用户信息且目标路由是登录页，则重定向回首页
  if (userStore.userInfo && to.path === '/login') {
    next('/')
    return
  }

  next()
})

export default router
