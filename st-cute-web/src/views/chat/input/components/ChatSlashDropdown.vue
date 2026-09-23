<template>
  <div v-if="visible" class="slash-dropdown">
    <div v-if="loading" class="slash-hint">{{ t('common.loading') }}</div>
    <div v-else-if="renderGroups.length === 0" class="slash-hint">{{ t('chat.slashNoMatch') }}</div>
    <div v-else>
      <div v-for="group in renderGroups" :key="group.group" class="slash-group">
        <div class="slash-group-title">{{ group.group }}</div>
        <div
          v-for="(item, idx) in group.items"
          :key="item.name"
          :ref="el => emit('setItemRef', el, group.startFlatIndex + idx)"
          class="slash-item"
          :class="{ 'slash-item-active': group.startFlatIndex + idx === highlightIndex }"
          @mousedown.prevent
          @click="emit('select', item)"
          @mousemove="emit('update:highlightIndex', group.startFlatIndex + idx)"
        >
          <div class="slash-item-name">/{{ item.name }}</div>
          <div v-if="item.description" class="slash-item-desc" :title="item.description">{{ item.description }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { SlashItem } from '@/types'
import { t } from '@/i18n'

defineProps<{
  visible: boolean
  loading: boolean
  renderGroups: Array<{ group: string; items: SlashItem[]; startFlatIndex: number }>
  highlightIndex: number
}>()

const emit = defineEmits<{
  (e: 'select', item: SlashItem): void
  (e: 'update:highlightIndex', index: number): void
  (e: 'setItemRef', el: any, index: number): void
}>()
</script>

<style scoped>
.slash-dropdown {
  position: absolute;
  /* 弹出在输入区上方，与 .chat-footer 顶部保持 4px 间距 */
  bottom: calc(100% + 4px);
  left: 8px;
  right: 8px;
  max-height: 280px;
  overflow-y: auto;
  background-color: var(--bg-color-elevated);
  border: 1px solid var(--overlay-veil-strong);
  border-radius: 8px;
  box-shadow: 0 -4px 16px rgba(0, 0, 0, 0.45);
  z-index: 15;
  scrollbar-width: thin;
}

.slash-dropdown::-webkit-scrollbar {
  width: 4px;
}

.slash-dropdown::-webkit-scrollbar-thumb {
  background-color: var(--overlay-veil-strong);
  border-radius: 2px;
}

.slash-dropdown::-webkit-scrollbar-track {
  background-color: transparent;
}

.slash-hint {
  padding: 10px 12px;
  font-size: 0.78rem;
  color: var(--text-color-faint);
  text-align: center;
}

.slash-group + .slash-group {
  border-top: 1px solid var(--overlay-veil);
}

.slash-group-title {
  padding: 6px 12px 4px;
  font-size: 0.68rem;
  font-weight: 600;
  color: var(--text-color-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.slash-item {
  padding: 6px 12px;
  cursor: pointer;
  transition: background-color 0.15s;
}

/* 高亮统一由 slash-item-active 类呈现（键盘导航与鼠标悬浮共用同一来源，
   避免与 :hover 伪类叠加出现双高亮），鼠标悬浮经 mousemove 设置高亮索引 */
.slash-item-active {
  background-color: var(--primary-bg-weak);
}

.slash-item-name {
  font-size: 0.8rem;
  color: var(--text-color-bright);
  font-family: monospace;
}

.slash-item-desc {
  margin-top: 2px;
  font-size: 0.7rem;
  color: var(--text-color-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
