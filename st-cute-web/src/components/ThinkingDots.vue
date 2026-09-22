<template>
  <span class="thinking-dots">
    <span class="thinking-dot"></span>
    <span class="thinking-dot"></span>
    <span class="thinking-dot"></span>
  </span>
</template>

<script setup lang="ts">
/**
 * 三点跳动动效（通用"思考中/等待中"指示器）
 *
 * 抽象为共享组件的理由：助手消息正文区的「思考中指示」与工具末批的「等待子智能体返回」指示
 * 必须保持完全一致的视觉语言（周期、时延、缩放曲线、点径与间距）。
 * 若各处手抄一份样式，后续调整必然产生漂移，故以此为唯一实现源。
 *
 * 动画仅使用 transform: scale 与 opacity，两者均不触发浏览器布局重算，
 * 因此不会打扰 VirtualChatList 依赖 ResizeObserver 的高度测量与贴底补偿机制。
 */
</script>

<style scoped>
.thinking-dots {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.thinking-dot {
  width: 7px;
  height: 7px;
  background-color: var(--primary-color);
  border-radius: 50%;
  opacity: 0.4;
  animation: thinking-bounce 1.4s infinite both;
}

.thinking-dot:nth-child(1) {
  animation-delay: 0s;
}

.thinking-dot:nth-child(2) {
  animation-delay: 0.2s;
}

.thinking-dot:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes thinking-bounce {
  0%, 80%, 100% {
    transform: scale(0.6);
    opacity: 0.35;
  }
  40% {
    transform: scale(1.1);
    opacity: 0.85;
  }
}
</style>
