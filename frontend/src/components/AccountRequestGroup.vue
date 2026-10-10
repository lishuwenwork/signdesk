<script setup>
import { computed } from 'vue'
import { canExecuteRequest, requestCounts } from '../platformViewState'
import { requestPresentation } from '../requestPresentation'
import { dateTime } from '../api'
import EnabledActionButton from './EnabledActionButton.vue'
import DeskIcon from './DeskIcon.vue'
import StatusChip from './StatusChip.vue'
const props = defineProps({
  account: { type: Object, required: true }, requests: { type: Array, required: true },
  platformEnabled: Boolean, expanded: Boolean, filtering: Boolean,
  pendingRequestIds: { type: Array, default: () => [] }, pendingAccountIds: { type: Array, default: () => [] },
  highlightedRequestId: { type: String, default: '' },
})
const emit = defineEmits(['toggle', 'add-request', 'edit-account', 'delete-account', 'toggle-request', 'toggle-account',
  'open-request', 'delete-request', 'save-template', 'execute-request', 'show-run'])
const counts = computed(() => requestCounts(props.account.requests))
const accountPending = computed(() => props.pendingAccountIds.includes(props.account.id))
const bodyId = computed(() => `account-requests-${props.account.id}`)
const pending = request => props.pendingRequestIds.includes(request.id)
const executable = request => canExecuteRequest(request, props.account.enabled, props.platformEnabled)
const presentation = request => requestPresentation(request, props.account.enabled, props.platformEnabled)
</script>
<template>
  <section class="account-card" :aria-label="`账号 ${account.alias}`" :data-account-id="account.id">
    <header class="account-heading">
      <button class="account-toggle" :aria-expanded="expanded" :aria-controls="bodyId" @click="emit('toggle')">
        <DeskIcon name="chevron" :class="{ collapsed: !expanded }" />
        <span class="account-avatar">{{ account.alias.slice(0, 1) }}</span>
        <strong class="account-name">{{ account.alias }}</strong>
        <span class="status-dot" :class="{ off: !account.enabled }"></span>
        <span class="account-summary">{{ counts.total }} 个请求<span v-if="counts.attention"> · {{ counts.attention }} 个需处理</span><span v-if="filtering"> · 匹配 {{ requests.length }} 个</span></span>
      </button>
      <div class="account-actions">
        <EnabledActionButton class="account-enable-action" :enabled="!!account.enabled" :pending="accountPending" :label="`账号 ${account.alias}`" @toggle="emit('toggle-account', account)" />
        <el-button text @click="emit('add-request')"><DeskIcon name="plus" />添加请求</el-button>
        <el-dropdown trigger="click">
          <button class="icon-button" :aria-label="`${account.alias}的账号设置`"><DeskIcon name="more" /></button>
          <template #dropdown><el-dropdown-menu>
            <el-dropdown-item @click="emit('edit-account')">编辑账号</el-dropdown-item>
            <el-dropdown-item :disabled="accountPending" @click="emit('toggle-account', account)">{{ account.enabled ? '禁用账号' : '启用账号' }}</el-dropdown-item>
            <el-dropdown-item divided @click="emit('delete-account')">删除账号</el-dropdown-item>
          </el-dropdown-menu></template>
        </el-dropdown>
      </div>
    </header>
    <div v-show="expanded" :id="bodyId" class="account-body">
      <div v-for="request in requests" :key="request.id" class="request-row"
        :class="{ 'recent-request': request.id === highlightedRequestId, 'needs-attention': request.authPaused || request.todayState === 'pending', disabled: !request.enabled }"
        :data-request-id="request.id" role="group" :aria-label="`请求 ${request.name}`" tabindex="-1">
        <div class="request-info">
          <button class="request-title" :disabled="!request.safeHost" @click="emit('open-request', 'view', request)">{{ request.name }}</button>
          <div class="request-meta">
            <template v-if="request.safeHost"><span class="method">{{ request.method }}</span><span class="request-host">{{ request.safeHost }}</span><span class="request-revision">版本 {{ request.currentRevision }}</span></template>
            <span v-else>未配置 cURL，需更新</span>
          </div>
        </div>
        <div class="request-state" :class="presentation(request).tone">
          <span class="state-text"><DeskIcon :name="presentation(request).icon" />{{ presentation(request).label }}</span>
          <p class="state-caption">{{ presentation(request).caption }}</p>
        </div>
        <button v-if="request.lastRun" class="last-run" @click="emit('show-run', request.lastRun.id)">
          <span class="last-run-time">{{ dateTime(request.lastRun.finishedAt || request.lastRun.createdAt) }}</span>
          <span class="last-run-detail"><StatusChip :status="request.lastRun.status" /><span>{{ request.lastRun.httpStatus ?? '—' }} · {{ request.lastRun.durationMs == null ? '—' : `${request.lastRun.durationMs} ms` }}</span></span>
        </button>
        <span v-else class="last-run empty-run">暂无执行记录</span>
        <div class="row-actions">
          <el-button text :disabled="pending(request)" :type="request.authPaused ? 'warning' : ''" @click="emit('open-request', 'update', request)">更新 cURL</el-button>
          <el-dropdown trigger="click">
            <button class="icon-button" :aria-label="`${request.name}的更多操作`"><DeskIcon name="more" /></button>
            <template #dropdown><el-dropdown-menu>
              <el-dropdown-item :disabled="!executable(request) || pending(request)" @click="emit('execute-request', request, false)">执行</el-dropdown-item>
              <el-dropdown-item :disabled="pending(request)" @click="emit('open-request', 'rules', request)">编辑名称 / 规则</el-dropdown-item>
              <el-dropdown-item :disabled="!request.safeHost" @click="emit('open-request', 'view', request)">查看完整请求</el-dropdown-item>
              <el-dropdown-item @click="emit('save-template', request)">保存为接口模板</el-dropdown-item>
              <el-dropdown-item :disabled="pending(request)" @click="emit('toggle-request', request)">{{ request.enabled ? '禁用请求' : '启用请求' }}</el-dropdown-item>
              <el-dropdown-item divided :disabled="!executable(request) || pending(request)" @click="emit('execute-request', request, true)">重新执行（忽略当日标记）</el-dropdown-item>
              <el-dropdown-item divided @click="emit('delete-request', request)">删除请求</el-dropdown-item>
            </el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </div>
      <div v-if="!account.requests.length" class="account-empty"><span>还没有请求</span><span class="muted">点击「添加请求」粘贴这个账号的完整 cURL。</span></div>
    </div>
  </section>
</template>
<style scoped>
.account-card + .account-card { border-top: 1px solid var(--line); }
.account-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 11px 17px; min-height: 56px; background: #fafbf8; }
.account-toggle { display: flex; align-items: center; text-align: left; gap: 11px; padding: 0; border: 0; background: transparent; min-width: 0; color: var(--ink); }
.account-toggle > .icon { width: 14px; height: 14px; color: #7c8973; transition: transform .18s; }
.account-toggle > .icon.collapsed { transform: rotate(-90deg); }
.account-avatar { width: 28px; height: 28px; border: 1px solid #e3e8dc; border-radius: 50%; background: #eef1e7; display: grid; place-items: center; flex-shrink: 0; color: #718060; font-size: 11px; }
.account-name { font-size: 13px; font-weight: 600; overflow-wrap: anywhere; }
.account-summary { font-size: 11px; color: #6d7b62; white-space: nowrap; }
.account-actions, .row-actions { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.account-actions :deep(.el-button), .row-actions :deep(.el-button) { min-height: 27px; font-size: 11px; margin: 0; padding: 4px 0; background: transparent; }
.account-actions .icon-button, .row-actions .icon-button { width: 27px; height: 27px; }
.account-actions :deep(.account-enable-action) { color: var(--muted); }
.request-row { display: grid; grid-template-columns: minmax(175px,1fr) 138px 150px 130px; align-items: center; gap: 15px; min-height: 74px; padding: 13px 22px 13px 61px; position: relative; border-top: 1px solid #f0f2ec; }
.request-row:hover { background: #fcfdf9; }
.request-row.needs-attention::before { content: ''; position: absolute; left: 0; top: 16px; bottom: 16px; width: 3px; background: #d8ba82; border-radius: 0 3px 3px 0; }
.request-row.recent-request { background: #f0f6e8; box-shadow: inset 0 0 0 1px #bdd2a7; }
.request-row:focus-visible { outline-offset: -3px; }
.request-info { min-width: 0; }
.request-title { padding: 0; text-align: left; border: 0; background: transparent; font-size: 13px; font-weight: 500; color: var(--ink); overflow-wrap: anywhere; }
.request-title:hover:not(:disabled) { color: var(--forest); text-decoration: underline; text-underline-offset: 4px; }
.request-title:disabled { cursor: default; }
.disabled .request-title { color: #77816f; }
.request-meta { font-size: 11px; color: #6b7963; margin-top: 4px; display: flex; align-items: baseline; flex-wrap: wrap; gap: 7px; }
.request-host { overflow-wrap: anywhere; }
.request-revision { border-left: 1px solid var(--line); padding-left: 7px; white-space: nowrap; }
.state-text { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #516d46; }
.state-text .icon { width: 13px; height: 13px; }
.warning .state-text { color: var(--amber); }
.active .state-text { color: var(--blue); }
.muted .state-text { color: var(--muted); }
.state-caption { margin-top: 4px; color: #6f7d63; font-size: 10px; }
.last-run { border: 0; background: transparent; padding: 0; text-align: left; }
.last-run-time { font-size: 11px; color: #617154; display: block; }
.last-run:hover .last-run-time { color: var(--forest); text-decoration: underline; text-underline-offset: 4px; }
.last-run-detail { margin-top: 4px; font: 11px/1.65 var(--mono); color: #6b7963; display: flex; align-items: center; flex-wrap: wrap; gap: 3px 7px; }
.empty-run { font-size: 11px; color: #6f7b65; }
.row-actions { justify-content: flex-end; gap: 12px; }
.row-actions :deep(.el-button) { font-size: 12px; }
.account-empty { padding: 22px 61px; display: grid; gap: 7px; font-size: 12px; }
@media(max-width:1240px) { .request-row { grid-template-columns: minmax(145px,1fr) 123px 112px 116px; gap: 10px; padding-left: 45px; padding-right: 16px; } .account-summary { display: none; } }
@media(max-width:730px) {
 .account-heading { padding: 12px 11px; gap: 6px; }
 .account-toggle { gap: 7px; }
 .account-avatar { width: 25px; height: 25px; font-size: 10px; }
 .account-name { font-size: 11px; }
 .account-actions { gap: 7px; }
 .account-actions :deep(.account-enable-action) { display: none; }
 .account-actions :deep(.el-button) { font-size: 10px; }
 .request-row { padding: 15px 14px 12px 32px; grid-template-columns: minmax(0,1fr) auto; gap: 10px 12px; min-height: 114px; }
 .request-info { grid-column: 1; grid-row: 1; }
 .request-state { grid-column: 1; grid-row: 2; }
 .last-run { display: none; }
 .row-actions { grid-column: 2; grid-row: 1 / 3; flex-direction: column; gap: 3px; align-items: flex-end; }
 .request-title { font-size: 12px; }
 .request-meta { font-size: 9px; }
 .state-text { font-size: 10px; }
 .state-caption { display: none; }
 .row-actions :deep(.el-button) { min-height: 36px; font-size: 10px; }
 .account-actions .icon-button, .row-actions .icon-button { width: 36px; height: 36px; }
 .account-empty { padding: 18px 32px; }
}
</style>
