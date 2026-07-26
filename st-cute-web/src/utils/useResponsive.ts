import { ref, type Ref } from 'vue'

export interface UseResponsiveReturn {
  isMobile: Ref<boolean>
  width: Ref<number>
  height: Ref<number>
}

// 响应式单例状态
const isMobile = ref(false)
const width = ref(0)
const height = ref(0)

const breakpointValue = 768
let initialized = false
let resizeTimer: any = null

function checkMobile() {
  width.value = window.innerWidth
  height.value = window.innerHeight
  isMobile.value = window.innerWidth < breakpointValue
}

/**
 * 初始化响应式检测
 */
export function initResponsive() {
  if (initialized) return
  if (typeof window === 'undefined') return

  checkMobile()
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer)
    resizeTimer = setTimeout(checkMobile, 100)
  })
  initialized = true
}

/**
 * 响应式 Composable，返回全局共享的单例状态
 */
export function useResponsive(): UseResponsiveReturn {
  return {
    isMobile,
    width,
    height
  }
}
