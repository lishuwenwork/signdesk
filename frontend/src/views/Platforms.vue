<script setup>
import { computed, inject, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import DeskIcon from '../components/DeskIcon.vue'
import RunDetailDrawer from '../components/RunDetailDrawer.vue'
import RequestDetailDrawer from '../components/RequestDetailDrawer.vue'
import { api, report, periodic, confirm, execute } from '../api'
import { schedulePresentation } from '../schedulePresentation'
import ScheduleDialog from '../components/ScheduleDialog.vue'
import {
  filterAccounts, isAccountExpanded, reconcileAccountState, setAccountExpansion,
} from '../platformViewState'
import AccountRequestGroup from '../components/AccountRequestGroup.vue'
import RequestDialog from '../components/RequestDialog.vue'
import RequestTemplatesDialog from '../components/RequestTemplatesDialog.vue'

const route = useRoute(), router = useRouter()
const platforms = ref([]), selectedId = ref(''), error = ref(''), loading = ref(true)
const runDrawer = reactive({ visible: false, id: '' })
const requestDetail = reactive({ visible: false, request: null, account: null, platform: null })
const requestTotal = computed(() => selected.value?.accounts.reduce((sum, a) => sum + a.requests.length, 0) || 0)
const statusCounts = computed(() => {
  const requests = selected.value?.accounts.flatMap(a => a.requests) || []
  return { all: requests.length, attention: requests.filter(r => !r.safeHost || r.authPaused || r.todayState === 'pending').length,
    completed: requests.filter(r => r.todayState === 'completed').length }
})
function showRun(id) { requestDetail.visible = false; Object.assign(runDrawer, { visible: true, id }) }
function selectPlatform(id) { selectedId.value = id; router.replace({ path: '/platforms', query: { platformId: id } }) }
let locatedKey = ''
watch([() => route.query, platforms], async () => {
  const platformId = String(route.query.platformId || ''), requestId = String(route.query.requestId || ''), action = String(route.query.action || '')
  const key = `${platformId}:${requestId}:${action}`
  if (!platformId || key === locatedKey) return
  const platform = platforms.value.find(p => p.id === platformId)
  if (!platform) return
  requestDetail.visible = false
  runDrawer.visible = false
  selectedId.value = platformId
  const account = platform.accounts.find(a => a.requests.some(r => r.id === requestId))
  if (!requestId) { locatedKey = key; return }
  if (!account) return
  locatedKey = key
  await revealCreated(platformId, account.id, requestId)
  if (disposed || route.path !== '/platforms' || selectedId.value !== platformId
    || `${route.query.platformId || ''}:${route.query.requestId || ''}:${route.query.action || ''}` !== key) return
  const request = account.requests.find(r => r.id === requestId)
  if (action === 'update') openRequest(account.id, 'update', request)
  if (action === 'detail' && request.safeHost) openRequest(account.id, 'view', request)
}, { deep: true })
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
let platformSession = 0, accountSession = 0, requestEditorGeneration = 0, navigationGeneration = 0
watch(() => [requestDialog.visible, requestDialog.platformId, requestDialog.accountId, requestDialog.mode, requestDialog.request?.id], ([open]) => { if (open) requestEditorGeneration++ }, { flush: 'sync' })
watch(platformDialog, () => { platformSession++ }, { flush: 'sync' })
watch(accountDialog, () => { accountSession++ }, { flush: 'sync' })
watch(() => `${route.query.platformId || ''}:${route.query.requestId || ''}:${route.query.action || ''}`, () => { navigationGeneration++ }, { flush: 'sync' })

watch(selectedId, () => {
  navigationGeneration++
  keyword.value = ''
  status.value = 'all'
  feedback.value = ''
  highlightedRequestId.value = ''
  clearTimeout(highlightTimer)
  if (scheduleDialog.platformId !== selectedId.value) scheduleDialog.visible = false
  requestDetail.visible = false
  requestDialog.visible = false
  templatesDialog.visible = false
  runDrawer.visible = false
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
  platformSession++
  accountSession++
  navigationGeneration++
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
async function revealCreated(platformId, accountId, requestId = '', guard = () => true) {
  if (disposed || !guard()) return
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
  if (disposed || selectedId.value !== platformId || !guard()) return
  const selector = requestId ? `[data-request-id="${requestId}"]` : `[data-account-id="${accountId}"]`
  const target = workspace.value?.querySelector(selector)
  target?.scrollIntoView({ block: 'nearest' })
  if (requestId) target?.focus({ preventScroll: true })
  highlightTimer = setTimeout(() => { highlightedRequestId.value = '' }, 3000)
}
async function requestSaved({ accountId, requestId, mode }) {
  const platformId = requestDialog.platformId, generation = navigationGeneration, editor = requestEditorGeneration
  const stillCurrent = () => generation === navigationGeneration && editor === requestEditorGeneration
  if (!(await refresh()) || selectedId.value !== platformId || !stillCurrent()) return
  if (mode === 'new') await revealCreated(platformId, accountId, requestId, stillCurrent)
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
  platformSession++
  Object.assign(platformForm, p ? { ...p, enabled: !!p.enabled }
    : { id: '', name: '', note: '', enabled: true, version: 1 })
  platformDialog.value = true
}
async function savePlatform() {
  if (saving.value) return
  if (!platformForm.name.trim()) return ElMessage.warning('请填写平台名称')
  const current = platformSession, generation = navigationGeneration, data = { ...platformForm }
  saving.value = true
  try {
    const result = await api(data.id ? `/platforms/${data.id}` : '/platforms', data.id ? 'PUT' : 'POST', data)
    if (disposed || current !== platformSession) return
    if (result.id && generation === navigationGeneration) selectedId.value = result.id
    platformDialog.value = false
    await Promise.all([refresh(), refreshSchedules()])
    if (!disposed) ElMessage.success('平台已保存')
  } catch (e) {
    if (!disposed && current === platformSession) report(e)
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
  accountSession++
  accountPlatformId.value = selectedId.value
  Object.assign(accountForm, a ? { ...a, enabled: !!a.enabled }
    : { id: '', alias: '', enabled: true, version: 1 })
  accountDialog.value = true
}
async function saveAccount() {
  if (saving.value) return
  if (!accountForm.alias.trim()) return ElMessage.warning('请填写账号别名')
  const current = accountSession, generation = navigationGeneration, data = { ...accountForm }
  const platformId = accountPlatformId.value, creating = !data.id
  saving.value = true
  try {
    const result = await api(data.id ? `/accounts/${data.id}` : `/platforms/${platformId}/accounts`, data.id ? 'PUT' : 'POST', data)
    if (disposed || current !== accountSession) return
    accountDialog.value = false
    const closedSession = accountSession
    if (await refresh()) {
      if (creating && selectedId.value === platformId && generation === navigationGeneration && accountSession === closedSession)
        await revealCreated(platformId, result.id, '', () => generation === navigationGeneration && accountSession === closedSession)
    }
    if (!disposed) ElMessage.success('账号已保存')
  } catch (e) {
    if (!disposed && current === accountSession) report(e)
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
    await api(`/requests/${request.id}`, 'PUT', {
      name: request.name, enabled: !request.enabled, rules: request.rules, version: request.version,
    })
    await refresh()
  } catch (e) {
    report(e)
  } finally {
    pendingRequestIds.value = pendingRequestIds.value.filter((id) => id !== request.id)
  }
}
function openRequest(accountId, mode = 'new', request = null) {
  const account = selected.value.accounts.find((a) => a.id === accountId)
  if (mode === 'view') {
    Object.assign(requestDetail, { visible: true, request: JSON.parse(JSON.stringify(request)), account: { id: account.id, alias: account.alias }, platform: { id: selected.value.id, name: selected.value.name } })
    return
  }
  Object.assign(requestDialog, {
    platformId: selectedId.value, accountId, mode, request, visible: true,
    contextLabel: `${selected.value.name} / ${account.alias}${request ? ` / ${request.name}` : ''}`,
  })
}
function openTemplates(request = null) {
  Object.assign(templatesDialog, {
    platformId: selectedId.value, seed: request ? { name: request.name, rules: request.rules } : null, visible: true,
  })
}
</script>

<template>
  <div class="platform-layout">
    <aside class="platform-list" aria-label="平台列表">
      <div class="pane-heading"><h2>我的平台<span class="section-count">{{ platforms.length }}</span></h2><button class="icon-button" aria-label="新增平台" @click="editPlatform()"><DeskIcon name="plus" /></button></div>
      <button v-for="p in platforms" :key="p.id" class="platform-choice" :class="{ selected: selectedId === p.id }" :aria-pressed="selectedId === p.id" @click="selectPlatform(p.id)">
        <span class="platform-monogram">{{ p.name.slice(0,1) }}</span><span class="platform-choice-copy"><strong>{{ p.name }}</strong><small>{{ platformCounts(p) }}</small></span><span class="status-dot" :class="{ off: !p.enabled }"></span>
      </button>
      <div class="pane-note"><strong>一个平台，一组独立配置。</strong><p>账号凭证不共用，计划按平台设置。</p><p>关闭浏览器，服务端仍继续执行。</p></div>
    </aside>
    <section ref="workspace" class="platform-workspace">
      <div class="page-heading platform-page-heading"><div class="platform-heading-left"><h1>平台与账号</h1><el-select :model-value="selectedId" class="platform-picker" aria-label="选择平台" @change="selectPlatform"><el-option v-for="p in platforms" :key="p.id" :label="p.name" :value="p.id" /></el-select></div><el-button @click="editPlatform()"><DeskIcon name="plus" />新增平台</el-button></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" class="catalog-error" />
      <div v-if="loading" class="muted" role="status">正在加载平台…</div>
      <el-empty v-else-if="!platforms.length && !error" description="还没有平台"><el-button type="primary" @click="editPlatform()">新增平台</el-button></el-empty>
      <template v-if="selected">
        <header class="catalog-overview">
          <div class="platform-identity"><span class="platform-emblem">{{ selected.name.slice(0,1) }}</span><div><div class="catalog-title"><h2>{{ selected.name }}</h2><el-switch class="platform-enable" :model-value="!!selected.enabled" inline-prompt active-text="启用" inactive-text="禁用" :width="56" :loading="pendingPlatformIds.includes(selected.id)" :disabled="pendingPlatformIds.includes(selected.id)" aria-label="平台启用状态" @change="togglePlatform(selected)" /></div><p class="catalog-note" aria-label="平台备注"><span>备注</span>{{ selected.note || '此平台下的账号各自保存独立请求' }}</p></div></div>
          <div class="catalog-actions actions"><el-button @click="openTemplates()"><DeskIcon name="template" />接口模板</el-button><el-button type="primary" :disabled="!selected.enabled" @click="execute('platform', selected.id)"><DeskIcon name="play" />执行此平台</el-button><el-dropdown trigger="click"><button class="icon-button" aria-label="平台设置"><DeskIcon name="more" /></button><template #dropdown><el-dropdown-menu><el-dropdown-item @click="editPlatform(selected)">编辑平台</el-dropdown-item><el-dropdown-item divided @click="remove('platforms', selected)">删除平台</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
        </header>
        <div class="platform-schedule" aria-label="当前平台定时计划"><DeskIcon name="clock" /><div class="schedule-content"><div class="schedule-summary-line"><strong>定时计划</strong><el-tag size="small" :type="planSummary.tone">{{ planSummary.label }}</el-tag><span v-if="planSummary.frequency">{{ planSummary.frequency }} {{ planSummary.times }} · {{ planSummary.timezone }}</span><el-button v-if="planError" text :loading="plansLoading" @click="loadSchedules">重试读取</el-button></div><div v-if="planSummary.frequency" class="schedule-policy">执行策略：{{ planSummary.strategy }}</div><div v-for="notice in planSummary.notices" :key="notice" class="schedule-notice">{{ notice }}</div><div v-if="planError" class="muted small">{{ planError }}</div></div><el-button text @click="editSchedule">设置计划</el-button></div>
        <div class="accounts-heading"><h3>账号与请求<span class="section-count">{{ selected.accounts.length }} 个账号 · {{ requestTotal }} 个请求</span></h3><div class="expand-actions"><el-button text :disabled="!groups.length" @click="expandVisible(true)">全部展开</el-button><el-button text :disabled="!groups.length" @click="expandVisible(false)">全部收起</el-button><el-button @click="editAccount()"><DeskIcon name="plus" />添加账号</el-button></div></div>
        <div v-if="selected.accounts.length" class="catalog-toolbar"><div class="filter-tabs" aria-label="请求状态筛选"><button v-for="tab in [{id:'all',name:'全部请求'},{id:'attention',name:'需处理'},{id:'completed',name:'今日完成'}]" :key="tab.id" class="filter-tab" :class="{ active: status === tab.id }" :aria-pressed="status === tab.id" @click="status = tab.id">{{ tab.name }}<span>{{ statusCounts[tab.id] }}</span></button></div><div class="search-controls"><el-input v-model="keyword" clearable placeholder="搜索账号 / 请求名称 / 主机" aria-label="搜索当前平台请求"><template #prefix><DeskIcon name="search" /></template></el-input><el-select v-model="status" class="catalog-status-filter" aria-label="请求状态筛选"><el-option label="全部状态" value="all" /><el-option label="需处理" value="attention" /><el-option label="今日完成" value="completed" /><el-option label="禁用" value="disabled" /></el-select></div></div>
        <div v-if="filtering" class="catalog-matches muted small" role="status">{{ groups.length }} 个账号 · {{ matchedRequests }} 个匹配请求<el-button text @click="clearFilters">清除筛选</el-button></div>
        <p v-if="feedback" class="catalog-feedback muted" role="status">{{ feedback }}</p>
        <el-empty v-if="!selected.accounts.length" description="这个平台还没有账号"><el-button @click="editAccount()">添加账号</el-button></el-empty>
        <el-empty v-else-if="!groups.length" description="没有符合条件的账号或请求"><el-button @click="clearFilters">清除筛选</el-button></el-empty>
        <div v-else class="account-sheet"><div class="table-legend"><span>请求 / 接口</span><span>今日状态</span><span>最近执行（北京）</span><span>操作</span></div>
          <AccountRequestGroup v-for="group in groups" :key="group.account.id" :account="group.account" :requests="group.requests" :platform-enabled="!!selected.enabled" :expanded="isAccountExpanded(currentState, group.account.id, filtering)" :filtering="filtering" :pending-request-ids="pendingRequestIds" :pending-account-ids="pendingAccountIds" :highlighted-request-id="highlightedRequestId"
            @toggle="toggleAccount(group.account.id)" @toggle-account="toggleAccountEnabled(group.account)" @add-request="openRequest(group.account.id)" @edit-account="editAccount(group.account)" @delete-account="remove('accounts', group.account)" @toggle-request="toggleRequest" @open-request="(mode, request) => openRequest(group.account.id, mode, request)" @delete-request="request => remove('requests', request, group.account)" @save-template="openTemplates" @execute-request="(request, force) => execute('request', request.id, force)" @show-run="showRun" />
        </div>
        <footer class="sheet-footer"><span><DeskIcon name="info" />列表仅含请求摘要；更新 cURL 创建不可变新版本，不改变已排队任务。</span><span>凭证不共用</span></footer>
      </template>
    </section>
  </div>
  <el-dialog v-model="platformDialog" :title="platformForm.id ? '编辑平台' : '新增平台'" width="470px" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving"><el-form label-position="top" :disabled="saving"><el-form-item label="平台名称"><el-input v-model="platformForm.name" maxlength="40" placeholder="例如：平台 A" /></el-form-item><el-form-item label="备注"><el-input v-model="platformForm.note" type="textarea" :rows="3" maxlength="500" /></el-form-item><el-form-item label="启用平台"><el-switch v-model="platformForm.enabled" /></el-form-item></el-form><template #footer><el-button :disabled="saving" @click="platformDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="savePlatform">保存平台</el-button></template></el-dialog>
  <el-dialog v-model="accountDialog" :title="accountForm.id ? '编辑账号' : '添加账号'" width="450px" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving"><el-form label-position="top" :disabled="saving"><el-form-item label="账号别名"><el-input v-model="accountForm.alias" maxlength="40" placeholder="例如：主账号" /></el-form-item><el-form-item label="启用账号"><el-switch v-model="accountForm.enabled" /></el-form-item></el-form><div class="muted">别名用于区分账号，鉴权信息直接从完整 cURL 保存。</div><template #footer><el-button :disabled="saving" @click="accountDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveAccount">保存账号</el-button></template></el-dialog>
  <RequestDialog v-model="requestDialog.visible" :mode="requestDialog.mode" :platform-id="requestDialog.platformId" :account-id="requestDialog.accountId" :request="requestDialog.request" :context-label="requestDialog.contextLabel" @saved="requestSaved" />
  <ScheduleDialog v-model="scheduleDialog.visible" :platform-id="scheduleDialog.platformId" :platform-name="scheduleDialog.platformName" @saved="scheduleSaved" />
  <RequestTemplatesDialog v-model="templatesDialog.visible" :platform-id="templatesDialog.platformId" :seed="templatesDialog.seed" />
  <RunDetailDrawer v-model="runDrawer.visible" :run-id="runDrawer.id" />
  <RequestDetailDrawer v-model="requestDetail.visible" :request="requestDetail.request" :account="requestDetail.account" :platform="requestDetail.platform" @edit="mode => { requestDetail.visible = false; openRequest(requestDetail.account.id, mode, requestDetail.request) }" @show-run="showRun" />
</template>
<style scoped>
.platform-layout { display: grid; grid-template-columns: 244px minmax(0,1fr); }
.platform-list { position: sticky; top: 0; height: 100dvh; border-right: 1px solid var(--line); background: #f0f2eb; padding: 31px 18px 20px; display: flex; flex-direction: column; overflow-y: auto; }
.pane-heading { display: flex; justify-content: space-between; align-items: center; padding: 0 8px 20px; }
.pane-heading h2 { font-size: 15px; }
.pane-heading .icon-button { border-color: var(--line-strong); background: #ffffff80; color: var(--forest); width: 28px; height: 28px; }
.platform-choice { width: 100%; text-align: left; display: grid; grid-template-columns: 37px minmax(0,1fr) 8px; align-items: center; gap: 11px; padding: 15px 12px; border: 1px solid transparent; border-radius: 9px; background: transparent; color: var(--ink); margin-bottom: 8px; }
.platform-choice:hover { background: #ffffff90; }
.platform-choice.selected { background: white; border-color: #d7dfcf; box-shadow: 0 3px 9px #1f3d2905; }
.platform-monogram { width: 37px; height: 37px; background: #e3e8dc; border-radius: 9px; display: grid; place-items: center; color: #58674e; font: bold 21px Georgia, 'Songti SC', serif; }
.platform-choice.selected .platform-monogram { background: var(--forest); color: var(--lime); }
.platform-choice-copy { min-width: 0; }
.platform-choice strong { font-size: 13px; display: block; overflow-wrap: anywhere; }
.platform-choice small { font-size: 10px; color: var(--muted); display: block; margin-top: 4px; }
.pane-note { margin-top: auto; padding: 18px 8px 0; border-top: 1px solid var(--line-strong); color: var(--muted); font-size: 11px; }
.pane-note strong { display: block; font-weight: 500; margin-bottom: 5px; }
.pane-note p + p { margin-top: 12px; font-size: 10px; }
.platform-workspace { min-width: 0; padding: 27px 38px 28px; max-width: 1420px; width: 100%; margin: 0 auto; }
.platform-heading-left { display: flex; align-items: center; gap: 18px; min-width: 0; }
.platform-picker { display: none; }
.platform-page-heading { margin: 0; }
.catalog-error { margin: 16px 0; }
.catalog-overview { padding: 24px 0 22px; display: flex; justify-content: space-between; align-items: center; gap: 24px; }
.platform-identity { display: flex; align-items: center; gap: 15px; min-width: 0; }
.platform-emblem { width: 54px; height: 54px; border-radius: 13px 13px 13px 4px; background: #e5edda; color: #466638; font: 700 32px Georgia, 'Songti SC', serif; display: grid; place-items: center; flex-shrink: 0; }
.catalog-title { display: flex; align-items: center; gap: 15px; flex-wrap: wrap; }
.catalog-title h2 { font: 600 30px/1.25 Georgia, 'Microsoft YaHei', serif; letter-spacing: -.6px; overflow-wrap: anywhere; }
.catalog-note { font-size: 12px; color: var(--muted); margin-top: 7px; white-space: pre-wrap; overflow-wrap: anywhere; }
.catalog-note > span { color: #7b866f; margin-right: 7px; }
.catalog-actions { flex-shrink: 0; flex-wrap: nowrap; }
.platform-schedule { border: 1px solid var(--line); border-radius: 9px; background: #f0f3eb; padding: 15px 18px; display: flex; align-items: center; gap: 13px; }
.platform-schedule > .icon { color: #7a8b67; width: 22px; height: 22px; }
.schedule-content { flex: 1; min-width: 0; }
.schedule-summary-line { display: flex; align-items: center; gap: 8px 12px; flex-wrap: wrap; font-size: 12px; }
.schedule-summary-line strong { font-size: 12px; }
.schedule-policy { margin-top: 3px; font-size: 11px; color: #6a795c; }
.schedule-notice { margin-top: 3px; color: var(--amber); font-size: 11px; }
.accounts-heading { display: flex; align-items: center; justify-content: space-between; margin: 26px 0 14px; gap: 12px; }
.accounts-heading h3 { font-size: 14px; font-weight: 600; }
.expand-actions { display: flex; align-items: center; gap: 4px; }
.expand-actions :deep(.el-button.is-text) { font-size: 11px; padding: 3px 8px; color: var(--muted); }
.catalog-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 14px; margin-bottom: 16px; }
.filter-tabs { display: flex; gap: 5px; }
.filter-tab { border: 1px solid transparent; background: transparent; padding: 6px 11px; border-radius: 6px; color: var(--muted); font-size: 12px; display: flex; gap: 7px; white-space: nowrap; }
.filter-tab.active { background: #e8efdf; border-color: #dfe7d5; color: var(--forest); }
.filter-tab span { font: 11px var(--mono); }
.search-controls { display: flex; align-items: center; gap: 8px; }
.search-controls > .el-input { width: 255px; }
.search-controls :deep(.el-input__wrapper), .catalog-status-filter :deep(.el-select__wrapper) { min-height: 35px; box-shadow: 0 0 0 1px var(--line) inset; }
.search-controls .icon { width: 15px; height: 15px; }
.catalog-status-filter { width: 120px; }
.catalog-matches { display: flex; align-items: center; gap: 8px; margin: -8px 0 10px; }
.catalog-feedback { margin-bottom: 12px; }
.account-sheet { border: 1px solid var(--line); background: white; border-radius: 11px; overflow: hidden; }
.table-legend { display: grid; grid-template-columns: minmax(175px,1fr) 138px 150px 130px; gap: 15px; padding: 10px 22px 10px 61px; color: #6e7767; font-size: 11px; border-bottom: 1px solid var(--line); }
.table-legend span:last-child { text-align: right; }
.sheet-footer { display: flex; justify-content: space-between; gap: 15px; font-size: 10px; color: #6e7b61; margin-top: 15px; }
.sheet-footer .icon { width: 12px; height: 12px; margin-right: 5px; }
@media(min-width:1680px) { .platform-workspace { padding-left: 52px; padding-right: 52px; } }
@media(max-width:1240px) { .platform-layout { grid-template-columns: 206px minmax(0,1fr); } .platform-list { padding-left: 12px; padding-right: 12px; } .platform-workspace { padding-left: 25px; padding-right: 25px; } .table-legend { grid-template-columns: minmax(145px,1fr) 123px 112px 116px; gap: 10px; padding-left: 45px; padding-right: 16px; } .search-controls > .el-input { width: 200px; } .catalog-toolbar { flex-wrap: wrap; } }
@media(max-width:1020px) { .platform-layout { grid-template-columns: minmax(0,1fr); } .platform-list { display: none; } .platform-picker { display: block; width: 165px; } .catalog-overview { gap: 12px; } }
@media(max-width:730px) {
 .platform-workspace { padding: 21px 18px 25px; }
 .platform-heading-left { flex-wrap: wrap; gap: 10px; }
 .platform-picker { width: 130px; }
 .catalog-overview { flex-direction: column; align-items: stretch; gap: 18px; padding: 20px 0; }
 .platform-emblem { width: 47px; height: 47px; font-size: 27px; }
 .catalog-title h2 { font-size: 27px; }
 .catalog-actions { justify-content: flex-end; }
 .catalog-actions :deep(.el-button) { font-size: 11px; min-height: 34px; }
 .platform-schedule { padding: 13px; gap: 10px; flex-wrap: wrap; align-items: flex-start; }
 .schedule-content { flex-basis: calc(100% - 36px); }
 .schedule-summary-line { font-size: 11px; }
 .platform-schedule > .el-button { margin-left: 32px; }
 .schedule-policy, .schedule-notice { font-size: 10px; }
 .accounts-heading { flex-wrap: wrap; margin-top: 21px; }
 .accounts-heading h3 { font-size: 13px; }
 .accounts-heading .section-count { font-size: 10px; margin-left: 6px; }
 .expand-actions { margin-left: auto; }
 .expand-actions :deep(.el-button.is-text) { font-size: 10px; padding: 3px 5px; }
 .catalog-toolbar { flex-direction: column; align-items: stretch; gap: 12px; }
 .filter-tab { padding: 5px 10px; font-size: 11px; }
 .search-controls { width: 100%; }
 .search-controls > .el-input { width: auto; flex: 1; min-width: 0; }
 .catalog-status-filter { width: 109px; flex-shrink: 0; }
 .table-legend { display: none; }
 .sheet-footer { font-size: 9px; }
 .sheet-footer span:first-child { max-width: 240px; }
 .sheet-footer span:last-child { white-space: nowrap; }
}
</style>
