<script setup>
import { computed, onUnmounted, reactive, ref } from 'vue'
import { api, periodic, execute, report, confirm, dateTime } from '../api'
import { attentionRequests, platformLocation } from '../requestPresentation'
import DeskIcon from '../components/DeskIcon.vue'
import StatusChip from '../components/StatusChip.vue'
import RunDetailDrawer from '../components/RunDetailDrawer.vue'
import BatchDetailDrawer from '../components/BatchDetailDrawer.vue'
const state = ref(null), platforms = ref([]), error = ref(''), executing = ref(false)
const runDrawer = reactive({ visible: false, id: '' }), batchDrawer = reactive({ visible: false, id: '' })
const attention = computed(() => attentionRequests(platforms.value))
const nextPlans = computed(() => (state.value?.schedules || []).filter(plan => plan.spec.enabled && plan.platformEnabled && plan.nextAt).sort((a,b) => a.nextAt.localeCompare(b.nextAt)).slice(0,5))
let sequence = 0, disposed = false
async function load() {
  const current = ++sequence
  try {
    const [dashboard, catalogue] = await Promise.all([api('/dashboard'), api('/platforms')])
    if (disposed || current !== sequence) return
    state.value = dashboard; platforms.value = catalogue; error.value = ''
  } catch (e) { if (!disposed && current === sequence) error.value = e.message }
}
periodic(load, 3000)
onUnmounted(() => { disposed = true; sequence++; runDrawer.visible = false; batchDrawer.visible = false })
async function executeAll() { if (executing.value) return; executing.value = true; try { await execute('all'); await load() } finally { executing.value = false } }
async function cancel(id) {
  if (!(await confirm('取消此批次尚未发送的剩余请求？已经发送的请求不会自动重试。'))) return
  try { await api(`/batches/${id}/cancel`, 'POST', {}); await load() } catch(e) { report(e) }
}
function showRun(id) { Object.assign(runDrawer, { visible: true, id }) }
function showBatch(id) { Object.assign(batchDrawer, { visible: true, id }) }
function planTime(value, timezone) { return new Intl.DateTimeFormat('zh-CN', { timeZone: timezone, hour:'2-digit',minute:'2-digit',hour12:false }).format(new Date(value)) }
</script>
<template>
  <div class="page-heading"><div><h1>今日概览</h1><p class="page-subtitle">今天的任务、需要关注的结果，以及接下来的安排。</p></div><div class="heading-actions"><el-button @click="load"><DeskIcon name="refresh" />刷新</el-button><el-button type="primary" :loading="executing" @click="executeAll"><DeskIcon name="play" />执行全部平台</el-button></div></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="space-top" />
  <template v-if="state">
    <div class="kpi-row" aria-label="工作区统计">
      <RouterLink to="/platforms" class="kpi"><span class="kpi-label"><DeskIcon name="layers" />已管理平台</span><strong class="kpi-value">{{ state.platforms }}</strong><span class="kpi-description">{{ state.accounts }} 个独立账号</span></RouterLink>
      <RouterLink to="/platforms" class="kpi"><span class="kpi-label"><DeskIcon name="template" />请求总数</span><strong class="kpi-value">{{ state.requests }}</strong><span class="kpi-description">包含启用与禁用的请求</span></RouterLink>
      <div class="kpi"><span class="kpi-label"><DeskIcon name="check" />今日已完成</span><strong class="kpi-value">{{ state.completed }}</strong><span class="kpi-description">各平台业务日期的独立日标记</span></div>
      <button class="kpi" @click="($event.currentTarget.ownerDocument.getElementById('attention-list'))?.scrollIntoView({block:'start'})"><span class="kpi-label"><DeskIcon name="alert" />需要处理</span><strong class="kpi-value">{{ state.needsAttention }}</strong><span class="kpi-description">凭证暂停或今日结果待确认</span></button>
    </div>
    <div class="dashboard-grid">
      <section id="attention-list" class="panel flush"><div class="panel-heading"><h2>需要你关注<span class="section-count">{{ attention.length }}</span></h2><RouterLink to="/platforms" class="text-button">管理请求<DeskIcon name="arrow" /></RouterLink></div>
        <div v-for="item in attention.slice(0,8)" :key="item.request.id" class="attention-item"><span class="attention-symbol"><DeskIcon :name="item.request.authPaused ? 'key' : 'alert'" /></span><div class="attention-copy"><h3>{{ item.platform.name }} · {{ item.account.alias }}</h3><p>{{ item.request.name }} · {{ item.request.authPaused ? (item.request.safeHost ? '凭证暂停，需更新完整 cURL' : '尚未配置 cURL，请更新请求') : '今日结果待确认，请先核对平台' }}</p></div><RouterLink :to="platformLocation(item.platform.id,item.request.id,item.request.authPaused ? 'update' : undefined)"><el-button size="small">{{ item.request.authPaused ? '更新 cURL' : '定位请求' }}</el-button></RouterLink></div>
        <div v-if="!attention.length" class="empty-state"><DeskIcon name="check" /><h3>目前没有需要处理的结果</h3><p>凭证暂停与待确认结果会显示在这里。</p></div>
        <div class="panel-footnote">待确认可能已经完成，核对平台前不要直接重跑。{{ attention.length > 8 ? `另有 ${attention.length-8} 个请求，请进入平台查看。` : '' }}</div>
      </section>
      <section class="panel flush"><div class="panel-heading"><h2>接下来的计划</h2><RouterLink to="/schedules" class="text-button">全部计划<DeskIcon name="arrow" /></RouterLink></div><div class="next-plans"><div v-for="plan in nextPlans" :key="plan.platformId" class="next-plan"><span class="status-dot"></span><div class="next-plan-copy"><RouterLink :to="{ path:'/schedules',query:{platformId:plan.platformId} }">{{ plan.name }}</RouterLink><p>{{ plan.spec.frequency === 'daily' ? '每日' : '指定星期' }} · {{ plan.spec.timezone === 'UTC' ? 'UTC' : '北京时间' }}</p></div><div class="next-plan-time"><strong>{{ planTime(plan.nextAt, plan.spec.timezone) }}</strong><p>{{ dateTime(plan.nextAt) }}（北京）</p></div></div></div><div v-if="!nextPlans.length" class="empty-state"><DeskIcon name="clock" /><h3>还没有生效的计划</h3><p>配置平台时间，服务端将按计划派发。</p><RouterLink to="/schedules"><el-button size="small">设置计划</el-button></RouterLink></div></section>
    </div>
    <div class="dashboard-grid">
      <section class="panel flush"><div class="panel-heading"><h2>最近执行</h2><RouterLink to="/runs" class="text-button">查看全部<DeskIcon name="arrow" /></RouterLink></div><button v-for="run in state.recent" :key="run.id" class="recent-row" @click="showRun(run.id)"><span><strong>{{ run.platformName }} · {{ run.requestName }}</strong><small>{{ run.alias }} · {{ run.source === 'auto' ? '定时' : '手动' }} · {{ run.safeSummary || '等待执行' }}</small></span><StatusChip :status="run.status" /><span class="recent-time">{{ dateTime(run.finishedAt || run.createdAt) }}</span><DeskIcon name="arrow" /></button><div v-if="!state.recent.length" class="empty-state"><DeskIcon name="record" /><h3>还没有执行记录</h3><p>添加请求并执行后，可以在这里查看结果。</p></div></section>
      <section class="panel flush"><div class="panel-heading"><h2>执行队列<span class="section-count">{{ state.active.length }}</span></h2><span class="small-note">服务器持久队列</span></div><div class="queue-list"><div v-for="batch in state.active" :key="batch.id" class="queue-card"><div class="queue-card-heading"><button class="text-button" @click="showBatch(batch.id)">{{ batch.name }}</button><el-button text size="small" :disabled="!!batch.cancelRequested" @click="cancel(batch.id)">{{ batch.cancelRequested ? '取消中' : '取消剩余' }}</el-button></div><div class="queue-meta"><span>{{ batch.status === 'running' ? '执行中' : '等待执行' }} · {{ batch.source === 'auto' ? '定时' : '手动' }}</span><span>{{ batch.done }} / {{ batch.total }}</span></div><div class="queue-meter" role="progressbar" :aria-label="`${batch.name}批次进度`" :aria-valuenow="batch.done" :aria-valuemin="0" :aria-valuemax="batch.total"><span :style="{width: `${batch.total ? Math.round(batch.done/batch.total*100) : 100}%`}"></span></div></div></div><div v-if="!state.active.length" class="empty-state"><DeskIcon name="check" /><h3>队列已就绪</h3><p>当前没有执行中的任务，浏览器关闭不影响服务。</p></div></section>
    </div>
    <footer class="page-footer"><span>统计按平台业务日期计算；日标记不随历史执行记录清理。</span><span>任务由服务器执行</span></footer>
  </template>
  <RunDetailDrawer v-model="runDrawer.visible" :run-id="runDrawer.id" />
  <BatchDetailDrawer v-model="batchDrawer.visible" :batch-id="batchDrawer.id" @show-run="showRun" @cancelled="load" />
</template>
<style scoped>
.attention-item { display: flex; align-items: center; gap: 13px; padding: 17px 22px; border-top: 1px solid #edf0e8; }
.attention-symbol { width: 35px; height: 35px; display: grid; place-items: center; background: #f8efde; color: #a2793a; border-radius: 9px; flex-shrink: 0; }
.attention-copy { flex: 1; min-width: 0; }
.attention-copy h3 { font-size: 12px; }
.attention-copy p { font-size: 10px; color: var(--muted); margin-top: 5px; overflow-wrap: anywhere; }
.next-plans { padding: 2px 22px 16px; }
.next-plan { display: grid; grid-template-columns: 9px minmax(0,1fr) auto; gap: 12px; align-items: start; padding: 17px 0; border-top: 1px solid #edf0e8; }
.next-plan .status-dot { margin-top: 8px; background: #839b6a; }
.next-plan-copy a { font-size: 12px; font-weight: 500; }
.next-plan-copy p, .next-plan-time p { font-size: 10px; color: var(--muted); margin-top: 5px; }
.next-plan-time { text-align: right; }
.next-plan-time strong { font: 22px/1.2 Georgia,serif; color: #42593b; }
.recent-row { width: 100%; display: grid; grid-template-columns: minmax(0,1fr) 85px 135px 18px; align-items: center; gap: 13px; padding: 14px 22px; border: 0; border-top: 1px solid #edf0e8; text-align: left; background: transparent; color: var(--ink); }
.recent-row:hover { background: #fcfdf9; }
.recent-row strong { font-size: 12px; font-weight: 500; }
.recent-row small { display: block; font-size: 10px; color: var(--muted); margin-top: 5px; overflow-wrap: anywhere; }
.recent-time { font: 10px/1.8 var(--mono); color: var(--muted); text-align: right; }
.recent-row > .icon { width: 14px; height: 14px; color: var(--muted); }
.queue-list { padding: 0 22px 18px; }
.queue-card { border-top: 1px solid #edf0e8; padding: 15px 0; }
.queue-card-heading { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.queue-card-heading .text-button { font-size: 12px; font-weight: 500; }
.queue-meta { display: flex; justify-content: space-between; gap: 8px; font-size: 10px; color: var(--muted); margin-top: 9px; }
@media(max-width:1020px) { .recent-row { grid-template-columns: minmax(0,1fr) 80px 18px; } .recent-time { display: none; } }
@media(max-width:730px) { .attention-item { padding: 15px 17px; gap: 9px; } .attention-symbol { width: 30px; height: 30px; } .attention-copy h3 { font-size: 11px; } .attention-copy p { font-size: 10px; } .next-plans { padding: 0 17px 10px; } .recent-row { padding: 14px 17px; grid-template-columns: minmax(0,1fr) 75px 18px; gap: 8px; } .recent-row strong { font-size: 11px; } .queue-list { padding-left: 17px; padding-right: 17px; } }
</style>
