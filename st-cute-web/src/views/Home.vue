<template>
  <div class="app-container">
    <n-layout has-sider class="main-layout">
      <!-- 左栏：会话目录 (桌面端) -->
      <left-sider v-if="!isMobile" />

      <!-- 中栏：当前会话聊天区 -->
      <chat-container />

      <!-- 右栏：审查与标签页面板 (桌面端) -->
      <right-sider v-if="!isMobile" />
    </n-layout>

    <!-- 移动端侧边栏 Drawer -->
    <n-drawer v-if="isMobile" v-model:show="showLeftDrawer" placement="left" :width="280" body-style="padding: 0; background-color: var(--bg-color-card);">
      <left-sider />
    </n-drawer>
    <n-drawer v-if="isMobile" v-model:show="showRightDrawer" placement="right" :width="320" body-style="padding: 0; background-color: var(--bg-color-card);">
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

const { isMobile } = useResponsive()

// 实时事件编排（WS 监听注册/注销与 git 轮询）整体委托给 composable
useRealtimeEvents()

const showLeftDrawer = computed({
  get: () => !appStore.leftSiderCollapsed,
  set: (val) => { appStore.leftSiderCollapsed = !val }
})
const showRightDrawer = computed({
  get: () => !appStore.rightSiderCollapsed,
  set: (val) => { appStore.rightSiderCollapsed = !val }
})

watch(isMobile, (newVal) => {
  if (newVal) {
    appStore.leftSiderCollapsed = true
    appStore.rightSiderCollapsed = true
  }
}, { immediate: true })

onMounted(async () => {
  // 0. 初始化移动端响应式检测
  initResponsive()

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
