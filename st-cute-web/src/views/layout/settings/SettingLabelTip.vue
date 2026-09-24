<template>
  <!-- 设置项标签 + 悬浮问号说明：统一「标签文案 + 问号图标 + tooltip」样板。
       说明内容默认取 tip 属性；复杂排版内容（如密码项多行说明）经默认插槽传入 -->
  <span class="setting-label-tip">
    <span>{{ label }}</span>
    <n-tooltip trigger="hover" placement="top-start">
      <template #trigger>
        <span class="tip-trigger">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10"></circle>
            <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"></path>
            <line x1="12" y1="17" x2="12.01" y2="17"></line>
          </svg>
        </span>
      </template>
      <div class="tip-content">
        <slot>{{ tip }}</slot>
      </div>
    </n-tooltip>
  </span>
</template>

<script setup lang="ts">
defineProps<{
  /** 标签文案 */
  label: string
  /** 悬浮说明文案（未提供默认插槽内容时展示） */
  tip?: string
}>()
</script>

<style scoped>
/* 根节点为行内 flex 容器：标签与问号图标同行对齐，间距对齐 .setting-item-label 的 gap */
.setting-label-tip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* 问号触发器：弱化着色 + help 光标，提示可悬浮查看说明 */
.tip-trigger {
  cursor: help;
  color: var(--text-color-muted);
  display: inline-flex;
  align-items: center;
}

/* 说明内容：限宽与紧凑排版，多行说明的纵向留白由插槽内容自带 */
.tip-content {
  max-width: 280px;
  font-size: 0.8rem;
  line-height: 1.6;
}
</style>
