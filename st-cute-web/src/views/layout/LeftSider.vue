<template>
  <n-layout-sider
    collapse-mode="width"
    :collapsed-width="0"
    :collapsed="collapsed"
    :width="width"
    bordered
    class="left-sider"
    content-style="display: flex; flex-direction: column; height: 100%;"
  >
    <div class="sider-header">
      <span class="brand-text">ST-Cute</span>
    </div>
    
    <div class="sider-actions">
      <n-button class="add-project-btn" block @click="openAddProject">
        + {{ t('sider.addProject') }}
      </n-button>
    </div>

    <div class="project-collapse-wrapper">
      <div v-if="projectStore.projectList.length === 0" class="empty-projects">
        {{ t('sider.noProjects') }}
      </div>
      
      <n-collapse
        v-else
        v-model:expanded-names="expandedNames"
        @update:expanded-names="handleExpandedChange"
        arrow-placement="left"
      >
        <n-collapse-item
          v-for="proj in projectStore.projectList"
          :key="proj.id"
          :name="proj.id"
        >
          <template #header>
            <div class="project-title" :class="{ 'active-project': projectStore.activeProjectId === proj.id }">
              <n-ellipsis style="max-width: 100%">
                {{ proj.name }}
              </n-ellipsis>
            </div>
          </template>
          <template #header-extra>
            <div class="project-actions" @click.stop>
              <n-button
                size="tiny"
                quaternary
                circle
                @click="conversationStore.createConversation(proj.id)"
                :title="t('sider.newChat')"
              >
                <template #icon>
                  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                    <line x1="12" y1="5" x2="12" y2="19"></line>
                    <line x1="5" y1="12" x2="19" y2="12"></line>
                  </svg>
                </template>
              </n-button>
              <n-popconfirm
                @positive-click="projectStore.handleDeleteProject(proj.id)"
                positive-text="确认"
                negative-text="取消"
                placement="bottom-end"
              >
                <template #trigger>
                  <n-button
                    size="tiny"
                    quaternary
                    circle
                    class="delete-proj-btn"
                    title="删除项目"
                  >
                    <template #icon>
                      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                        <polyline points="3 6 5 6 21 6"></polyline>
                        <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                      </svg>
                    </template>
                  </n-button>
                </template>
                确定删除该项目吗？
              </n-popconfirm>
            </div>
          </template>

          <!-- 项目下的会话列表 -->
          <div class="conversation-list">
            <div
              v-if="!conversationsByProject[proj.id] || conversationsByProject[proj.id].length === 0"
              class="empty-conversations"
            >
              暂无会话
            </div>
            <div
              v-else
              v-for="sess in conversationsByProject[proj.id]"
              :key="sess.id"
              :class="['conversation-item', conversationStore.activeCid === sess.id ? 'active' : '']"
              @click="conversationStore.selectConversation(sess.id)"
              style="position: relative;"
            >
              <!-- 编辑状态的输入框 -->
              <div v-if="editingCid === sess.id" class="conversation-edit-wrapper" @click.stop>
                <n-input
                  v-model:value="editingTitle"
                  size="tiny"
                  ref="editInputRef"
                  @blur="saveTitle(sess)"
                  @keyup.enter="saveTitle(sess)"
                  @keyup.esc="cancelEdit"
                  maxlength="50"
                />
              </div>
              <template v-else>
                <div class="conversation-title">{{ sess.title }}</div>
                <div class="conversation-meta">{{ formatTime(sess.updatedAt) }}</div>
                
                <!-- 重命名按钮 -->
                <span
                  class="edit-conversation-btn"
                  @click.stop="startEdit(sess)"
                  title="重命名会话"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="width: 11px; height: 11px;">
                    <path d="M12 20h9"></path>
                    <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"></path>
                  </svg>
                </span>

                <!-- 删除按钮 -->
                <n-popconfirm
                  @positive-click="conversationStore.deleteConversation(sess.id)"
                  :positive-text="t('common.confirm')"
                  :negative-text="t('common.cancel')"
                  placement="bottom-end"
                >
                  <template #trigger>
                    <span
                      class="delete-conversation-btn"
                      @click.stop
                    >
                      ✕
                    </span>
                  </template>
                  {{ t('sider.deleteConfirmContent') }}
                </n-popconfirm>
              </template>
            </div>
          </div>
        </n-collapse-item>
      </n-collapse>
    </div>

    <!-- 底部固定展示：系统设置 -->
    <div class="sider-footer" style="padding: 12px; border-top: 1px solid #2d2d30; margin-top: auto;">
      <n-button block quaternary @click="openSettingsModal">
        <template #icon>
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
        </template>
        {{ t('settings.title') }}
      </n-button>
    </div>

    <!-- 添加项目弹窗 -->
    <n-modal
      v-model:show="showAddProjectModal"
      preset="card"
      style="width: 480px; max-width: 90vw;"
      :title="t('sider.addProject')"
      :bordered="false"
      size="medium"
    >
      <div style="margin-top: 5px;">
        <n-form label-placement="left" label-width="110">
          <n-form-item>
            <template #label>
              <span style="color: var(--status-error); margin-right: 4px;">*</span>{{ t('sider.projectPath') }}
            </template>
            <n-input
              v-model:value="newProjectForm.path"
              :placeholder="t('sider.projectPathPlaceholder')"
              @input="onPathInput"
              maxlength="255"
            />
          </n-form-item>
          <n-form-item>
            <template #label>
              <span style="color: var(--status-error); margin-right: 4px;">*</span>{{ t('sider.projectName') }}
            </template>
            <n-input
              v-model:value="newProjectForm.name"
              :placeholder="t('sider.projectNamePlaceholder')"
              maxlength="50"
            />
          </n-form-item>
        </n-form>
      </div>
      <template #footer>
        <div style="display: flex; justify-content: flex-end; gap: 12px;">
          <n-button type="primary" @click="submitAddProject">{{ t('common.confirm') }}</n-button>
          <n-button @click="showAddProjectModal = false">{{ t('common.cancel') }}</n-button>
        </div>
      </template>
    </n-modal>

    <!-- 系统设置大弹窗 -->
    <n-modal
      v-model:show="showSettingsModal"
      preset="card"
      style="width: 760px; max-width: 95vw; min-height: 480px;"
      :title="isAddingOrEditingProvider ? (providerStore.isEditing ? t('sider.editProvider') : t('sider.addProvider')) : t('settings.title')"
      :bordered="false"
      size="medium"
    >
      <!-- 供应商专属子页面 -->
      <div v-if="isAddingOrEditingProvider" class="provider-sub-view">
        <n-form :label-placement="isMobile ? 'top' : 'left'" label-width="135" style="margin-top: 15px;">
          <n-form-item>
            <template #label>
              <span style="color: var(--status-error); margin-right: 4px;">*</span>{{ t('sider.protocol') }}
            </template>
            <n-select v-model:value="providerStore.form.protocol" :options="providerStore.protocolOptions" />
          </n-form-item>
          <n-form-item>
            <template #label>
              <span style="color: var(--status-error); margin-right: 4px;">*</span>{{ t('sider.groupName') }} (Group)
            </template>
            <n-input v-model:value="providerStore.form.group" placeholder="例如: openrouter" maxlength="50" />
          </n-form-item>
          <n-form-item>
            <template #label>
              <span style="color: var(--status-error); margin-right: 4px;">*</span>{{ t('sider.modelName') }}
            </template>
            <n-input v-model:value="providerStore.form.modelName" placeholder="例如: gpt-4" maxlength="100" />
          </n-form-item>
          <n-form-item>
            <template #label>
              <span style="color: var(--status-error); margin-right: 4px;">*</span>{{ t('sider.baseUrl') }}
            </template>
            <n-input v-model:value="providerStore.form.baseUrl" placeholder="例如: https://openrouter.ai/api/v1" maxlength="255" />
          </n-form-item>
          <n-form-item>
            <template #label>
              <span style="color: var(--status-error); margin-right: 4px;">*</span>{{ t('sider.apiKey') }}
            </template>
            <n-input
              v-model:value="providerStore.form.apiKey"
              :placeholder="t('sider.apiKeyPlaceholder')"
              maxlength="255"
            />
          </n-form-item>
          <n-form-item>
            <template #label>
              <span style="color: var(--status-error); margin-right: 4px;">*</span>{{ t('sider.contextSize') }}
            </template>
            <n-input-number v-model:value="providerStore.form.contextSize" :min="50000" :max="10000000" :step="1000" :placeholder="t('sider.contextSizePlaceholder')" style="width: 100%;" :input-props="{ spellcheck: 'false' }" />
          </n-form-item>
          <n-form-item :label="t('sider.maxTokens')">
            <n-input-number
              v-model:value="providerStore.form.maxTokens"
              :min="1"
              :max="128000"
              :placeholder="t('sider.maxTokensPlaceholder')"
              :clearable="true"
              style="width: 100%;"
            />
          </n-form-item>
          <n-form-item :label="t('sider.reasoningEffort')">
            <n-input
              v-model:value="providerStore.form.reasoningEffort"
              :placeholder="t('sider.reasoningEffortPlaceholder')"
              maxlength="50"
              :clearable="true"
            />
          </n-form-item>
          <n-form-item :label="t('sider.temperature')">
            <n-input-number
              v-model:value="providerStore.form.temperature"
              :min="0"
              :max="2"
              :step="0.1"
              :placeholder="t('sider.temperaturePlaceholder')"
              :clearable="true"
              style="width: 100%;"
            />
          </n-form-item>
        </n-form>
        <div style="display: flex; justify-content: flex-end; gap: 12px; margin-top: 20px;">
          <n-button type="primary" @click="saveProviderAndReturn">{{ t('common.save') }}</n-button>
          <n-button @click="cancelProviderEdit">{{ t('common.back') }}</n-button>
        </div>
      </div>

      <!-- 设置主界面（Tab卡片） -->
      <div v-else class="settings-main-view">
        <n-tabs v-model:value="activeTab" placement="top" animated>
          <!-- 卡片1：基础设置 -->
          <n-tab-pane name="basic" :tab="t('settings.basicTab')">
            <div class="settings-pane-content">
              <!-- 语言设置（放在最前面） -->
              <div class="setting-item-row setting-item-row--stack">
                <div class="setting-item-label">
                  <span>{{ t('settings.language') }}</span>
                  <n-tooltip trigger="hover" placement="top-start">
                    <template #trigger>
                      <span style="cursor: help; color: var(--text-color-muted); display: inline-flex; align-items: center;">
                        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                          <circle cx="12" cy="12" r="10"></circle>
                          <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"></path>
                          <line x1="12" y1="17" x2="12.01" y2="17"></line>
                        </svg>
                      </span>
                    </template>
                    <div style="max-width: 280px; font-size: 0.8rem; line-height: 1.6;">
                      {{ t('settings.languageTooltip') }}
                    </div>
                  </n-tooltip>
                </div>
                <n-select
                  v-model:value="appStore.language"
                  :options="[
                    { label: t('settings.langZh'), value: 'zh-CN' },
                    { label: t('settings.langEn'), value: 'en-US' }
                  ]"
                  class="setting-item-control select-control"
                  @update:value="onLanguageChange"
                />
              </div>

              <div class="setting-item-row setting-item-row--stack">
                <div class="setting-item-label">
                  <span>{{ t('settings.newlineKey') }}</span>
                  <n-tooltip trigger="hover" placement="top-start">
                    <template #trigger>
                      <span style="cursor: help; color: var(--text-color-muted); display: inline-flex; align-items: center;">
                        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                          <circle cx="12" cy="12" r="10"></circle>
                          <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"></path>
                          <line x1="12" y1="17" x2="12.01" y2="17"></line>
                        </svg>
                      </span>
                    </template>
                    <div style="max-width: 280px; font-size: 0.8rem; line-height: 1.6;">
                      {{ t('settings.newlineKeyTooltip') }}
                    </div>
                  </n-tooltip>
                </div>
                <n-select
                  v-model:value="appStore.newlineKey"
                  :options="[
                    { label: t('settings.enterKey'), value: 'enter' },
                    { label: t('settings.altEnterKey'), value: 'alt+enter' }
                  ]"
                  class="setting-item-control select-control"
                  @update:value="appStore.saveBasicConfig"
                />
              </div>

              <div class="setting-item-row">
                <div class="setting-item-label">
                  <span>{{ t('settings.messageAggregation') }}</span>
                  <n-tooltip trigger="hover" placement="top-start">
                    <template #trigger>
                      <span style="cursor: help; color: var(--text-color-muted); display: inline-flex; align-items: center;">
                        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                          <circle cx="12" cy="12" r="10"></circle>
                          <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"></path>
                          <line x1="12" y1="17" x2="12.01" y2="17"></line>
                        </svg>
                      </span>
                    </template>
                    <div style="max-width: 280px; font-size: 0.8rem; line-height: 1.6;">
                      {{ t('settings.messageAggregationTooltip') }}
                    </div>
                  </n-tooltip>
                </div>
                <n-switch
                  v-model:value="appStore.messageAggregation"
                  @update:value="appStore.saveBasicConfig"
                />
              </div>

              <div class="setting-item-row">
                <div class="setting-item-label">
                  <span>{{ t('settings.pathSandbox') }}</span>
                  <n-tooltip trigger="hover" placement="top-start">
                    <template #trigger>
                      <span style="cursor: help; color: var(--text-color-muted); display: inline-flex; align-items: center;">
                        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                          <circle cx="12" cy="12" r="10"></circle>
                          <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"></path>
                          <line x1="12" y1="17" x2="12.01" y2="17"></line>
                        </svg>
                      </span>
                    </template>
                    <div style="max-width: 280px; font-size: 0.8rem; line-height: 1.6;">
                      {{ t('settings.pathSandboxTooltip') }}
                    </div>
                  </n-tooltip>
                </div>
                <n-switch
                  v-model:value="appStore.pathSandboxEnabled"
                  @update:value="appStore.saveBasicConfig"
                />
              </div>

              <div class="setting-item-row">
                <div class="setting-item-label">
                  <span>{{ t('settings.httpLog') }}</span>
                  <n-tooltip trigger="hover" placement="top-start">
                    <template #trigger>
                      <span style="cursor: help; color: var(--text-color-muted); display: inline-flex; align-items: center;">
                        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                          <circle cx="12" cy="12" r="10"></circle>
                          <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"></path>
                          <line x1="12" y1="17" x2="12.01" y2="17"></line>
                        </svg>
                      </span>
                    </template>
                    <div style="max-width: 280px; font-size: 0.8rem; line-height: 1.6;">
                      {{ t('settings.httpLogTooltip') }}
                    </div>
                  </n-tooltip>
                </div>
                <n-switch
                  v-model:value="appStore.httpLog"
                  @update:value="appStore.saveBasicConfig"
                />
              </div>

              <div v-if="appStore.httpLog" class="setting-item-row">
                <div class="setting-item-label-simple">{{ t('settings.httpLogDays') }}</div>
                <n-input-number
                  v-model:value="appStore.httpLogDays"
                  :min="1"
                  :max="999"
                  size="small"
                  class="setting-item-control input-number-control"
                  @update:value="appStore.saveBasicConfig"
                />
              </div>

              <div class="setting-item-col">
                <div class="setting-item-label">
                  <span>{{ t('settings.password') }}</span>
                  <n-tooltip trigger="hover" placement="top-start">
                    <template #trigger>
                      <span style="cursor: help; color: var(--text-color-muted); display: inline-flex; align-items: center;">
                        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                          <circle cx="12" cy="12" r="10"></circle>
                          <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"></path>
                          <line x1="12" y1="17" x2="12.01" y2="17"></line>
                        </svg>
                      </span>
                    </template>
                    <div style="max-width: 280px; font-size: 0.8rem; line-height: 1.6;">
                      {{ t('settings.passwordTooltip') }}
                    </div>
                  </n-tooltip>
                </div>
                <div class="setting-password-wrapper">
                  <n-input
                    v-model:value="appStore.password"
                    type="password"
                    show-password-on="click"
                    placeholder="不启用安全密码"
                    size="small"
                    class="setting-item-control password-input"
                    maxlength="64"
                  />
                  <n-button type="primary" size="small" @click="appStore.saveBasicConfig">
                    保存
                  </n-button>
                </div>
              </div>
            </div>
          </n-tab-pane>

          <n-tab-pane name="providers" :tab="t('sider.providerSettings')">
            <div class="providers-pane-content">
              <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
                <span style="font-size: 0.9rem; color: #a0a0a5;">{{ t('sider.providerListTitle') }}</span>
                <n-button type="primary" size="small" @click="openAddProvider">
                  {{ t('sider.addProvider') }}
                </n-button>
              </div>

              <div class="provider-settings-list" style="display: flex; flex-direction: column; gap: 12px; max-height: 350px; overflow-y: auto; padding-right: 4px;">
                <div v-if="providerStore.providerList.length === 0" style="text-align: center; padding: 20px; color: #555568;">
                  {{ t('sider.noProviders') }}
                </div>
                <div
                  v-else
                  v-for="prov in providerStore.providerList"
                  :key="prov.group + ':' + prov.modelName"
                  class="provider-setting-item"
                  style="background-color: rgba(255, 255, 255, 0.015); border: 1px solid rgba(255, 255, 255, 0.05); border-radius: 8px; padding: 12px; display: flex; justify-content: space-between; align-items: center;"
                >
                  <div>
                    <div style="display: flex; align-items: center; gap: 8px;">
                      <strong style="color: #e3e3e7;">{{ prov.group }}</strong>
                      <span style="font-size: 0.75rem; background-color: rgba(129, 182, 229, 0.08); color: var(--primary-color); padding: 2px 6px; border-radius: 4px; border: 1px solid rgba(129, 182, 229, 0.25);">
                        {{ prov.protocol }}
                      </span>
                    </div>
                    <div style="font-size: 0.8rem; color: #a0a0a5; margin-top: 4px; display: flex; gap: 12px;">
                      <span>{{ t('sider.model') }}: {{ prov.modelName }}</span>
                      <span v-if="prov.contextSize">{{ t('sider.window') }}: {{ prov.contextSize.toLocaleString() }} tokens</span>
                    </div>
                  </div>
                  <div style="display: flex; gap: 8px;">
                    <n-button size="small" quaternary @click="openEditProvider(prov)">
                      {{ t('common.edit') }}
                    </n-button>
                    <n-popconfirm
                      @positive-click="providerStore.handleDeleteProvider(prov.group, prov.modelName)"
                      :positive-text="t('common.confirm')"
                      :negative-text="t('common.cancel')"
                      placement="top-end"
                    >
                      <template #trigger>
                        <n-button size="small" quaternary type="error">
                          {{ t('common.delete') }}
                        </n-button>
                      </template>
                      {{ t('sider.deleteProviderConfirm') }}
                    </n-popconfirm>
                  </div>
                </div>
              </div>
            </div>
          </n-tab-pane>
        </n-tabs>
      </div>
    </n-modal>

    <!-- 自定义拖拽边框条 -->
    <div 
      v-if="!isMobile"
      ref="resizeHandleRef" 
      class="custom-resize-handle left-handle"
    >
      <div class="resize-line"></div>
    </div>
  </n-layout-sider>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useResponsive } from '@/utils/useResponsive'

const { isMobile } = useResponsive()

const collapsed = computed(() => {
  return isMobile.value ? false : appStore.leftSiderCollapsed
})

const width = computed(() => {
  return isMobile.value ? '100%' : appStore.leftSiderWidth
})
import { useMessage } from 'naive-ui'
import { useConversationStore } from '@/stores/conversation'
import { useProjectStore } from '@/stores/project'
import { useAppStore } from '@/stores/app'
import { useProviderStore } from '@/stores/provider'
import { t, setLanguage } from '@/i18n'

const message = useMessage()
const conversationStore = useConversationStore()
const projectStore = useProjectStore()
const appStore = useAppStore()
const providerStore = useProviderStore()

const onLanguageChange = (val: 'zh-CN' | 'en-US') => {
  setLanguage(val)
  appStore.saveBasicConfig()
}

const showAddProjectModal = ref(false)
const newProjectForm = ref({ name: '', path: '' })
const lastExtractedName = ref('')

// 会话重命名相关状态与逻辑
const editingCid = ref<number | null>(null)
const editingTitle = ref('')
const editInputRef = ref<any>(null)

const startEdit = (sess: any) => {
  editingCid.value = sess.id
  editingTitle.value = sess.title
  nextTick(() => {
    if (editInputRef.value) {
      if (Array.isArray(editInputRef.value)) {
        editInputRef.value[0]?.focus()
      } else {
        editInputRef.value?.focus()
      }
    }
  })
}

const saveTitle = async (sess: any) => {
  if (editingCid.value === null) return
  const title = editingTitle.value.trim()
  if (!title) {
    editingCid.value = null
    return
  }
  if (title === sess.title) {
    editingCid.value = null
    return
  }
  try {
    await conversationStore.renameConversation(sess.id, title)
    sess.title = title
  } catch (e) {
    console.error('重命名会话失败:', e)
  } finally {
    editingCid.value = null
  }
}

const cancelEdit = () => {
  editingCid.value = null
}

const expandedNames = ref<number[]>([])

// 系统设置相关状态
const showSettingsModal = ref(false)
const activeTab = ref('basic')
const isAddingOrEditingProvider = ref(false)

const openSettingsModal = () => {
  showSettingsModal.value = true
  isAddingOrEditingProvider.value = false
  activeTab.value = 'basic'
}

const openAddProvider = () => {
  providerStore.resetForm()
  isAddingOrEditingProvider.value = true
}

const openEditProvider = (prov: any) => {
  providerStore.handleEditProvider(prov)
  isAddingOrEditingProvider.value = true
}

const cancelProviderEdit = () => {
  providerStore.resetForm()
  isAddingOrEditingProvider.value = false
}

const saveProviderAndReturn = async () => {
  const group = providerStore.form.group ? providerStore.form.group.trim() : ''
  const baseUrl = providerStore.form.baseUrl ? providerStore.form.baseUrl.trim() : ''
  const apiKey = providerStore.form.apiKey ? providerStore.form.apiKey.trim() : ''
  const modelName = providerStore.form.modelName ? providerStore.form.modelName.trim() : ''

  const protocol = providerStore.form.protocol
  if (!protocol) {
    message.warning('请选择协议')
    return
  }
  if (!group) {
    message.warning('请输入分组名称')
    return
  }
  if (!baseUrl) {
    message.warning('请输入端点 (Base URL)')
    return
  }
  if (!apiKey) {
    message.warning('请输入密钥 (API Key)')
    return
  }
  if (!modelName) {
    message.warning('请输入模型名称')
    return
  }
  if (!providerStore.form.contextSize) {
    message.warning('请输入上下文窗口大小')
    return
  }

  // 校验分组名称：不能出现空格和特殊字符 (只允许字母、数字、下划线、连字符)
  const groupRegex = /^[a-zA-Z0-9_-]+$/
  if (!groupRegex.test(group)) {
    message.warning('分组名称不能包含空格或特殊字符（仅允许字母、数字、下划线和连字符）')
    return
  }

  // 校验模型名称：不能出现空格和大部分特殊字符，但允许 @, /, -, _, ., :, 数字, 字母
  const modelRegex = /^[a-zA-Z0-9_./@:-]+$/
  if (!modelRegex.test(modelName)) {
    message.warning('模型名称不能包含空格或特殊字符（仅允许字母、数字及 _ . / @ : - 等常见符号）')
    return
  }

  providerStore.form.group = group
  providerStore.form.baseUrl = baseUrl
  providerStore.form.apiKey = apiKey
  providerStore.form.modelName = modelName

  await providerStore.handleSaveProvider()
  isAddingOrEditingProvider.value = false
}

// 监听当前活跃项目变化，自动展开该项目
watch(
  () => projectStore.activeProjectId,
  (newId) => {
    if (newId && !expandedNames.value.includes(newId)) {
      expandedNames.value = [newId]
    }
  },
  { immediate: true }
)

const handleExpandedChange = (names: any[]) => {
  if (names.length > 0) {
    const lastExpanded = names[names.length - 1]
    projectStore.activeProjectId = Number(lastExpanded)
  }
}

const openAddProject = () => {
  newProjectForm.value = { name: '', path: '' }
  lastExtractedName.value = ''
  showAddProjectModal.value = true
}

const onPathInput = (val: string) => {
  if (!val) return
  const cleaned = val.replace(/[\\/]+$/, '')
  const lastSlash = Math.max(cleaned.lastIndexOf('/'), cleaned.lastIndexOf('\\'))
  const dirName = lastSlash !== -1 ? cleaned.substring(lastSlash + 1) : cleaned
  
  if (!newProjectForm.value.name || newProjectForm.value.name === lastExtractedName.value) {
    newProjectForm.value.name = dirName
    lastExtractedName.value = dirName
  }
}

const submitAddProject = async () => {
  if (!newProjectForm.value.path.trim()) {
    message.warning('请输入项目路径')
    return false
  }
  try {
    const saved = await projectStore.handleSaveProject({
      name: newProjectForm.value.name.trim() || '未命名项目',
      path: newProjectForm.value.path.trim()
    })
    showAddProjectModal.value = false
    // 自动为新创建的项目拉起第一个会话，提供极佳的流程体验
    await conversationStore.createConversation(saved.id)
    return true
  } catch (e: any) {
    message.error(e.response?.data?.message || e.message || '保存项目失败')
    return false
  }
}

// 按项目对会话进行分组
const conversationsByProject = computed(() => {
  const groups: Record<string, any[]> = {}
  conversationStore.conversationList.forEach((sess) => {
    if (sess.parentCid) return
    const pId = sess.projectId || ''
    if (!groups[pId]) {
      groups[pId] = []
    }
    groups[pId].push(sess)
  })
  return groups
})

const formatTime = (timeStr: string) => {
  if (!timeStr) return ''
  try {
    const d = new Date(timeStr)
    const now = new Date()
    const diffMs = now.getTime() - d.getTime()
    const diffMins = Math.floor(diffMs / 60000)
    if (diffMins < 1) return '刚刚'
    if (diffMins < 60) return `${diffMins} 分钟前`
    const diffHrs = Math.floor(diffMins / 60)
    if (diffHrs < 24) return `${diffHrs} 小时前`
    return d.toLocaleDateString()
  } catch (e) {
    return timeStr
  }
}

// 侧边栏拖拽调宽
const resizeHandleRef = ref<HTMLElement | null>(null)
let hoverTimer: any = null
let isResizing = false
let canResize = false
let resizeFrameId: number | null = null
let pendingWidth: number | null = null
let liveWidth: number | null = null

const getSiderEl = () => resizeHandleRef.value?.closest<HTMLElement>('.n-layout-sider') || null

const applyLiveWidth = (newWidth: number) => {
  liveWidth = newWidth
  const sider = getSiderEl()
  if (!sider) return
  const width = `${newWidth}px`
  sider.style.width = width
  sider.style.maxWidth = width
}

const flushPendingWidth = () => {
  resizeFrameId = null
  if (pendingWidth === null) return
  applyLiveWidth(pendingWidth)
  pendingWidth = null
}

const scheduleWidthUpdate = (newWidth: number) => {
  pendingWidth = newWidth
  if (resizeFrameId === null) {
    resizeFrameId = window.requestAnimationFrame(flushPendingWidth)
  }
}

const handleMouseUp = () => {
  isResizing = false
  canResize = false
  if (resizeFrameId !== null) {
    window.cancelAnimationFrame(resizeFrameId)
    resizeFrameId = null
  }
  const finalWidth = pendingWidth ?? liveWidth
  if (finalWidth !== null) {
    applyLiveWidth(finalWidth)
    appStore.leftSiderWidth = finalWidth
    pendingWidth = null
    liveWidth = null
  }
  if (resizeHandleRef.value) {
    resizeHandleRef.value.classList.remove('active-resizing')
  }
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  document.body.classList.remove('sider-resizing')
  document.removeEventListener('mousemove', handleMouseMove)
  document.removeEventListener('mouseup', handleMouseUp)
}

const handleMouseMove = (e: MouseEvent) => {
  if (!isResizing) return
  let newWidth = e.clientX
  if (newWidth < 150) newWidth = 150
  if (newWidth > 500) newWidth = 500
  scheduleWidthUpdate(newWidth)
}

const initResizeEvents = () => {
  const handle = resizeHandleRef.value
  if (!handle) return

  handle.addEventListener('mouseenter', () => {
    hoverTimer = setTimeout(() => {
      canResize = true
      handle.classList.add('active-resizing')
    }, 100)
  })

  handle.addEventListener('mouseleave', () => {
    clearTimeout(hoverTimer)
    if (!isResizing) {
      canResize = false
      handle.classList.remove('active-resizing')
    }
  })

  handle.addEventListener('mousedown', (e) => {
    if (!canResize) return
    isResizing = true
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    document.body.classList.add('sider-resizing')
    
    document.addEventListener('mousemove', handleMouseMove)
    document.addEventListener('mouseup', handleMouseUp)
    e.preventDefault()
  })
}

onMounted(() => {
  initResizeEvents()
})

onUnmounted(() => {
  clearTimeout(hoverTimer)
  if (resizeFrameId !== null) {
    window.cancelAnimationFrame(resizeFrameId)
  }
  document.body.classList.remove('sider-resizing')
  document.body.style.userSelect = ''
  document.removeEventListener('mousemove', handleMouseMove)
  document.removeEventListener('mouseup', handleMouseUp)
})
</script>

<style scoped>
.project-collapse-wrapper {
  padding: 8px 12px;
  overflow-y: auto;
  flex: 1;
}

.empty-projects {
  text-align: center;
  color: var(--text-color-secondary);
  margin-top: 20px;
  font-size: 0.9rem;
}

.project-title {
  font-size: 0.95rem;
  font-weight: 600;
  color: #e3e3e7;
  width: 100%;
}

.project-title.active-project {
  color: #81b6e5;
}

.project-actions {
  display: flex;
  gap: 4px;
  opacity: 0.3;
  transition: opacity 0.2s;
}

:deep(.n-collapse-item__header:hover) .project-actions,
.project-actions:hover {
  opacity: 1;
}

.project-actions .delete-proj-btn:hover {
  color: var(--status-error) !important;
}

.empty-conversations {
  padding: 8px 16px;
  color: #555568;
  font-size: 0.8rem;
}

.conversation-list {
  padding-left: 12px;
}

.delete-conversation-btn {
  position: absolute;
  right: 8px;
  top: 10px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--text-color-muted);
  font-size: 0.8rem;
  opacity: 0.6;
  transition: opacity 0.2s, color 0.2s;
}

.delete-conversation-btn:hover {
  opacity: 1;
  color: var(--status-error);
}
.settings-main-view {
  min-height: 360px;
  display: flex;
  flex-direction: column;
}
/* 系统设置弹窗中的顶部 Tab 栏及分割线优化 */
:deep(.settings-main-view .n-tabs.n-tabs--top-placement) {
  height: 100%;
  flex: 1;
  display: flex;
  flex-direction: column;
}
/* Tab 标签下方的水平分割线优化 */
:deep(.settings-main-view .n-tabs.n-tabs--top-placement > .n-tabs-nav) {
  margin-bottom: 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.09);
  padding-bottom: 8px;
}
:deep(.settings-main-view .n-tabs.n-tabs--top-placement .n-tabs-pane-wrapper) {
  flex: 1;
}

/* 各设置面板内容区域容器 */
.settings-pane-content {
  padding: 0 24px;
  display: flex;
  flex-direction: column;
}
.providers-pane-content {
  padding: 16px 24px;
}

/* 设置项常规行式布局 */
.setting-item-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.setting-item-row--stack {
  /* 默认保持与 setting-item-row 一致，移动端再做堆叠覆盖 */
}

/* 设置项标签容器 */
.setting-item-label {
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 6px;
}
.setting-item-label-simple {
  font-weight: 500;
}

/* 控制控件类 */
.setting-item-control.select-control {
  width: 260px;
}
.setting-item-control.input-number-control {
  width: 90px;
}

/* 设置项列式布局（如密码设置） */
.setting-item-col {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.setting-password-wrapper {
  display: flex;
  gap: 8px;
  align-items: center;
}
.setting-item-control.password-input {
  flex: 1;
}

/* 移动端响应式适配 */
@media (max-width: 768px) {
  .settings-pane-content {
    padding: 0 12px;
  }
  .providers-pane-content {
    padding: 12px 16px;
  }
  .setting-item-row {
    padding: 12px 0;
  }
  /* 移动端强制堆叠为垂直布局的行 */
  .setting-item-row--stack {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  .setting-item-row--stack .setting-item-control.select-control {
    width: 100%;
  }
  .setting-item-col {
    padding: 12px 0;
  }
}

/* 超窄屏幕适配（针对供应商项） */
@media (max-width: 480px) {
  .provider-setting-item {
    flex-direction: column !important;
    align-items: flex-start !important;
    gap: 8px !important;
    padding: 10px !important;
  }
  .provider-setting-item > div:last-child {
    align-self: flex-end;
  }
}

.custom-resize-handle {
  position: absolute;
  top: 0;
  height: 100%;
  width: 8px;
  z-index: 100;
  display: flex;
  justify-content: center;
  align-items: center;
}
.left-handle {
  right: -4px;
  cursor: default;
}
.resize-line {
  width: 1px;
  height: 100%;
  background-color: transparent;
  transition: all 0.2s ease;
}
.custom-resize-handle.active-resizing {
  cursor: col-resize !important;
}
.custom-resize-handle.active-resizing .resize-line {
  width: 4px;
  background-color: var(--primary-color) !important;
  box-shadow: 0 0 8px rgba(129, 182, 229, 0.6);
}

.conversation-title {
  padding-right: 48px !important;
}

.edit-conversation-btn {
  position: absolute;
  right: 28px;
  top: 10px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: rgba(255, 255, 255, 0.55);
  font-size: 0.8rem;
  opacity: 0.6;
  transition: opacity 0.2s, color 0.2s;
}

.conversation-item:hover .edit-conversation-btn {
  opacity: 0.7;
}

.edit-conversation-btn:hover {
  opacity: 1 !important;
  color: #ffffff !important;
}

.conversation-edit-wrapper {
  padding: 2px 0;
}
</style>
