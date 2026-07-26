<template>
  <n-tooltip trigger="hover" placement="bottom">
    <template #trigger>
      <span style="cursor: help;">
        <slot :total="totalTokens">
          {{ labelPrefix }}{{ totalTokens }}
        </slot>
      </span>
    </template>
    <div style="font-size: 0.8rem; line-height: 1.6; padding: 4px; color: #e3e3e7;">
      <div style="font-weight: bold; border-bottom: 1px solid #444; margin-bottom: 6px; padding-bottom: 4px;">
        {{ displayTitle }}
      </div>
      <div>{{ t('chat.inputToken') }} (Input): {{ inputTokens }}</div>
      <div>{{ t('chat.outputToken') }} (Output): {{ outputTokens }}</div>
      <div v-if="cachedTokens !== undefined">{{ t('chat.cachedToken') }} (Cached): {{ cachedTokens }}</div>
      <div v-if="inputTokens > 0 && cachedTokens !== undefined" style="color: var(--status-warning); font-weight: bold; margin-top: 4px; border-top: 1px dashed #444; padding-top: 4px;">
        {{ t('chat.cacheRatio') }} (Ratio): {{ ratioText }}%
      </div>
    </div>
  </n-tooltip>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { t } from '@/i18n'

const props = withDefaults(
  defineProps<{
    inputTokens: number
    outputTokens: number
    cachedTokens?: number
    title?: string
    labelPrefix?: string
  }>(),
  {
    labelPrefix: ''
  }
)

const displayTitle = computed(() => {
  return props.title || t('chat.contextDetails')
})

const totalTokens = computed(() => {
  return (props.inputTokens || 0) + (props.outputTokens || 0)
})

const ratioText = computed(() => {
  if (!props.inputTokens || !props.cachedTokens) return '0.0'
  return ((props.cachedTokens / props.inputTokens) * 100).toFixed(1)
})
</script>
