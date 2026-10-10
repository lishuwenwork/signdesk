<script setup>
import { computed } from 'vue'
import { canExecuteRequest, requestCounts } from '../platformViewState'
import EnabledActionButton from './EnabledActionButton.vue'

const props = defineProps({
  account: { type: Object, required: true },
  requests: { type: Array, required: true },
  platformEnabled: Boolean,
  expanded: Boolean,
  filtering: Boolean,
  pendingRequestIds: { type: Array, default: () => [] },
  pendingAccountIds: { type: Array, default: () => [] },
  highlightedRequestId: { type: String, default: '' },
})
const emit = defineEmits([
  'toggle', 'add-request', 'edit-account', 'delete-account', 'toggle-request', 'toggle-account',
  'open-request', 'delete-request', 'save-template', 'execute-request',
])
const counts = computed(() => requestCounts(props.account.requests))
const accountPending = computed(() => props.pendingAccountIds.includes(props.account.id))
const bodyId = computed(() => `account-requests-${props.account.id}`)
const pending = (request) => props.pendingRequestIds.includes(request.id)
const executable = (request) => canExecuteRequest(request, props.account.enabled, props.platformEnabled)
</script>

<template>
  <section class="account-card" :aria-label="`账号 ${account.alias}`" :data-account-id="account.id">
    <header class="account-heading">
      <div class="account-info">
        <div class="account-title">
          <button class="account-toggle" :aria-expanded="expanded" :aria-controls="bodyId" @click="emit('toggle')">
            <span class="collapse-chevron" :class="{ expanded }" aria-hidden="true"></span>
            <strong class="account-name">{{ account.alias }}</strong>
          </button>
          <el-tag class="account-state" size="small" :type="account.enabled ? 'success' : 'info'">{{ account.enabled ? '启用' : '禁用' }}</el-tag>
        </div>
        <div class="account-summary">
          <span>{{ counts.total }} 个请求</span>
          <span v-if="counts.attention" class="attention-count">{{ counts.attention }} 个需处理</span>
          <span v-if="filtering">匹配 {{ requests.length }} 个</span>
        </div>
      </div>
      <div class="account-actions">
        <EnabledActionButton class="account-enable-action" :enabled="!!account.enabled" :pending="accountPending"
          :label="`账号 ${account.alias}`" @toggle="emit('toggle-account', account)" />
        <el-button type="primary" @click="emit('add-request')">＋ 添加请求</el-button>
        <el-dropdown trigger="click">
          <el-button class="more-button" text :aria-label="`${account.alias}的账号设置`">⋯</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="emit('edit-account')">编辑账号</el-dropdown-item>
              <el-dropdown-item divided @click="emit('delete-account')">删除账号</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div v-show="expanded" :id="bodyId" class="account-body">
      <div v-for="request in requests" :key="request.id" class="request-row"
        :class="{ 'recent-request': request.id === highlightedRequestId }"
        :data-request-id="request.id" role="group" :aria-label="`请求 ${request.name}`" tabindex="-1">
        <div class="request-info">
          <div class="request-title">
            <strong>{{ request.name }}</strong>
            <el-tag class="request-state" size="small" :type="request.enabled ? 'success' : 'info'">{{ request.enabled ? '启用' : '禁用' }}</el-tag>
            <el-tag v-if="!request.safeHost" type="warning" size="small">未配置 cURL</el-tag>
            <el-tag v-else-if="request.authPaused" type="danger" size="small">需更新 cURL</el-tag>
          </div>
          <div class="request-meta">
            <template v-if="request.safeHost">
              <span class="method mono">{{ request.method }}</span>
              <span class="request-host">{{ request.safeHost }}</span>
              <span class="request-revision">版本 {{ request.currentRevision }}</span>
            </template>
            <span v-else>未配置 cURL，需更新</span>
          </div>
        </div>
        <div class="request-controls">
          <div class="request-actions">
            <EnabledActionButton class="request-enable-action" :enabled="!!request.enabled" :pending="pending(request)"
              :label="`请求 ${request.name}`" @toggle="emit('toggle-request', request)" />
            <el-button text type="primary" :disabled="pending(request)" @click="emit('open-request', 'update', request)">更新 cURL</el-button>
            <el-button text :disabled="pending(request)" title="编辑名称与结果规则" @click="emit('open-request', 'rules', request)">编辑</el-button>
            <el-button text type="danger" :disabled="pending(request)" class="delete-request"
              @click="emit('delete-request', request)">删除</el-button>
            <el-dropdown trigger="click">
              <el-button text class="more-button" :aria-label="`${request.name}的更多操作`">⋯</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item :disabled="!executable(request) || pending(request)"
                    @click="emit('execute-request', request, false)">执行</el-dropdown-item>
                  <el-dropdown-item :disabled="!request.safeHost" @click="emit('open-request', 'view', request)">查看完整请求</el-dropdown-item>
                  <el-dropdown-item @click="emit('save-template', request)">保存为接口模板</el-dropdown-item>
                  <el-dropdown-item divided :disabled="!executable(request) || pending(request)"
                    @click="emit('execute-request', request, true)">重新执行（忽略当日标记）</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </div>
      </div>
      <div v-if="!account.requests.length" class="account-empty">
        <span>还没有请求</span>
        <span class="muted">点击「添加请求」粘贴这个账号的完整 cURL。</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.account-card {
  background: #fff;
  border: 1px solid #e2e8df;
  border-radius: 10px;
  overflow: hidden;
}
.account-card + .account-card { margin-top: 12px; }
.account-heading { display: flex; align-items: center; gap: 12px; padding: 14px 16px; }
.account-info { flex: 1; min-width: 0; }
.account-title { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 8px; margin-bottom: 4px; }
.account-toggle {
  display: flex;
  align-items: center;
  gap: 13px;
  min-width: 0;
  max-width: 100%;
  padding: 2px 0;
  border: 0;
  background: transparent;
  text-align: left;
  color: inherit;
  cursor: pointer;
  border-radius: 4px;
}
.account-toggle:focus-visible { outline: 2px solid #438468; outline-offset: 4px; }
.collapse-chevron {
  width: 7px;
  height: 7px;
  margin: 0 3px;
  border-right: 1.5px solid #819489;
  border-bottom: 1.5px solid #819489;
  transform: rotate(-45deg);
  flex-shrink: 0;
}
.collapse-chevron.expanded { transform: rotate(45deg); }
.account-name { font-size: 14px; font-weight: 600; color: #284637; overflow-wrap: anywhere; min-width: 0; }
.account-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 4px 12px; margin-left: 26px; font-size: 11px; color: #849082; line-height: 1.5; }
.attention-count { color: #a46b38; }
.account-actions, .request-actions { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
.account-actions :deep(.el-button), .request-actions :deep(.el-button) {
  min-height: 32px;
  height: auto;
  margin: 0;
  font-size: 12px;
  padding: 7px 10px;
}
.more-button { min-width: 32px; }
.account-body { border-top: 1px solid #e9eee6; }
.request-row { padding: 14px 18px 14px 36px; display: flex; flex-wrap: wrap; gap: 10px 20px; align-items: center; }
.request-row + .request-row { border-top: 1px solid #f0f2ee; }
.request-row:focus-visible { outline: 2px solid #438468; outline-offset: -2px; }
.request-row.recent-request { background: #edf6ec; }
.request-info { flex: 1 1 230px; min-width: 0; }
.request-title { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 9px; margin-bottom: 6px; }
.request-title strong { font-size: 13px; font-weight: 550; overflow-wrap: anywhere; min-width: 0; }
.request-meta { display: flex; align-items: baseline; flex-wrap: wrap; gap: 3px 10px; color: #849082; font-size: 11px; line-height: 1.6; }
.request-host { overflow-wrap: anywhere; min-width: 0; }
.request-revision { white-space: nowrap; }
.request-controls { display: flex; align-items: center; justify-content: flex-end; flex-shrink: 0; }
.request-actions .delete-request { margin-left: 6px; }
.account-empty { padding: 22px 36px; display: grid; gap: 7px; font-size: 13px; }
@media (max-width: 760px) {
  .account-heading { gap: 6px; padding: 12px; align-items: flex-start; }
  .account-toggle { gap: 9px; min-height: 44px; }
  .account-summary { margin-left: 22px; }
  .account-actions { gap: 2px; }
  .account-actions :deep(.el-button), .request-actions :deep(.el-button) { min-height: 44px; padding: 9px 8px; }
  .more-button { min-width: 44px; }
  .request-row { padding: 14px; gap: 9px; }
  .request-info { flex-basis: 100%; }
  .request-controls { justify-content: flex-end; flex-basis: 100%; min-width: 0; }
  .request-actions { gap: 0; }
  .request-actions .delete-request { margin-left: 0; }
  .account-empty { padding: 18px; }
}
</style>
