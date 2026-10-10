<script setup>
import { computed, inject, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, report, periodic, confirm, execute } from '../api'
import { schedulePresentation } from '../schedulePresentation'
import ScheduleDialog from '../components/ScheduleDialog.vue'
import {
  filterAccounts, isAccountExpanded, reconcileAccountState, setAccountExpansion,
} from '../platformViewState'
import AccountRequestGroup from '../components/AccountRequestGroup.vue'
import RequestDialog from '../components/RequestDialog.vue'
import RequestTemplatesDialog from '../components/RequestTemplatesDialog.vue'

const platforms = ref([]), selectedId = ref(''), error = ref(''), loading = ref(true)
const selected = computed(() => platforms.value.find((p) => p.id === selectedId.value))
const keyword = ref(''), status = ref('all'), feedback = ref('')
const filtering = computed(() => !!keyword.value.trim() || status.value !== 'all')
const groups = computed(() => filterAccounts(selected.value, keyword.value, status.value))
const matchedRequests = computed(() => groups.value.reduce((sum, group) => sum + group.requests.length, 0))
const accountStates = reactive(new Map())
const currentState = computed(() => accountStates.get(selectedId.value) || reconcileAccountState(null, []))
const pendingRequestIds = ref([]), pendingPlatformIds = ref([]), pendingAccountIds = ref([])
const highlightedRequestId = ref(''), workspace = ref(null)
const platformDialog = ref(false), accountDialog = ref(false), saving = ref(false)
const platformForm = reactive({ id: '', name: '', note: '', enabled: true, version: 1 })
const accountForm = reactive({ id: '', alias: '', enabled: true, version: 1 })
const accountPlatformId = ref('')
const requestDialog = reactive({ visible: false, mode: 'new', platformId: '', accountId: '', contextLabel: '', request: null })
const templatesDialog = reactive({ visible: false, platformId: '', seed: null })
const scheduleDialog = reactive({ visible: false, platformId: '', platformName: '' })
const plans = ref([]), plansReady = ref(false), plansLoading = ref(false), planError = ref('')
const systemStatus = inject('systemStatus', { online: ref(false), paused: ref(false) })
const selectedPlan = computed(() => plans.value.find((plan) => plan.platformId === selectedId.value))
const planSummary = computed(() => schedulePresentation(selectedPlan.value, {
  loading: !plansReady.value, error: planError.value, platformEnabled: !!selected.value?.enabled,
  paused: systemStatus.online.value ? systemStatus.paused.value : null,
}))
let loadSequence = 0, latestLoad, disposed = false, highlightTimer, planSequence = 0, latestPlanLoad

watch(selectedId, () => {
  keyword.value = ''
  status.value = 'all'
  feedback.value = ''
  highlightedRequestId.value = ''
  clearTimeout(highlightTimer)
  if (scheduleDialog.platformId !== selectedId.value) scheduleDialog.visible = false
}, { flush: 'sync' })
watch([() => keyword.value.trim().toLowerCase(), status], () => {
  const state = accountStates.get(selectedId.value)
  if (state) accountStates.set(selectedId.value, { ...state, filteredCollapsedIds: [] })
  feedback.value = ''
}, { flush: 'sync' })

function load() {
  const sequence = ++loadSequence
  latestLoad = (async () => {
    try {
      const data = await api('/platforms')
      if (disposed || sequence !== loadSequence) return false
      platforms.value = data
      const platformIds = new Set(data.map((p) => p.id))
      for (const id of accountStates.keys()) if (!platformIds.has(id)) accountStates.delete(id)
      for (const platform of data) {
        accountStates.set(platform.id, reconcileAccountState(accountStates.get(platform.id), platform.accounts))
      }
      if (!platformIds.has(selectedId.value)) selectedId.value = data[0]?.id || ''
      error.value = ''
      return true
    } catch (e) {
      if (!disposed && sequence === loadSequence) error.value = e.message
      return false
    } finally {
      if (!disposed && sequence === loadSequence) loading.value = false
    }
  })()
  return latestLoad
}
// A mutation must wait for the accepted refresh, not an older overlapping poll.
async function refresh() {
  let pending = load(), accepted = await pending
  while (!disposed && pending !== latestLoad) {
    pending = latestLoad
    accepted = await pending
  }
  return !disposed && accepted
}
function loadSchedules() {
  const current = ++planSequence
  plansLoading.value = true
  latestPlanLoad = (async () => {
    try {
      const data = await api('/schedules')
      if (disposed || current !== planSequence) return false
      plans.value = data
      planError.value = ''
      return true
    } catch (e) {
      if (!disposed && current === planSequence) planError.value = e.message
      return false
    } finally {
      if (!disposed && current === planSequence) {
        plansReady.value = true
        plansLoading.value = false
      }
    }
  })()
  return latestPlanLoad
}
async function refreshSchedules() {
  let pending = loadSchedules(), accepted = await pending
  while (!disposed && pending !== latestPlanLoad) {
    pending = latestPlanLoad
    accepted = await pending
  }
  return !disposed && accepted
}
function editSchedule() {
  Object.assign(scheduleDialog, { visible: true, platformId: selectedId.value, platformName: selected.value.name })
}
async function scheduleSaved() {
  if (!(await refreshSchedules()) && !disposed) planError.value = `已保存但计划读取失败：${planError.value}`
}
periodic(load, 4000)
periodic(loadSchedules, 4000)
onUnmounted(() => {
  disposed = true
  loadSequence++
  planSequence++
  clearTimeout(highlightTimer)
})

function setExpanded(accountIds, expanded, inFilter = filtering.value) {
  accountStates.set(selectedId.value, setAccountExpansion(currentState.value, accountIds, expanded, inFilter))
}
function toggleAccount(accountId) {
  setExpanded([accountId], !isAccountExpanded(currentState.value, accountId, filtering.value))
}
function expandVisible(expanded) {
  setExpanded(groups.value.map((group) => group.account.id), expanded)
}
function clearFilters() {
  keyword.value = ''
  status.value = 'all'
}
async function revealCreated(platformId, accountId, requestId = '') {
  const platform = platforms.value.find((p) => p.id === platformId)
  if (!platform?.accounts.some((account) => account.id === accountId)) return
  selectedId.value = platformId
  const visibleGroup = groups.value.find((group) => group.account.id === accountId)
  if (!visibleGroup || (requestId && !visibleGroup.requests.some((request) => request.id === requestId))) {
    clearFilters()
    feedback.value = '已清除筛选，以显示刚刚添加的内容。'
  }
  setExpanded([accountId], true, false)
  if (filtering.value) setExpanded([accountId], true, true)
  highlightedRequestId.value = requestId
  clearTimeout(highlightTimer)
  await nextTick()
  if (disposed) return
  const selector = requestId ? `[data-request-id="${requestId}"]` : `[data-account-id="${accountId}"]`
  const target = workspace.value?.querySelector(selector)
  target?.scrollIntoView({ block: 'nearest' })
  if (requestId) target?.focus({ preventScroll: true })
  highlightTimer = setTimeout(() => { highlightedRequestId.value = '' }, 3000)
}
async function requestSaved({ accountId, requestId, mode }) {
  const platformId = requestDialog.platformId
  if (!(await refresh())) return
  if (mode === 'new') await revealCreated(platformId, accountId, requestId)
  else if (selectedId.value === platformId && filtering.value
    && !groups.value.some((group) => group.requests.some((request) => request.id === requestId))) {
    feedback.value = '已保存，该请求不再符合当前筛选。清除筛选后可以查看。'
  }
}
function platformCounts(platform) {
  return `${platform.accounts.length} 个账号 · ${platform.accounts.reduce((sum, account) => sum + account.requests.length, 0)} 个请求`
}
async function togglePlatform(platform) {
  const { id, name, note, version } = platform, enabled = !platform.enabled
  if (pendingPlatformIds.value.includes(id)) return
  pendingPlatformIds.value.push(id)
  try {
    await api(`/platforms/${id}`, 'PUT', { name, note, enabled, version })
    const [catalogueReady, schedulesReady] = await Promise.all([refresh(), refreshSchedules()])
    if (disposed) return
    if (!catalogueReady || !schedulesReady) ElMessage.warning('平台状态已保存，但刷新失败，请重新读取。')
    else ElMessage.success(`平台已${enabled ? '启用' : '禁用'}`)
  } catch (e) {
    if (!disposed) report(e)
  } finally {
    pendingPlatformIds.value = pendingPlatformIds.value.filter((pendingId) => pendingId !== id)
  }
}
function editPlatform(p) {
  Object.assign(platformForm, p ? { ...p, enabled: !!p.enabled }
    : { id: '', name: '', note: '', enabled: true, version: 1 })
  platformDialog.value = true
}
async function savePlatform() {
  if (!platformForm.name.trim()) return ElMessage.warning('请填写平台名称')
  saving.value = true
  try {
    const result = await api(platformForm.id ? `/platforms/${platformForm.id}` : '/platforms',
      platformForm.id ? 'PUT' : 'POST', platformForm)
    if (result.id) selectedId.value = result.id
    platformDialog.value = false
    await Promise.all([refresh(), refreshSchedules()])
    ElMessage.success('平台已保存')
  } catch (e) {
    report(e)
  } finally {
    saving.value = false
  }
}
async function toggleAccountEnabled(account) {
  const { id, alias, version } = account, enabled = !account.enabled
  if (pendingAccountIds.value.includes(id)) return
  pendingAccountIds.value.push(id)
  try {
    await api(`/accounts/${id}`, 'PUT', { alias, enabled, version })
    const catalogueReady = await refresh()
    if (disposed) return
    if (!catalogueReady) ElMessage.warning('账号状态已保存，但刷新失败，请重新读取。')
    else ElMessage.success(`账号已${enabled ? '启用' : '禁用'}`)
  } catch (e) {
    if (!disposed) report(e)
  } finally {
    pendingAccountIds.value = pendingAccountIds.value.filter((pendingId) => pendingId !== id)
  }
}
function editAccount(a) {
  accountPlatformId.value = selectedId.value
  Object.assign(accountForm, a ? { ...a, enabled: !!a.enabled }
    : { id: '', alias: '', enabled: true, version: 1 })
  accountDialog.value = true
}
async function saveAccount() {
  if (!accountForm.alias.trim()) return ElMessage.warning('请填写账号别名')
  saving.value = true
  const platformId = accountPlatformId.value, creating = !accountForm.id
  try {
    const result = await api(accountForm.id ? `/accounts/${accountForm.id}` : `/platforms/${platformId}/accounts`,
      accountForm.id ? 'PUT' : 'POST', accountForm)
    accountDialog.value = false
    if (await refresh()) {
      if (creating) await revealCreated(platformId, result.id)
    }
    ElMessage.success('账号已保存')
  } catch (e) {
    report(e)
  } finally {
    saving.value = false
  }
}
async function remove(kind, item, account) {
  const label = {
    platforms: '平台和所有账号、请求、接口模板、计划及记录',
    accounts: '账号和所有请求及记录',
    requests: '请求和其历史记录',
  }[kind]
  const name = kind === 'requests' ? `账号「${account.alias}」下的请求「${item.name}」` : `「${item.name || item.alias}」`
  if (!(await confirm(`删除${name}？将同时删除${label}。`))) return
  try {
    await api(`/${kind}/${item.id}`, 'DELETE', {})
    await refresh()
    ElMessage.success('已删除')
  } catch (e) {
    report(e)
  }
}
async function toggleRequest(request) {
  if (pendingRequestIds.value.includes(request.id)) return
  pendingRequestIds.value.push(request.id)
  try {
    await api(`/requests/${request.id}`, 'PUT', { ...request, enabled: !request.enabled })
    await refresh()
  } catch (e) {
    report(e)
  } finally {
    pendingRequestIds.value = pendingRequestIds.value.filter((id) => id !== request.id)
  }
}
function openRequest(accountId, mode = 'new', request = null) {
  const account = selected.value.accounts.find((a) => a.id === accountId)
  Object.assign(requestDialog, {
    platformId: selectedId.value, accountId, mode, request, visible: true,
    contextLabel: `${selected.value.name} / ${account.alias}`,
  })
}
function openTemplates(request = null) {
  Object.assign(templatesDialog, {
    platformId: selectedId.value, seed: request ? { name: request.name, rules: request.rules } : null, visible: true,
  })
}
</script>

<template>
  <div class="page-heading platform-page-heading">
    <h1>平台与账号</h1>
    <el-button @click="editPlatform()">＋ 新增平台</el-button>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="catalog-error" />
  <div v-if="loading" class="muted" role="status">正在加载平台…</div>
  <el-empty v-else-if="!platforms.length && !error" description="还没有平台">
    <el-button type="primary" @click="editPlatform()">新增平台</el-button>
  </el-empty>
  <div v-if="platforms.length" class="platform-layout">
    <aside class="platform-list" aria-label="平台列表">
      <button v-for="p in platforms" :key="p.id" class="platform-choice"
        :class="{ selected: selectedId === p.id }" :aria-pressed="selectedId === p.id" @click="selectedId = p.id">
        <div class="platform-choice-title">
          <strong>{{ p.name }}</strong>
          <el-tag class="platform-state" size="small" :type="p.enabled ? 'success' : 'info'">{{ p.enabled ? '启用' : '禁用' }}</el-tag>
        </div>
        <small class="platform-counts">{{ platformCounts(p) }}</small>
      </button>
    </aside>
    <section v-if="selected" ref="workspace" class="platform-workspace">
      <div class="platform-picker">
        <span class="muted">当前平台</span>
        <el-select v-model="selectedId" aria-label="选择平台">
          <el-option v-for="p in platforms" :key="p.id" :label="p.name" :value="p.id" :aria-label="p.name" class="platform-option">
            <div class="platform-option-title">
              <span>{{ p.name }}</span>
              <el-tag size="small" :type="p.enabled ? 'success' : 'info'">{{ p.enabled ? '启用' : '禁用' }}</el-tag>
            </div>
            <small class="platform-counts">{{ platformCounts(p) }}</small>
          </el-option>
        </el-select>
        <small class="platform-counts">{{ platformCounts(selected) }}</small>
      </div>
      <header class="catalog-overview">
        <div class="catalog-title">
          <h2>{{ selected.name }}</h2>
          <el-switch class="platform-enable" :model-value="!!selected.enabled" inline-prompt active-text="启用" inactive-text="禁用" :width="56"
            :loading="pendingPlatformIds.includes(selected.id)" :disabled="pendingPlatformIds.includes(selected.id)"
            aria-label="平台启用状态" @change="togglePlatform(selected)" />
        </div>
        <p v-if="selected.note" class="catalog-note" aria-label="平台备注">
          <span class="catalog-field-label">备注</span>
          <span class="catalog-note-text muted">{{ selected.note }}</span>
        </p>
        <div class="platform-schedule" aria-label="当前平台定时计划">
          <span class="catalog-field-label">定时计划</span>
          <div class="schedule-content">
            <div class="schedule-summary-line">
              <el-tag size="small" :type="planSummary.tone">{{ planSummary.label }}</el-tag>
              <span v-if="planSummary.frequency">{{ planSummary.frequency }} {{ planSummary.times }} · {{ planSummary.timezone }}</span>
              <el-button v-if="planError" text :loading="plansLoading" @click="loadSchedules">重试读取</el-button>
            </div>
            <template v-if="planSummary.frequency">
              <div class="muted small">执行策略：{{ planSummary.strategy }}</div>
              <div v-for="notice in planSummary.notices" :key="notice" class="schedule-notice small">{{ notice }}</div>
            </template>
            <div v-if="planError" class="muted small">{{ planError }}</div>
          </div>
        </div>
        <div class="catalog-actions actions">
          <el-button @click="editAccount()">＋ 添加账号</el-button>
          <el-button @click="openTemplates()">接口模板</el-button>
          <el-button @click="editSchedule">设置计划</el-button>
          <el-button :disabled="!selected.enabled" @click="execute('platform', selected.id)">执行此平台</el-button>
          <el-dropdown trigger="click">
            <el-button text>平台设置 ▾</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="editPlatform(selected)">编辑平台</el-dropdown-item>
                <el-dropdown-item divided @click="remove('platforms', selected)">删除平台</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>
      <template v-if="selected.accounts.length">
        <div class="catalog-toolbar">
          <el-input v-model="keyword" clearable placeholder="搜索账号 / 请求名称 / 主机" aria-label="搜索当前平台请求" />
          <el-select v-model="status" class="catalog-status-filter" aria-label="请求状态筛选">
            <el-option label="全部状态" value="all" />
            <el-option label="需处理" value="attention" />
            <el-option label="禁用" value="disabled" />
          </el-select>
        </div>
        <div class="catalog-result-bar">
          <div v-if="filtering" class="catalog-matches muted small" role="status">
            {{ groups.length }} 个账号 · {{ matchedRequests }} 个匹配请求
            <el-button text @click="clearFilters">清除筛选</el-button>
          </div>
          <div class="expand-actions">
            <el-button text :disabled="!groups.length" @click="expandVisible(true)">全部展开</el-button>
            <el-button text :disabled="!groups.length" @click="expandVisible(false)">全部收起</el-button>
          </div>
        </div>
      </template>
      <p v-if="feedback" class="catalog-feedback muted" role="status">{{ feedback }}</p>
      <el-empty v-if="!selected.accounts.length" description="这个平台还没有账号">
        <el-button @click="editAccount()">添加账号</el-button>
      </el-empty>
      <el-empty v-else-if="!groups.length" description="没有符合条件的账号或请求">
        <el-button @click="clearFilters">清除筛选</el-button>
      </el-empty>
      <AccountRequestGroup v-for="group in groups" :key="group.account.id"
        :account="group.account" :requests="group.requests" :platform-enabled="!!selected.enabled"
        :expanded="isAccountExpanded(currentState, group.account.id, filtering)" :filtering="filtering"
        :pending-request-ids="pendingRequestIds" :pending-account-ids="pendingAccountIds" :highlighted-request-id="highlightedRequestId"
        @toggle="toggleAccount(group.account.id)" @toggle-account="toggleAccountEnabled(group.account)"
        @add-request="openRequest(group.account.id)" @edit-account="editAccount(group.account)" @delete-account="remove('accounts', group.account)"
        @toggle-request="toggleRequest" @open-request="(mode, request) => openRequest(group.account.id, mode, request)"
        @delete-request="(request) => remove('requests', request, group.account)" @save-template="openTemplates"
        @execute-request="(request, force) => execute('request', request.id, force)" />
      <div class="inline-info">
        列表仅显示主机和方法，不显示凭证。更新 cURL 会创建新版本；已经排队的任务仍使用其冻结版本。
      </div>
    </section>
  </div>
  <el-dialog v-model="platformDialog" :title="platformForm.id ? '编辑平台' : '新增平台'" width="470px">
    <el-form label-position="top">
      <el-form-item label="平台名称"><el-input v-model="platformForm.name" maxlength="40" placeholder="例如：平台 A" /></el-form-item>
      <el-form-item label="备注"><el-input v-model="platformForm.note" type="textarea" :rows="3" maxlength="500" /></el-form-item>
      <el-form-item label="启用平台"><el-switch v-model="platformForm.enabled" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="platformDialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="savePlatform">保存平台</el-button>
    </template>
  </el-dialog>
  <el-dialog v-model="accountDialog" :title="accountForm.id ? '编辑账号' : '添加账号'" width="450px">
    <el-form label-position="top">
      <el-form-item label="账号别名"><el-input v-model="accountForm.alias" maxlength="40" placeholder="例如：主账号" /></el-form-item>
      <el-form-item label="启用账号"><el-switch v-model="accountForm.enabled" /></el-form-item>
    </el-form>
    <div class="muted">别名用于区分账号，鉴权信息直接从完整 cURL 保存。</div>
    <template #footer>
      <el-button @click="accountDialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="saveAccount">保存账号</el-button>
    </template>
  </el-dialog>
  <RequestDialog v-model="requestDialog.visible" :mode="requestDialog.mode" :platform-id="requestDialog.platformId"
    :account-id="requestDialog.accountId" :request="requestDialog.request" :context-label="requestDialog.contextLabel"
    @saved="requestSaved" />
  <ScheduleDialog v-model="scheduleDialog.visible" :platform-id="scheduleDialog.platformId"
    :platform-name="scheduleDialog.platformName" @saved="scheduleSaved" />
  <RequestTemplatesDialog v-model="templatesDialog.visible" :platform-id="templatesDialog.platformId" :seed="templatesDialog.seed" />
</template>

<style scoped>
.platform-page-heading :deep(.el-button), .platform-workspace > * :deep(.el-button) { font-size: 12px; }
.platform-page-heading :deep(.el-button), .catalog-actions :deep(.el-button),
.catalog-result-bar :deep(.el-button) { min-height: 32px; height: auto; margin-left: 0; }
.catalog-error { margin-bottom: 16px; }
.platform-layout { display: grid; grid-template-columns: 200px minmax(0, 1fr); gap: 20px; align-items: start; }
.platform-list { padding: 6px; border: 1px solid #e2e8df; border-radius: 10px; background: #fff; }
.platform-choice {
  display: block; width: 100%; border: 0; border-radius: 7px; padding: 14px 12px;
  text-align: left; color: #476050; background: transparent; cursor: pointer;
}
.platform-choice + .platform-choice { margin-top: 3px; }
.platform-choice:hover { background: #f4f7f2; }
.platform-choice.selected { background: #eaf1e6; }
.platform-choice:focus-visible { outline: 2px solid #438468; outline-offset: -2px; }
.platform-choice-title, .platform-option-title { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.platform-choice-title strong, .platform-option-title > span:first-child { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.platform-choice strong { font-size: 13px; font-weight: 600; }
.platform-choice-title .el-tag, .platform-option-title .el-tag { flex-shrink: 0; }
.platform-counts { display: block; color: #849082; font-size: 11px; line-height: 1.6; }
.platform-option { height: auto; min-height: 60px; padding-top: 8px; padding-bottom: 8px; line-height: 1.5; white-space: normal; }
.platform-schedule, .catalog-note { margin: 12px 0 0; display: grid; grid-template-columns: 4em minmax(0, 1fr); gap: 10px; font-size: 12px; line-height: 1.7; }
.catalog-field-label { color: #617567; font-weight: 600; white-space: nowrap; }
.catalog-note-text { white-space: pre-wrap; overflow-wrap: anywhere; }
.schedule-content { display: grid; gap: 4px; min-width: 0; }
.schedule-summary-line { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 10px; font-size: 12px; line-height: 1.7; }
.schedule-summary-line > span { overflow-wrap: anywhere; }
.schedule-notice { color: #a46b38; line-height: 1.6; }
.platform-workspace { min-width: 0; }
.platform-picker { display: none; }
.catalog-overview { padding: 18px; border: 1px solid #e2e8df; border-radius: 10px; background: #fff; margin-bottom: 18px; }
.catalog-title { display: flex; align-items: center; gap: 8px 12px; flex-wrap: wrap; }
.catalog-title h2 { margin: 0; overflow-wrap: anywhere; min-width: 0; }
.platform-enable { height: 32px; }
.catalog-actions { margin-top: 14px; gap: 8px; }
.catalog-toolbar { display: flex; gap: 10px; }
.catalog-toolbar > .el-input { flex: 1; min-width: 0; }
.catalog-toolbar .catalog-status-filter { width: 132px; flex: 0 0 132px; }
.catalog-result-bar { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 0 12px; padding: 6px 0 10px; }
.catalog-matches { display: flex; align-items: center; flex-wrap: wrap; gap: 5px; margin-right: auto; }
.expand-actions { display: flex; gap: 4px; }
.catalog-feedback { margin: 0 0 12px; line-height: 1.7; }
@media (max-width: 1100px) {
  .platform-layout { grid-template-columns: minmax(0, 1fr); }
  .platform-list { display: none; }
  .platform-picker { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 4px 12px; align-items: center; margin-bottom: 16px; }
  .platform-picker > span { white-space: nowrap; }
  .platform-picker .el-select { min-width: 0; }
  .platform-picker > .platform-counts { grid-column: 2; }
}
@media (max-width: 760px) {
  .platform-page-heading :deep(.el-button), .catalog-actions :deep(.el-button),
  .catalog-result-bar :deep(.el-button) { min-height: 44px; }
  .platform-picker :deep(.el-select__wrapper), .catalog-toolbar :deep(.el-input__wrapper),
  .catalog-toolbar :deep(.el-select__wrapper) { min-height: 44px; }
  .platform-enable { height: 44px; }
  .catalog-overview { padding: 15px; }
  .catalog-actions { gap: 6px; }
  .catalog-toolbar { gap: 8px; }
  .catalog-toolbar .catalog-status-filter { width: 110px; flex-basis: 110px; }
}
</style>
