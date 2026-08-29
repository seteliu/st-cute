<template>
  <n-drawer v-model:show="appStore.showLogDrawer" :width="width" placement="right">
    <n-drawer-content :title="t('logDrawer.title')" closable style="background-color: #18181c; color: #fff;">
      <div style="display: flex; flex-direction: column; gap: 12px; height: 100%;">

        <!-- 工具名称：黑框外独立标题，置于抽屉内容最顶部 -->
        <div v-if="appStore.currentViewToolCall" class="log-section-title">
          {{ t('chat.toolNameLabel') }}: {{ formatToolName(appStore.currentViewToolCall.name) }}
        </div>

        <!-- 调用参数：标题在黑框外，内容黑框与执行结果统一样式 -->
        <template v-if="appStore.currentViewToolCall">
          <div class="log-section-title">{{ t('chat.argumentsLabel') }}:</div>
          <pre class="log-pre log-pre-args">{{ formattedToolCall }}</pre>
        </template>

        <!-- 执行结果展示 -->
        <div style="flex: 1; display: flex; flex-direction: column; min-height: 0; gap: 12px;">
          <!-- 没截断时：直接显示全部内容 -->
          <template v-if="!appStore.currentViewMessage || !appStore.currentViewMessage.beforeCompactContent">
            <div class="log-section-title">{{ t('chat.resultLabel') }}:</div>
            <pre
              class="log-pre log-pre-main"
              >{{ resultDisplay }}</pre>
          </template>

          <!-- 截断时：多显示一个截断前的完整日志 -->
          <template v-else>
            <!-- 1. 精简摘要 (content) -->
            <div style="display: flex; flex-direction: column; gap: 4px; max-height: 200px; flex-shrink: 0; min-height: 0;">
              <div class="log-section-title">{{ t('chat.compactSummaryLabel') }}:</div>
              <pre
                class="log-pre"
                style="flex: 1; min-height: 0;"
                >{{ compactSummaryDisplay }}</pre>
            </div>

            <!-- 2. 截断前的完整大日志 (beforeCompactContent) -->
            <div style="flex: 1; display: flex; flex-direction: column; min-height: 0;">
              <div class="log-section-title">{{ t('chat.rawOutputLabel') }}:</div>
              <pre
                class="log-pre log-pre-main"
                >{{ rawOutputDisplay }}</pre>
            </div>
          </template>
        </div>
      </div>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useAppStore } from '@/stores/app'
import { useResponsive } from '@/utils/useResponsive'
import { t } from '@/i18n'
import { formatToolName as sharedFormatToolName } from '@/utils/toolName'

const { isMobile } = useResponsive()

const width = computed(() => {
  return isMobile.value ? '100%' : 650
})

const appStore = useAppStore()

const formatToolName = (name: string) => {
  return sharedFormatToolName(name)
}

/**
 * 清洗终端 ANSI 控制序列（颜色、光标移动等，如 \u001b[36m），仅用于展示层
 */
const stripAnsi = (text: string): string => {
  return text.replace(/\u001b\[[0-9;?]*[A-Za-z]/g, '')
}

/**
 * 将美化后 JSON 中残留的字面转义序列（\r\n、\n、\t）还原为真实换行/制表符，仅用于展示层提升可读性。
 * 使用负向后行断言避免误伤「转义反斜杠 + n」这类合法序列（如 Windows 路径 P:\\note）。
 */
const unescapePrettyJson = (text: string): string => {
  return text
    .replace(/(?<!\\)\\r\\n/g, '\n')
    .replace(/(?<!\\)\\n/g, '\n')
    .replace(/(?<!\\)\\t/g, '\t')
}

/**
 * 执行结果通用展示格式化：
 * 1. 清洗 ANSI 控制序列；
 * 2. 内容为合法 JSON（对象/数组）时展开为缩进美化格式，并还原值内的换行转义；
 * 3. 解析失败时按纯文本原样展示。
 */
const formatDisplayText = (raw?: string | null): string => {
  if (!raw) return ''
  const cleaned = stripAnsi(raw)
  const trimmed = cleaned.trim()
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      return unescapePrettyJson(JSON.stringify(JSON.parse(trimmed), null, 2))
    } catch (e) {
      // 非法 JSON，按纯文本展示
    }
  }
  return cleaned
}

// 调用参数完整展示：组合 toolId、toolName、toolArguments 三个字段。
// toolArguments 解析为对象后放入 payload，外层序列化自然嵌套展开，
// 避免字符串字段换行被二次转义为 \n 字面噪音
const formattedToolCall = computed(() => {
  const msg = appStore.currentViewMessage
  if (!msg) return '{}'
  let argsObj: unknown = {}
  const rawArgs = msg.toolArguments
  if (rawArgs) {
    if (typeof rawArgs === 'object') {
      argsObj = rawArgs
    } else {
      try {
        argsObj = JSON.parse(rawArgs)
      } catch (e) {
        // 非法 JSON 保持字符串原样
        argsObj = String(rawArgs)
      }
    }
  }
  const payload = {
    toolId: msg.toolId || '',
    toolName: msg.toolName || '',
    toolArguments: argsObj
  }
  return unescapePrettyJson(JSON.stringify(payload, null, 2))
})

// 执行结果展示文本（未截断场景）
const resultDisplay = computed(() => {
  const msg = appStore.currentViewMessage
  if (!msg) return appStore.rawLogContent
  return formatDisplayText(msg.content)
})

// 压缩摘要展示文本（截断场景）
const compactSummaryDisplay = computed(() => {
  const msg = appStore.currentViewMessage
  return msg ? formatDisplayText(msg.content) : ''
})

// 压缩前完整原始输出展示文本（截断场景）
const rawOutputDisplay = computed(() => {
  const msg = appStore.currentViewMessage
  return msg ? formatDisplayText(msg.beforeCompactContent) : ''
})
</script>

<style scoped>
/* 区块标题统一样式：蓝色加粗，位于黑框外 */
.log-section-title {
  font-weight: bold;
  color: var(--primary-color);
  font-size: 0.95rem;
  font-family: monospace;
  padding: 0 2px;
}

/* 内容黑框统一样式：深底 + 细边框，等宽字体 */
.log-pre {
  background: #101014;
  color: #c2c2c9;
  padding: 12px 15px;
  border-radius: 6px;
  border: 1px solid #2d2d30;
  font-family: monospace;
  font-size: 0.8rem;
  margin: 0;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

/* 调用参数黑框：固定最大高度，超出滚动 */
.log-pre-args {
  max-height: 180px;
  flex-shrink: 0;
}

/* 执行结果主黑框：撑满剩余空间 */
.log-pre-main {
  flex: 1;
  min-height: 0;
}
</style>
