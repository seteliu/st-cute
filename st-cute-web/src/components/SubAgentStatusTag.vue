<template>
  <span :class="['subagent-status-tag', statusClass]">
    {{ statusLabel }}
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  status: 'running' | 'success' | 'failed' | string
}>()

const statusClass = computed(() => {
  const s = props.status?.toLowerCase()
  if (s === 'running' || s === 'success') return 'success-state'
  return 'failed-state'
})

const statusLabel = computed(() => {
  const s = props.status?.toLowerCase()
  if (s === 'running') return '运行中'
  if (s === 'success') return '正常结束'
  return '异常终止'
})
</script>

<style scoped>
.subagent-status-tag {
  display: inline-flex;
  align-items: center;
  font-size: 0.72rem;
  font-weight: bold;
  padding: 1px 6px;
  border-radius: 4px;
  line-height: 1.4;
  user-select: none;
  flex-shrink: 0;
  white-space: nowrap;
}

.success-state {
  color: var(--primary-color);
  background-color: rgba(129, 182, 229, 0.1);
  border: 1px solid rgba(129, 182, 229, 0.2);
}

.failed-state {
  color: var(--status-error);
  background-color: rgba(208, 48, 80, 0.1);
  border: 1px solid rgba(208, 48, 80, 0.2);
}
</style>
