<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, periodic, dateTime } from '../api'
import { scheduleFrequency, scheduleStrategy } from '../schedulePresentation'
import { filterPlans } from '../schedulesView.mjs'
import ScheduleDialog from '../components/ScheduleDialog.vue'
import DeskIcon from '../components/DeskIcon.vue'

const route = useRoute(), router = useRouter()
const plans = ref([]), settings = ref(null), dialog = ref(false), error = ref(''), loaded = ref(false)
const search = ref(''), filter = ref('all'), pausing = ref(false), pending = ref(new Set()), writeErrors = ref({})
const platformId = ref(''), platformName = ref(''), focusedId = ref(''), queryError = ref('')
const visiblePlans = computed(() => filterPlans(plans.value, search.value, filter.value))
const filters = [['all', '全部计划'], ['enabled', '已启用'], ['disabled', '已暂停']]
let sequence = 0, disposed = false, latestLoad, handledQuery = ''
function load() {
  if (pausing.value || pending.value.size) return latestLoad ?? Promise.resolve(false)
  const current = ++sequence
  latestLoad = (async () => {
    try {
      const [nextPlans, nextSettings] = await Promise.all([api('/schedules'), api('/settings')])
      if (disposed || current !== sequence) return false
      plans.value = nextPlans
      settings.value = nextSettings
      loaded.value = true
      error.value = ''
      locateQuery()
      return true
    } catch (e) {
      if (!disposed && current === sequence) error.value = e.message
      return false
    }
  })()
  return latestLoad
}
async function refresh() {
  let pendingLoad = load(), accepted = await pendingLoad
  while (!disposed && pendingLoad !== latestLoad) {
    pendingLoad = latestLoad
    accepted = await pendingLoad
  }
  return !disposed && accepted
}
periodic(load)
onUnmounted(() => { disposed = true; sequence++ })
function edit(plan) {
  platformId.value = plan.platformId
  platformName.value = plan.name
  dialog.value = true
}
function queryId() {
  return typeof route.query.platformId === 'string' ? route.query.platformId : ''
}
async function locateQuery() {
  const id = queryId()
  if (!loaded.value || !id || handledQuery === id) return
  const plan = plans.value.find((item) => item.platformId === id)
  if (!plan) { queryError.value = '未找到指定平台的计划，请检查平台是否仍存在。'; return }
  handledQuery = id
  queryError.value = ''
  focusedId.value = id
  search.value = ''
  filter.value = 'all'
  edit(plan)
  await nextTick()
  if (disposed || queryId() !== id) return
  document.querySelector(`[data-plan-id="${CSS.escape(id)}"]`)?.scrollIntoView({ block: 'nearest' })
}
watch(() => route.query.platformId, () => {
  handledQuery = ''
  dialog.value = false
  platformId.value = ''
  platformName.value = ''
  focusedId.value = queryId()
  queryError.value = ''
  if (!queryId()) return
  locateQuery()
}, { immediate: true })
async function saved() {
  if (!(await refresh()) && !disposed) error.value = `计划已保存，但刷新失败：${error.value}`
}
async function toggle(plan) {
  if (pending.value.has(plan.platformId)) return
  pending.value.add(plan.platformId)
  delete writeErrors.value[plan.platformId]
  sequence++
  try {
    await api(`/platforms/${plan.platformId}/schedule`, 'PUT', { ...plan.spec, enabled: !plan.spec.enabled })
  } catch (e) {
    if (!disposed) writeErrors.value[plan.platformId] = e.status === 409
      ? '计划版本已变化，本次未覆盖。请核对最新计划后再操作。' : e.message
  } finally {
    pending.value.delete(plan.platformId)
    if (!disposed) await refresh()
  }
}
async function pause() {
  if (!settings.value || pausing.value) return
  pausing.value = true
  sequence++
  try {
    await api('/settings', 'PUT', { ...settings.value, paused: !settings.value.paused })
  } catch (e) {
    if (!disposed) error.value = e.status === 409
      ? '全局设置版本已变化，本次未覆盖。已重新读取，请核对后再操作。' : e.message
    // Refresh the version without silently retrying the write.
    pausing.value = false
    const message = error.value
    await refresh()
    if (!disposed) error.value = message
    return
  } finally { pausing.value = false }
  if (!disposed) await refresh()
}
function clearFilters() { search.value = ''; filter.value = 'all' }
</script>

<template>
  <div class="schedules-page">
    <header class="page-heading schedules-heading">
      <div><h1>定时计划</h1><p>一个平台，一份独立计划。按自己的节奏执行。</p></div>
      <el-button v-if="settings" :type="settings.paused ? 'primary' : 'default'" :loading="pausing" @click="pause">
        <DeskIcon :name="settings.paused ? 'play' : 'pause'" />{{ settings.paused ? '恢复全局定时' : '暂停全局定时' }}
      </el-button>
    </header>
    <div v-if="error || queryError" class="plan-notice is-error" role="alert">
      <span>{{ error || queryError }}</span><el-button v-if="error" size="small" @click="refresh">重试读取</el-button>
    </div>
    <div v-if="settings?.paused" class="plan-notice paused-notice" role="status">
      <DeskIcon name="pause" class="pause-symbol" />
      <div><strong>全局定时已暂停</strong><p>只阻止新的自动触发；手动执行仍可使用，现有队列继续执行。</p></div>
    </div>
    <div class="plan-toolbar">
      <div class="plan-filter-tabs" role="group" aria-label="计划筛选">
        <button v-for="[value, label] in filters" :key="value" :class="{ active: filter === value }"
          :aria-pressed="filter === value" @click="filter = value">{{ label }}</button>
      </div>
      <el-input v-model="search" class="plan-search" clearable placeholder="搜索平台计划" aria-label="搜索平台计划"><template #prefix><DeskIcon name="search" /></template></el-input>
    </div>
    <div class="plan-caption">计划时刻表示平台批次开始 · 下一时刻统一显示为北京时间</div>
    <div v-if="!loaded && !error" class="plan-empty" role="status">正在读取平台计划…</div>
    <div v-else-if="loaded && !visiblePlans.length" class="plan-empty">
      <DeskIcon name="clock" class="empty-clock" />
      <h2>{{ plans.length ? '暂无匹配的计划' : '还没有平台计划' }}</h2>
      <p>每个平台拥有独立计划，支持每日或指定星期与多个时刻。</p>
      <el-button v-if="plans.length" @click="clearFilters">显示全部计划</el-button>
      <el-button v-else type="primary" @click="router.push('/platforms')">添加平台</el-button>
    </div>
    <div class="plan-cards">
      <article v-for="plan in visiblePlans" :key="plan.platformId" class="plan-card"
        :class="{ off: !plan.spec.enabled, focused: focusedId === plan.platformId }"
        :data-plan-id="plan.platformId" :aria-label="`${plan.name}定时计划`">
        <div class="plan-card-top">
          <div class="plan-card-identity">
            <span class="platform-monogram" aria-hidden="true">{{ Array.from(plan.name)[0] }}</span>
            <div class="plan-identity-copy">
              <button class="plan-platform-link" :aria-label="`定位平台 ${plan.name}`"
                @click="router.push({ path: '/platforms', query: { platformId: plan.platformId } })">{{ plan.name }}</button>
              <p>{{ plan.platformEnabled ? '平台已启用' : '平台已停用' }} · 计划 v{{ plan.spec.revision }}</p>
            </div>
          </div>
          <div class="plan-card-time-block">
            <div class="plan-card-times">{{ plan.spec.times.join(' / ') }}</div>
            <div class="plan-card-frequency">{{ scheduleFrequency(plan.spec) }} · {{ plan.spec.timezone }}</div>
          </div>
          <div class="plan-card-actions">
            <el-switch :model-value="plan.spec.enabled" :disabled="pending.has(plan.platformId)"
              :loading="pending.has(plan.platformId)" :aria-label="`${plan.name}定时启用`" @change="toggle(plan)" />
            <el-button size="small" :disabled="pending.has(plan.platformId)" @click="edit(plan)">编辑计划</el-button>
          </div>
        </div>
        <div class="plan-card-bottom">
          <span>下一计划时刻 <strong>{{ plan.spec.enabled ? dateTime(plan.nextAt) : '计划未启用' }}</strong>
            <span v-if="settings?.paused"> · 全局暂停中</span><span v-if="!plan.platformEnabled"> · 平台停用，不发送</span>
          </span>
          <span>{{ scheduleStrategy(plan.spec) }}</span>
        </div>
        <p v-if="writeErrors[plan.platformId]" class="plan-write-error" role="alert">{{ writeErrors[plan.platformId] }}</p>
      </article>
    </div>
    <div class="schedule-help">
      同平台账号与请求依次执行，跨平台有界并发。服务器约每 5 秒扫描，实际发送受队列负载影响。
      服务停机期间不能执行，恢复后仅考虑当前业务日期与补执行窗口；暂停定时不会取消已经排队的任务。
    </div>
    <ScheduleDialog v-model="dialog" :platform-id="platformId" :platform-name="platformName" @saved="saved" />
  </div>
</template>

<style scoped>
.schedules-page { color: #173b31; font: 13px/1.6 "Microsoft YaHei", "微软雅黑", sans-serif; min-width: 0; --el-color-primary: #214e3e; --el-color-primary-light-9: #f0f5e9; --el-color-primary-light-3: #6b8e77; --el-border-radius-base: 7px; }
.schedules-heading { margin-bottom: 23px; gap: 16px; }
.schedules-heading h1 { font-size: 23px; font-weight: 600; line-height: 1.65; margin: 0; }
.schedules-heading p { margin: 6px 0 0; color: var(--muted); font-size: 11px; }
.plan-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 10px; }
.plan-filter-tabs { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.plan-filter-tabs button { border: 0; background: transparent; color: #74816b; padding: 8px 14px; border-radius: 7px; font: inherit; cursor: pointer; }
.plan-filter-tabs button.active { background: #eaf0dd; color: #214e3e; font-weight: 600; }
.plan-filter-tabs button:hover { background: #eef2e7; }
.plan-filter-tabs button:focus-visible, .plan-platform-link:focus-visible { outline: 2px solid #214e3e; outline-offset: 3px; }
.plan-search { width: 230px; max-width: 100%; }
.plan-caption { color: #839079; font-size: 11px; margin: 0 0 16px; }
.plan-cards { display: grid; gap: 15px; }
.plan-card { background: #fff; border: 1px solid #e3e8dc; border-radius: 12px; overflow: hidden; }
.plan-card.focused { border-color: #87a776; box-shadow: 0 0 0 2px #dcebb5; }
.plan-card-top { padding: 21px 23px; display: grid; grid-template-columns: minmax(160px, 1fr) minmax(170px, 1fr) auto; gap: 20px; align-items: center; }
.plan-card-identity { display: flex; align-items: center; gap: 13px; min-width: 0; }
.platform-monogram { display: grid; place-items: center; width: 39px; height: 39px; flex-shrink: 0; background: #eef3e5; border: 1px solid #e0e8d4; border-radius: 10px; color: #466b45; font-size: 17px; }
.plan-identity-copy { min-width: 0; }
.plan-platform-link { border: 0; padding: 0; background: transparent; font: inherit; font-size: 14px; font-weight: 600; color: #214e3e; text-align: left; overflow-wrap: anywhere; cursor: pointer; }
.plan-platform-link:hover { text-decoration: underline; text-underline-offset: 4px; }
.plan-card-identity p { font-size: 11px; color: #7c8a73; margin: 5px 0 0; }
.plan-card-times { font: 25px/1.35 Georgia, serif; font-variant-numeric: tabular-nums; color: #405737; overflow-wrap: anywhere; }
.plan-card-frequency { font-size: 11px; color: #7c8a73; margin-top: 5px; overflow-wrap: anywhere; }
.plan-card-actions { display: flex; align-items: center; gap: 17px; }
.plan-card-actions :deep(.el-button) { margin-left: 0; }
.plan-card-bottom { display: flex; justify-content: space-between; gap: 14px; padding: 12px 23px; border-top: 1px solid #edf0e8; background: #fafbf7; font-size: 11px; color: #78866b; }
.plan-card-bottom strong { font-weight: 500; color: #586d4b; }
.plan-card-bottom > span { overflow-wrap: anywhere; }
.plan-card.off .plan-card-times { color: #939c89; }
.plan-notice { display: flex; align-items: center; gap: 14px; background: #edf2e4; border: 1px solid #dce5cf; border-radius: 10px; padding: 14px 18px; margin: 0 0 20px; }
.plan-notice p { font-size: 11px; color: #758569; margin: 3px 0 0; }
.plan-notice strong { font-size: 12px; font-weight: 600; }
.pause-symbol { color: #68854c; font-size: 21px; }
.is-error, .plan-write-error { color: #a44e42; background: #faf0e9; border-color: #eedbcb; }
.plan-write-error { font-size: 12px; margin: 0; padding: 10px 23px; }
.schedule-help { font-size: 11px; line-height: 1.9; color: #7c8a73; border-left: 2px solid #dcebb5; padding: 1px 0 1px 14px; margin-top: 22px; }
.plan-empty { padding: 42px 20px; border: 1px solid #e3e8dc; background: white; border-radius: 12px; text-align: center; color: #78866b; }
.plan-empty h2 { font-size: 14px; color: #214e3e; margin: 9px 0; }
.plan-empty p { font-size: 12px; margin: 0 0 18px; }
.empty-clock { font-size: 30px; color: #88a172; }
@media (max-width: 730px) { .schedules-heading h1 { font-size: 19px; } .schedules-heading { margin-bottom: 19px; } .schedules-heading p { font-size: 10px; } }
@media (max-width: 1000px) { .plan-card-top { grid-template-columns: minmax(140px, 1fr) minmax(120px, 1fr) auto; gap: 12px; padding: 18px; } .plan-card-bottom { flex-direction: column; gap: 4px; padding: 11px 18px; } .plan-card-actions { gap: 10px; } }
@media (max-width: 620px) { .schedules-heading { align-items: flex-start; flex-wrap: wrap; } .schedules-heading p { font-size: 10px; } .plan-toolbar { flex-wrap: wrap; gap: 10px; } .plan-search { width: 100%; } .plan-filter-tabs button { padding: 7px 12px; } .plan-card-top { grid-template-columns: minmax(0, 1fr) auto; gap: 15px 10px; padding: 17px; } .plan-card-actions { grid-column: 2; grid-row: 1; gap: 8px; } .plan-card-time-block { grid-column: 1 / -1; padding-left: 49px; } .plan-card-identity { gap: 10px; } .plan-card-times { font-size: 24px; } .plan-platform-link { font-size: 13px; } .plan-card-identity p { font-size: 10px; } .plan-card-actions :deep(.el-button) { padding: 7px 9px; font-size: 11px; } .plan-card-bottom { font-size: 10px; } .plan-notice { padding: 13px; } }
</style>
