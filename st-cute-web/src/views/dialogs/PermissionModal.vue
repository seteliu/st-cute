<template>
  <n-modal
    v-model:show="appStore.showPermissionModal"
    preset="card"
    :title="appStore.currentPermissionReq?.subAgent ? t('permissionModal.titleSubAgent') : t('permissionModal.title')"
    style="width: 500px; background-color: #18181c; color: #fff;"
  >
    <div class="permission-modal-content">
      <div style="margin-bottom: 12px;">
        <n-alert type="warning" :title="appStore.currentPermissionReq?.subAgent ? t('permissionModal.alertTitleSubAgent') : t('permissionModal.alertTitle')" :bordered="false">
          <span v-if="appStore.currentPermissionReq?.subAgent">
            {{ t('permissionModal.requestDescSubAgent', { role: appStore.currentPermissionReq.subAgent.role }) }}
          </span>
          <span v-else>
            {{ t('permissionModal.requestDesc') }}
          </span>
        </n-alert>
      </div>
      <div
        class="info-card"
        style="margin-bottom: 15px; background-color: #101014; border: 1px solid #2d2d30; padding: 12px; border-radius: 6px;"
      >
        <div
          class="info-row"
          style="display: flex; justify-content: space-between; font-size: 0.85rem; margin-bottom: 8px;"
        >
          <span class="label" style="color: #a0a0a5;">{{ t('permissionModal.toolName') }}</span>
          <span class="val" style="color: var(--status-warning); font-weight: bold; font-family: monospace;">{{
            appStore.currentPermissionReq?.toolName
          }}</span>
        </div>
        <div style="margin-top: 8px;">
          <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px;">
            <span class="label" style="color: #a0a0a5; font-size: 0.85rem;">{{ t('permissionModal.arguments') }}</span>
            <n-switch v-model:value="appStore.isEditingArgs" size="small">
              <template #checked>{{ t('permissionModal.editArgs') }}</template>
              <template #unchecked>{{ t('permissionModal.startEdit') }}</template>
            </n-switch>
          </div>
          <div>
            <n-input
              v-if="appStore.isEditingArgs"
              v-model:value="appStore.editedArgumentsJson"
              type="textarea"
              :placeholder="t('permissionModal.editPlaceholder')"
              :autosize="{ minRows: 3, maxRows: 10 }"
              style="font-family: monospace; font-size: 0.85rem; background-color: #18181c; border-color: #2d2d30; color: #fff;"
              maxlength="10000"
            />
            <pre
              v-else
              style="background: #18181c; padding: 10px; border-radius: 4px; font-family: monospace; font-size: 0.85rem; color: #c2c2c9; margin: 0; overflow-x: auto; border: 1px solid #2d2d30; max-height: 200px;"
              >{{ appStore.formatArgumentsJson(appStore.currentPermissionReq?.arguments) }}</pre
            >
          </div>
        </div>
      </div>
      <div v-if="appStore.currentPermissionReq?.toolName === 'RunCommandTool' || appStore.currentPermissionReq?.toolName === 'execute_command'" style="margin-bottom: 15px;">
        <n-checkbox v-model:checked="appStore.alwaysAllowChecked">
          {{ t('permissionModal.alwaysAllowCmd') }}
        </n-checkbox>
        <div v-if="appStore.alwaysAllowChecked" style="margin-left: 22px; margin-top: 8px; display: flex; flex-direction: column; gap: 6px; background-color: #101014; border: 1px solid #2d2d30; padding: 10px; border-radius: 4px;">
          <n-radio-group v-model:value="appStore.permissionPatternMode" size="small">
            <n-space vertical>
              <n-radio value="exact">
                {{ t('chat.scopeExact') }}: <code style="color: var(--status-warning); font-family: monospace;">{{ appStore.commandExactPattern }}</code>
              </n-radio>
              <n-radio value="prefix">
                {{ t('chat.scopePrefix') }}: <code style="color: var(--status-warning); font-family: monospace;">{{ appStore.commandPrefixPattern }}</code>
              </n-radio>
              <n-radio value="all">
                {{ t('chat.scopeAll') }}: <code style="color: #ff4d4f; font-family: monospace;">*</code>
              </n-radio>
            </n-space>
          </n-radio-group>
        </div>
      </div>
      <div
        v-else-if="
          appStore.currentPermissionReq?.toolName === 'WriteFileTool' ||
          appStore.currentPermissionReq?.toolName === 'ModifyFileTool' ||
          appStore.currentPermissionReq?.toolName === 'write_to_file' ||
          appStore.currentPermissionReq?.toolName === 'replace_file_content'
        "
        style="margin-bottom: 15px;"
      >
        <n-checkbox v-model:checked="appStore.alwaysAllowChecked">
          {{ t('permissionModal.alwaysAllowFile') }}
        </n-checkbox>
        <div v-if="appStore.alwaysAllowChecked" style="margin-left: 22px; margin-top: 8px; display: flex; flex-direction: column; gap: 6px; background-color: #101014; border: 1px solid #2d2d30; padding: 10px; border-radius: 4px;">
          <n-radio-group v-model:value="appStore.permissionPatternMode" size="small">
            <n-space vertical>
              <n-radio value="exact">
                {{ t('chat.scopeExact') }}: <code style="color: var(--status-warning); font-family: monospace;">{{ appStore.fileExactPattern }}</code>
              </n-radio>
              <n-radio value="prefix">
                {{ t('chat.scopePrefix') }}: <code style="color: var(--status-warning); font-family: monospace;">{{ appStore.fileDirPattern }}</code>
              </n-radio>
              <n-radio value="all">
                {{ t('chat.scopeAll') }}: <code style="color: #ff4d4f; font-family: monospace;">*</code>
              </n-radio>
            </n-space>
          </n-radio-group>
        </div>
      </div>
    </div>
    <template #action>
      <n-space justify="end">
        <n-button type="error" @click="appStore.handlePermissionDecision('DENY')">{{ t('permissionModal.deny') }}</n-button>
        <n-button type="primary" @click="appStore.handlePermissionDecision('ALLOW')">{{ t('permissionModal.allow') }}</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { useAppStore } from '@/stores/app'
import { t } from '@/i18n'

const appStore = useAppStore()
</script>

<style scoped>
</style>
