<template>
  <n-tooltip :trigger="trigger" placement="bottom" :show="tooltipVisible" @update:show="onTooltipShowChange">
    <template #trigger>
      <span style="cursor: help;">
        <slot :total="totalTokens">
          {{ labelPrefix }}{{ totalTokens }}
        </slot>
      </span>
    </template>
    <!-- 弹层正文刻意用正文色而非高亮色：明细数据为主阅读内容，层级交给加粗标题与紫色缓存比行区分 -->
    <div style="font-size: 0.8rem; line-height: 1.6; padding: 4px; color: var(--text-color);">
      <div style="font-weight: bold; border-bottom: 1px solid var(--border-color); margin-bottom: 6px; padding-bottom: 4px;">
        {{ displayTitle }}
      </div>
      <div>{{ t('chat.inputToken') }}：{{ inputTokens }}<template v-if="cachedTokens !== undefined">（缓存 {{ cachedTokens }}）</template></div>
      <div>{{ t('chat.outputToken') }}：{{ outputTokens }}</div>
      <div v-if="windowPercentage">{{ t('chat.windowToken') }}：{{ windowPercentage }}%</div>
      <div v-if="cid" style="color: var(--accent-color); font-weight: bold; margin-top: 6px;">
        {{ t('chat.sessionCacheRatio') }}：<template v-if="cacheRatioLoading"><n-spin :size="11" /></template><template v-else-if="cacheRatioText !== null">{{ cacheRatioText }}%</template><template v-else>--</template>
      </div>
    </div>
  </n-tooltip>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { t } from '@/i18n'
import { getConversationCacheRatioApi } from '@/api/conversation'

const props = withDefaults(
  defineProps<{
    inputTokens: number
    outputTokens: number
    cachedTokens?: number
    title?: string
    labelPrefix?: string
    /** 弹层触发方式：PC 悬浮 / 移动端点击 */
    trigger?: 'hover' | 'click'
    /** 会话 ID：传入才会启用「累计缓存占比」懒查询 */
    cid?: number
    /** 窗口上限（token 数）：无值时隐藏「当前窗口」行 */
    contextLimit?: number | null
  }>(),
  {
    labelPrefix: '',
    trigger: 'hover'
  }
)

const displayTitle = computed(() => {
  return props.title || t('chat.contextDetails')
})

const totalTokens = computed(() => {
  return (props.inputTokens || 0) + (props.outputTokens || 0)
})

/** 当前窗口占用百分比（1 位小数）：换算仅前端处理，窗口未就绪时隐藏该行 */
const windowPercentage = computed<string | null>(() => {
  const limit = props.contextLimit
  if (!limit) return null
  return ((totalTokens.value / limit) * 100).toFixed(1)
})

// ── 累计缓存占比：弹层弹出才查询，不缓存结果（数据随对话增长变化，每次弹出取最新值）。
// 后端返回小数制 4 位小数，此处统一转为百分比 1 位小数展示（换算仅前端处理） ──
const tooltipVisible = ref(false)
const cacheRatioLoading = ref(false)
const cacheRatioText = ref<string | null>(null)

const onTooltipShowChange = (show: boolean) => {
  tooltipVisible.value = show
  if (!show) {
    // 弹层关闭后作废旧值，保证下次唤起时重新拉取最新数据（不持久缓存）
    cacheRatioText.value = null
  }
}

const loadCacheRatio = async () => {
  if (!props.cid || cacheRatioLoading.value) return
  cacheRatioLoading.value = true
  try {
    const ratio = await getConversationCacheRatioApi(props.cid)
    cacheRatioText.value = (Number(ratio) * 100).toFixed(1)
  } catch (e) {
    console.error('查询会话缓存比失败:', e)
    cacheRatioText.value = null
  } finally {
    cacheRatioLoading.value = false
  }
}

// 弹层可见性变化：首次弹出时发起查询
watch(tooltipVisible, (visible) => {
  if (visible) {
    loadCacheRatio()
  }
})

// 会话切换：旧结果立即作废；弹层保持展开时原地重查新会话，避免串显其他会话的比值
watch(
  () => props.cid,
  () => {
    cacheRatioText.value = null
    if (tooltipVisible.value) {
      loadCacheRatio()
    }
  }
)
</script>
