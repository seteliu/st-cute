<template>
  <div class="pane-content">
    <div style="margin-bottom: 12px;">
      <h4 style="margin: 0; border: none; padding: 0; font-size: 0.85rem; color: #a0a0a5;">{{ t('inspector.mcpServer') }}</h4>
    </div>
    <div v-if="agentStore.mcpList.length === 0" class="empty-state" style="text-align: center; padding: 20px 0;">
      <span>{{ t('inspector.noMcp') }}</span>
    </div>
    <div v-else class="mcp-list" style="display: flex; flex-direction: column; gap: 12px;">
      <div
        v-for="mcp in agentStore.mcpList"
        :key="mcp.name"
        class="mcp-card"
        style="background: #101014; border: 1px solid #2d2d30; border-radius: 6px; padding: 12px;"
      >
        <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px;">
          <strong style="color: #fff; font-size: 0.9rem;">{{ mcp.name }}</strong>
          <span
            :class="['status-tag', mcp.status === 'RUNNING' ? 'running' : 'offline']"
            style="font-size: 0.7rem; padding: 1px 4px; border-radius: 3px;"
          >
            {{ mcp.status === 'RUNNING' ? t('inspector.running') : t('inspector.stopped') }}
          </span>
        </div>
        <div style="font-size: 0.75rem; color: var(--text-color-secondary); margin-bottom: 6px;">
          {{ t('sider.protocol') }}: {{ mcp.type }}
        </div>

        <div v-if="mcp.tools && mcp.tools.length > 0" style="margin-top: 8px; border-top: 1px dashed #2d2d30; padding-top: 8px;">
          <div style="font-size: 0.8rem; color: #a0a0a5; font-weight: bold; margin-bottom: 4px;">{{ t('chat.toolNameLabel') }}:</div>
          <div style="display: flex; flex-direction: column; gap: 6px; max-height: 200px; overflow-y: auto;">
            <div
              v-for="tool in mcp.tools"
              :key="tool.name"
              style="background: #18181c; padding: 6px; border-radius: 4px; border: 1px solid #2d2d30;"
            >
              <div style="color: var(--primary-color); font-weight: bold; font-family: monospace; font-size: 0.8rem;">
                {{ tool.name }}
              </div>
              <div style="font-size: 0.75rem; color: #a0a0a5; margin-top: 2px;">
                {{ tool.description }}
              </div>
            </div>
          </div>
        </div>
        <div v-else style="font-size: 0.75rem; color: var(--text-color-secondary); margin-top: 6px; font-style: italic;">
          {{ t('inspector.noData') }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useAgentStore } from '@/stores/agent'
import { t } from '@/i18n'

const agentStore = useAgentStore()
</script>

<style scoped>
</style>
