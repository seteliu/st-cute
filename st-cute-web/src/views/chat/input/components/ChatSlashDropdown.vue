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
  /* 边框用品牌辅助色描边（紫色系）+ 30% 透明度：弹框浮在正文之上，淡叠加边框压在内容上时
     轮廓不清，紫色描边锚定浮层边界，低透明度只留淡淡紫色倾向不过艳
     （dark 浅紫 / light 深紫，随主题自动适配；color-mix 基于 WebView2 Chromium 111+ 支持） */
  border: 1px solid color-mix(in srgb, var(--accent-color) 30%, transparent);
  /* 圆角用紧凑档（8px）：弹框是密集滚动列表而非宽松容器，12px 弧段过长导致
     半透明边框在弧段处发虚不自然，退回 8px 更利落（曾尝试与 .input-area 对齐 12px，视觉不佳） */
  border-radius: 8px;
  /* 阴影与主输入框 .input-area 常态同款（卡片档令牌）：跟随主题自动调浓淡，
     替换原硬编码 rgba(0, 0, 0, 0.45)——该浓度是 dark 主题 overlay 最高档，
     写死后在浅色主题下脏重刺眼，且高于二级弹层应有的浮起层级 */
  box-shadow: var(--shadow-card), 0 0 1px 1px var(--overlay-veil);
  z-index: 15;
  scrollbar-width: thin;
}

.slash-dropdown::-webkit-scrollbar {
  width: 4px;
}

.slash-dropdown::-webkit-scrollbar-thumb {
  background-color: var(--scrollbar-thumb);
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
