import { computed, ref, type Ref } from 'vue'
import { NARROW_DESKTOP_BREAKPOINT } from '@/constants/layout'

export interface UseResponsiveReturn {
  isMobile: Ref<boolean>
  /** 窄桌面档：介于移动端断点与窄桌面断点之间，侧栏抽屉化但 UI 仍走桌面渲染 */
  isNarrowDesktop: Ref<boolean>
  /** 侧栏是否由抽屉承载：移动端或窄桌面档均成立（三处布局消费点的统一判定，避免各自拼装走样） */
  isOverlaySider: Ref<boolean>
  width: Ref<number>
  height: Ref<number>
}

// 响应式单例状态
const isMobile = ref(false)
const isNarrowDesktop = ref(false)
const width = ref(0)
const height = ref(0)

// 侧栏是否由抽屉承载：移动端（<768）或窄桌面档（768~1251）——两档下左右侧栏均退化为悬浮抽屉，
// 常驻三栏语义让位给 Drawer。收口为单一 computed，供 Home/LeftSider/RightSider 统一消费
const isOverlaySider = computed(() => isMobile.value || isNarrowDesktop.value)

const breakpointValue = 768
let initialized = false
let resizeTimer: any = null

function checkMobile() {
  width.value = window.innerWidth
  height.value = window.innerHeight
  isMobile.value = window.innerWidth < breakpointValue
  // 窄桌面档 = 移动端之上、窄桌面断点之下；两档共用「侧栏抽屉化」行为，中间区渲染走桌面分支
  isNarrowDesktop.value =
    window.innerWidth >= breakpointValue && window.innerWidth < NARROW_DESKTOP_BREAKPOINT
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
    isNarrowDesktop,
    isOverlaySider,
    width,
    height
  }
}
