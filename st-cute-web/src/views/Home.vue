<template>
  <div class="app-container">
    <n-layout has-sider class="main-layout">
      <!-- 左栏：会话目录（常驻三栏布局；移动端/窄桌面档改为下方 Drawer 承载） -->
      <left-sider v-if="!isOverlaySider" />

      <!-- 中栏：当前会话聊天区 -->
      <chat-container />

      <!-- 右栏：审查与标签页面板（常驻三栏布局；移动端/窄桌面档改为下方 Drawer 承载） -->
      <right-sider v-if="!isOverlaySider" />
    </n-layout>

    <!-- 移动端 / 窄桌面档侧边栏 Drawer：
         移动端 < 768 全屏抽屉（保持既有处理）；窄桌面档 768~1251 侧栏悬浮抽屉化（默认折叠），
         抽屉宽度沿用用户拖拽记忆的侧栏宽度，弹出时中间聊天区独占全宽 -->
    <n-drawer
      v-if="isOverlaySider"
      v-model:show="showLeftDrawer"
      placement="left"
      :width="drawerLeftWidth"
      body-style="padding: 0; background-color: var(--bg-color-card);"
    >
      <left-sider />
    </n-drawer>
    <n-drawer
      v-if="isOverlaySider"
      v-model:show="showRightDrawer"
      placement="right"
      :width="drawerRightWidth"
      body-style="padding: 0; background-color: var(--bg-color-card);"
    >
      <right-sider />
    </n-drawer>

    <!-- 全局弹窗与抽屉 -->
    <raw-log-drawer />
    <sub-agent-drawer />
    <thought-detail-drawer />
  </div>
</template>

<script setup lang="ts">
import { onMounted, computed, watch } from 'vue'
import { initResponsive, useResponsive } from '@/utils/useResponsive'
import { useRealtimeEvents } from '@/composables/useRealtimeEvents'
import {
  clampSiderWidth,
  MOBILE_DRAWER_LEFT_WIDTH,
  MOBILE_DRAWER_RIGHT_WIDTH
} from '@/constants/layout'

// 状态 Store 引入
import { useAppStore } from '@/stores/app'
import { useConversationStore } from '@/stores/conversation'
import { useProviderStore } from '@/stores/provider'
import { useProjectStore } from '@/stores/project'

// 布局组件引入
import LeftSider from '@/views/layout/LeftSider.vue'
import ChatContainer from '@/views/chat/ChatContainer.vue'
import RightSider from '@/views/layout/RightSider.vue'

// 弹框/抽屉组件引入
import RawLogDrawer from '@/views/dialogs/RawLogDrawer.vue'
import SubAgentDrawer from '@/views/dialogs/SubAgentDrawer.vue'
import ThoughtDetailDrawer from '@/views/dialogs/ThoughtDetailDrawer.vue'
import { t } from '@/i18n'

const appStore = useAppStore()
const conversationStore = useConversationStore()
const providerStore = useProviderStore()
const projectStore = useProjectStore()

// 响应式档位必须在 setup 顶层同步初始化，不能拖到 onMounted：
// 下方的档位 watch 带 immediate，首帧即按档位钳制并回写侧栏记忆宽度。
// 若此时档位还是默认值（窄桌面档被误判为三栏），首帧会按三栏预算把记忆宽度压到硬下限，
// 窗口拉宽后无法自行恢复——这是永久性的用户数据降级，非单纯的一帧视觉闪跳
initResponsive()

const { isOverlaySider, isNarrowDesktop } = useResponsive()

// 实时事件编排（WS 监听注册/注销与 git 轮询）整体委托给 composable
useRealtimeEvents()

// 抽屉弹出宽度：窄桌面档沿用用户拖拽记忆的侧栏宽度（体验连续），移动端用固定宽度
const drawerLeftWidth = computed(() =>
  isNarrowDesktop.value ? appStore.leftSiderWidth : MOBILE_DRAWER_LEFT_WIDTH
)
const drawerRightWidth = computed(() =>
  isNarrowDesktop.value ? appStore.rightSiderWidth : MOBILE_DRAWER_RIGHT_WIDTH
)

const showLeftDrawer = computed({
  get: () => !appStore.leftSiderCollapsed,
  set: (val) => {
    appStore.leftSiderCollapsed = !val
  }
})
const showRightDrawer = computed({
  get: () => !appStore.rightSiderCollapsed,
  set: (val) => {
    appStore.rightSiderCollapsed = !val
  }
})

// 跌破抽屉化断点（进入移动端或窄桌面档）时预设两侧折叠：仅单向收敛、拉宽不自动弹开，
// 避免窗口在断点附近来回拖动时抽屉反复自动展开造成布局抖动。
// 反向回到三栏时，把两侧记忆宽度按当前窗口重新钳制：防止「超宽窗口下拖出的宽度组合」
// 在收窄后又拉宽还原时，因左右之和超出当前窗口预算而挤压中间聊天区。
// 注意此处按两侧记忆宽度（而非当前折叠态）计算：跌入断点时两侧刚被折叠，若按折叠态
// 让出空间，超宽组合会被原样保留，用户随后展开侧栏即刻挤压中间聊天区
watch(
  isOverlaySider,
  (val) => {
    if (val) {
      appStore.leftSiderCollapsed = true
      appStore.rightSiderCollapsed = true
    } else {
      // 先钳左（按右记忆宽），再钳右（按钳后的左值），两步后左右之和必然 ≤ 窗口宽 − CHAT_MIN_WIDTH
      appStore.leftSiderWidth = clampSiderWidth(
        'left',
        appStore.leftSiderWidth,
        appStore.rightSiderWidth
      )
      appStore.rightSiderWidth = clampSiderWidth(
        'right',
        appStore.rightSiderWidth,
        appStore.leftSiderWidth
      )
    }
  },
  { immediate: true }
)

onMounted(async () => {
  try {
    // 1. 并行加载与项目无关的全局系统配置底座
    const res1 = await Promise.allSettled([
      appStore.loadBasicConfig(),
      providerStore.loadProviders()
    ])
    if (res1[0].status === 'rejected') {
      ;(window as any).$message?.error(t('home.basicConfigError'))
    }
    if (res1[1].status === 'rejected') {
      ;(window as any).$message?.error(t('home.providerConfigError'))
    }

    // 2. 严格串行加载项目列表与依赖它的会话列表
    try {
      await projectStore.loadProjects()
    } catch (e) {
      ;(window as any).$message?.error(t('home.projectListError'))
    }

    try {
      await conversationStore.loadConversations()
    } catch (e) {
      ;(window as any).$message?.error(t('home.historyError'))
    }
  } catch (e) {
    console.error('初始化配置拉取发生异常:', e)
  } finally {
    appStore.isInitialized = true
  }
})
</script>
