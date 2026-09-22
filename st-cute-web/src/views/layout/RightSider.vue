<template>
  <n-layout-sider
    collapse-mode="width"
    :collapsed-width="0"
    :collapsed="collapsed"
    :width="width"
    :show-trigger="false"
    bordered
    class="right-sider"
  >
    <n-tabs type="line" justify-content="space-evenly" class="inspector-tabs">
      <n-tab-pane name="overview">
        <template #tab>
          {{ t('inspector.overview') }}
        </template>
        <overview-pane />
      </n-tab-pane>

      <n-tab-pane name="advanced" :tab="t('inspector.advanced')">
        <div style="display: flex; flex-direction: column; height: 100%;">
          <!-- 统一重载控制头部 -->
          <div style="display: flex; justify-content: space-between; align-items: center; padding: 4px 4px 8px 4px; border-bottom: 1px dashed #2d2d30; margin-bottom: 12px;">
            <span style="font-size: 0.78rem; color: var(--text-color-secondary); font-weight: bold;">{{ t('inspector.assetsTitle') }}</span>
            <n-button
              size="tiny"
              type="primary"
              secondary
              :loading="isReloading"
              @click="handleUnifiedReload"
            >
              {{ t('inspector.hotReload') }}
            </n-button>
          </div>
          
          <n-tabs type="line" justify-content="space-evenly" class="advanced-sub-tabs">
            <n-tab-pane name="rule-sub" tab="RULE">
              <rules-pane />
            </n-tab-pane>
            <n-tab-pane name="skill-sub" tab="SKILL">
              <skills-pane />
            </n-tab-pane>
            <n-tab-pane name="mcp-sub" tab="MCP">
              <mcp-pane />
            </n-tab-pane>
            <n-tab-pane name="hook-sub" tab="HOOK">
              <hooks-pane />
            </n-tab-pane>
          </n-tabs>
        </div>
      </n-tab-pane>
      
      <n-tab-pane name="preview" :tab="t('inspector.preview')">
        <preview-pane />
      </n-tab-pane>

    </n-tabs>
    
    <!-- 自定义拖拽边框条 -->
    <div 
      v-if="!isMobile"
      ref="resizeHandleRef" 
      class="custom-resize-handle right-handle"
    >
      <div class="resize-line"></div>
    </div>
  </n-layout-sider>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { useAgentStore } from '@/stores/agent'
import { useConversationStore } from '@/stores/conversation'
import { useResponsive } from '@/utils/useResponsive'
import { t } from '@/i18n'

const { isMobile } = useResponsive()

const collapsed = computed(() => {
  return isMobile.value ? false : appStore.rightSiderCollapsed
})

const width = computed(() => {
  return isMobile.value ? '100%' : appStore.rightSiderWidth
})
import OverviewPane from '../inspector/OverviewPane.vue'
import PreviewPane from '../inspector/PreviewPane.vue'
import McpPane from '../inspector/McpPane.vue'
import SkillsPane from '../inspector/SkillsPane.vue'
import HooksPane from '../inspector/HooksPane.vue'
import RulesPane from '../inspector/RulesPane.vue'

const appStore = useAppStore()
const agentStore = useAgentStore()
const conversationStore = useConversationStore()
const isReloading = ref(false)

// 热重载按钮的最小 loading 保底时长（毫秒）：本机请求往往几十毫秒内完成，
// 转圈一闪而过毫无感知，保底展示让用户确认「重载已生效」
const MIN_RELOADING_MS = 400
const sleep = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

const handleUnifiedReload = async () => {
  const start = Date.now()
  isReloading.value = true
  try {
    const success = await conversationStore.reloadProjectAssets()
    if (!success) {
      // 失败必须有反馈：成功时右侧资产列表自动刷新即是反馈，失败时列表无变化会像「没点上」
      ;(window as any).$message?.error('热重载失败，请检查后端日志或物理配置是否正确')
    }
  } finally {
    // 补足最小 loading 时长：请求过快时等待至保底时长再收起，保证转圈可感知
    const elapsed = Date.now() - start
    if (elapsed < MIN_RELOADING_MS) {
      await sleep(MIN_RELOADING_MS - elapsed)
    }
    isReloading.value = false
  }
}

// 侧边栏拖拽调宽
const resizeHandleRef = ref<HTMLElement | null>(null)
let hoverTimer: any = null
let isResizing = false
let canResize = false
let resizeFrameId: number | null = null
let pendingWidth: number | null = null
let liveWidth: number | null = null

const getSiderEl = () => resizeHandleRef.value?.closest<HTMLElement>('.n-layout-sider') || null

const applyLiveWidth = (newWidth: number) => {
  liveWidth = newWidth
  const sider = getSiderEl()
  if (!sider) return
  const width = `${newWidth}px`
  sider.style.width = width
  sider.style.maxWidth = width
}

const flushPendingWidth = () => {
  resizeFrameId = null
  if (pendingWidth === null) return
  applyLiveWidth(pendingWidth)
  pendingWidth = null
}

const scheduleWidthUpdate = (newWidth: number) => {
  pendingWidth = newWidth
  if (resizeFrameId === null) {
    resizeFrameId = window.requestAnimationFrame(flushPendingWidth)
  }
}

const handleMouseUp = () => {
  isResizing = false
  canResize = false
  if (resizeFrameId !== null) {
    window.cancelAnimationFrame(resizeFrameId)
    resizeFrameId = null
  }
  const finalWidth = pendingWidth ?? liveWidth
  if (finalWidth !== null) {
    applyLiveWidth(finalWidth)
    appStore.rightSiderWidth = finalWidth
    pendingWidth = null
    liveWidth = null
  }
  if (resizeHandleRef.value) {
    resizeHandleRef.value.classList.remove('active-resizing')
  }
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  document.body.classList.remove('sider-resizing')
  document.removeEventListener('mousemove', handleMouseMove)
  document.removeEventListener('mouseup', handleMouseUp)
}

const handleMouseMove = (e: MouseEvent) => {
  if (!isResizing) return
  let newWidth = window.innerWidth - e.clientX
  if (newWidth < 240) newWidth = 240
  if (newWidth > 600) newWidth = 600
  scheduleWidthUpdate(newWidth)
}

const initResizeEvents = () => {
  const handle = resizeHandleRef.value
  if (!handle) return

  handle.addEventListener('mouseenter', () => {
    hoverTimer = setTimeout(() => {
      canResize = true
      handle.classList.add('active-resizing')
    }, 100)
  })

  handle.addEventListener('mouseleave', () => {
    clearTimeout(hoverTimer)
    if (!isResizing) {
      canResize = false
      handle.classList.remove('active-resizing')
    }
  })

  handle.addEventListener('mousedown', (e) => {
    if (!canResize) return
    isResizing = true
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    document.body.classList.add('sider-resizing')
    
    document.addEventListener('mousemove', handleMouseMove)
    document.addEventListener('mouseup', handleMouseUp)
    e.preventDefault()
  })
}

onMounted(() => {
  initResizeEvents()
})

onUnmounted(() => {
  clearTimeout(hoverTimer)
  if (resizeFrameId !== null) {
    window.cancelAnimationFrame(resizeFrameId)
  }
  document.body.classList.remove('sider-resizing')
  document.body.style.userSelect = ''
  document.removeEventListener('mousemove', handleMouseMove)
  document.removeEventListener('mouseup', handleMouseUp)
})
</script>

<style scoped>
.custom-resize-handle {
  position: absolute;
  top: 0;
  height: 100%;
  width: 8px;
  z-index: 100;
  display: flex;
  justify-content: center;
  align-items: center;
}
.right-handle {
  left: -4px;
  cursor: default;
}
.resize-line {
  width: 1px;
  height: 100%;
  background-color: transparent;
  transition: all 0.2s ease;
}
.custom-resize-handle.active-resizing {
  cursor: col-resize !important;
}
.custom-resize-handle.active-resizing .resize-line {
  width: 4px;
  background-color: var(--primary-color) !important;
  box-shadow: 0 0 8px rgba(129, 182, 229, 0.6);
}

:deep(.inspector-tabs > .n-tabs-nav .n-tabs-nav-scroll-content) {
  width: 100%;
  display: flex;
}

:deep(.inspector-tabs > .n-tabs-nav .n-tabs-tab-wrapper) {
  flex: 1 1 0%;
  display: flex;
  justify-content: center;
}

:deep(.inspector-tabs > .n-tabs-nav .n-tabs-tab) {
  width: 100%;
  justify-content: center;
}
</style>
