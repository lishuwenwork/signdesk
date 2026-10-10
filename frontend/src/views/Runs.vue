<script setup>
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, periodic, labels, dateTime } from '../api'
import { platformLocation } from '../requestPresentation'
import DeskIcon from '../components/DeskIcon.vue'
import StatusChip from '../components/StatusChip.vue'
import RunDetailDrawer from '../components/RunDetailDrawer.vue'
import BatchDetailDrawer from '../components/BatchDetailDrawer.vue'
const route = useRoute(), router = useRouter()
const platforms = ref([]), data = ref({ items:[],total:0 }), active = ref([]), error = ref(''), loading = ref(false), keyword = ref('')
const filter = reactive({ platformId:String(route.query.platformId || ''),status:String(route.query.status || ''),source:String(route.query.source || ''),page:1,size:20 })
const runDrawer = reactive({ visible:false,id:'' }), batchDrawer = reactive({ visible:false,id:'' })
const rows = computed(() => { const q=keyword.value.trim().toLowerCase(); return data.value.items.filter(run => !q || [run.platformName,run.alias,run.requestName,run.safeSummary].some(value => String(value || '').toLowerCase().includes(q))) })
let sequence=0, disposed=false
async function load() {
  const current=++sequence
  loading.value=true
  try {
    const [logs,catalogue,batches]=await Promise.all([api(`/runs?${new URLSearchParams(filter)}`),api('/platforms'),api('/batches')])
    if(disposed || current!==sequence) return
    data.value=logs;platforms.value=catalogue;active.value=batches;error.value=''
    if(filter.page>1 && !logs.items.length && logs.total<=((filter.page-1)*filter.size)) { filter.page=Math.max(1,Math.ceil(logs.total/filter.size)); await load() }
  } catch(e) { if(!disposed && current===sequence) error.value=e.message }
  finally { if(!disposed && current===sequence) loading.value=false }
}
periodic(load,3000)
onUnmounted(()=> {disposed=true;sequence++;runDrawer.visible=false;batchDrawer.visible=false})
function changeFilter() {filter.page=1;keyword.value='';load();router.replace({path:'/runs',query:{...(filter.platformId?{platformId:filter.platformId}:{}),...(filter.status?{status:filter.status}:{}),...(filter.source?{source:filter.source}:{})}})}
function show(id) {Object.assign(runDrawer,{visible:true,id})}
function showBatch(id) {Object.assign(batchDrawer,{visible:true,id})}
watch(()=>route.query.runId,id=> {if(id) show(String(id))},{immediate:true})
function reset(){Object.assign(filter,{platformId:'',status:'',source:'',page:1});changeFilter()}
</script>
<template>
  <div class="page-heading"><div><h1>执行记录</h1><p class="page-subtitle">每一次请求都有记录。先查看结果，再决定下一步。</p></div><el-button :loading="loading" @click="load"><DeskIcon name="refresh" />刷新记录</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="space-top" />
  <div v-if="active.length" class="banner"><DeskIcon name="clock" /><div class="banner-copy"><strong>{{ active.length }} 个活动批次</strong><p>请求由服务器持久队列执行，关闭网页不会取消。</p></div><el-dropdown trigger="click"><el-button size="small">查看队列</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="batch in active" :key="batch.id" @click="showBatch(batch.id)">{{ batch.name }} · {{ batch.done }}/{{ batch.total }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
  <section class="panel flush records-panel">
    <div class="run-filters"><el-select v-model="filter.platformId" placeholder="全部平台" clearable aria-label="记录平台筛选" @change="changeFilter"><el-option v-for="p in platforms" :key="p.id" :label="p.name" :value="p.id" /></el-select><el-select v-model="filter.status" placeholder="全部结果" clearable aria-label="记录结果筛选" @change="changeFilter"><el-option v-for="(name,key) in labels" :key="key" :label="name" :value="key" /></el-select><el-select v-model="filter.source" placeholder="全部来源" clearable aria-label="记录来源筛选" @change="changeFilter"><el-option label="手动执行" value="manual" /><el-option label="定时执行" value="auto" /></el-select><el-input v-model="keyword" clearable placeholder="搜索当前页记录" aria-label="搜索当前页记录"><template #prefix><DeskIcon name="search" /></template></el-input><button v-if="filter.platformId || filter.status || filter.source || keyword" class="text-button" @click="reset">重置筛选</button><span class="run-filter-note">HTTP 200 不自动代表业务成功</span></div>
    <div class="table-wrapper"><table class="log-table"><thead><tr><th>平台 / 账号</th><th>请求 / 来源</th><th>结果</th><th>HTTP / 耗时</th><th>摘要</th><th>时间（北京）</th><th>操作</th></tr></thead><tbody><tr v-for="run in rows" :key="run.id" :data-run-id="run.id"><td><RouterLink :to="platformLocation(run.platformId,run.requestId)" class="run-platform">{{ run.platformName }}</RouterLink><div class="cell-sub">{{ run.alias }}</div></td><td>{{ run.requestName }}<div class="cell-sub">版本 {{ run.requestRevision }} · {{ run.source === 'auto' ? '定时' : '手动' }}</div></td><td><StatusChip :status="run.status" /></td><td class="cell-number">{{ run.httpStatus ?? '—' }}<div class="cell-sub">{{ run.durationMs == null ? '—' : `${run.durationMs} ms` }}</div></td><td class="cell-summary">{{ run.safeSummary || '等待执行' }}</td><td class="cell-time">{{ dateTime(run.finishedAt || run.createdAt) }}</td><td><el-button text size="small" @click="show(run.id)">详情</el-button></td></tr></tbody></table></div>
    <div v-if="!rows.length" class="empty-state"><DeskIcon name="record" /><h3>{{ loading ? '正在读取执行记录…' : '暂无符合条件的执行记录' }}</h3><p v-if="keyword">搜索仅针对当前页，可以清除搜索或切换页码。</p></div>
    <div class="pagination"><span class="pagination-copy">共 {{ data.total }} 条记录{{ keyword ? ` · 当前页匹配 ${rows.length} 条` : '' }}</span><el-pagination v-model:current-page="filter.page" v-model:page-size="filter.size" :page-sizes="[10,20,50,100]" :total="data.total" layout="sizes, prev, pager, next" @size-change="filter.page=1;load()" @current-change="load" /></div>
  </section>
  <footer class="page-footer"><span>响应体仅在详情按需查看，随记录保留策略清理；日标记独立保留。</span><span>HTML 纯文本展示</span></footer>
  <RunDetailDrawer v-model="runDrawer.visible" :run-id="runDrawer.id" />
  <BatchDetailDrawer v-model="batchDrawer.visible" :batch-id="batchDrawer.id" @show-run="show" @cancelled="load" />
</template>
<style scoped>
.records-panel { margin-top: 20px; }
.run-filters { display: flex; flex-wrap: wrap; gap: 9px; align-items: center; padding: 17px 19px; border-bottom: 1px solid var(--line); }
.run-filters .el-select { width: 145px; }
.run-filters .el-input { width: 185px; }
.run-filters :deep(.el-input__wrapper),.run-filters :deep(.el-select__wrapper) { min-height: 34px; font-size: 11px; }
.run-filters .icon { width: 14px; height: 14px; }
.run-filter-note { margin-left: auto; font-size: 10px; color: var(--muted); }
.table-wrapper { overflow-x: auto; }
.log-table { width: 100%; border-collapse: collapse; text-align: left; min-width: 950px; }
.log-table thead { background: #fafbf7; color: var(--muted); font-size: 10px; }
.log-table th { font-weight: 400; padding: 12px 16px; border-bottom: 1px solid var(--line); white-space: nowrap; }
.log-table td { padding: 15px 16px; font-size: 12px; border-bottom: 1px solid #edf0e8; vertical-align: middle; }
.log-table tbody tr:hover { background: #fcfdf9; }
.run-platform { font-weight: 500; }
.run-platform:hover { text-decoration: underline; text-underline-offset: 4px; }
.cell-sub { font-size: 10px; color: var(--muted); margin-top: 5px; }
.cell-summary { max-width: 215px; font-size: 11px !important; color: var(--muted); overflow-wrap: anywhere; }
.cell-time { font: 10px/1.9 var(--mono); color: var(--muted); white-space: nowrap; }
.cell-number { font: 11px var(--mono); font-variant-numeric: tabular-nums; }
.pagination { padding: 14px 18px; margin-top: 0; border-top: 1px solid var(--line); }
.pagination-copy { font-size: 11px; color: var(--muted); }
@media(max-width:730px) {
 .run-filters { padding: 13px; gap: 7px; }
 .run-filters .el-select { width: calc((100% - 14px)/3); }
 .run-filters .el-input { flex: 1; min-width: 150px; }
 .run-filter-note { width: 100%; margin-left: 0; }
 .log-table { min-width: 0; }
 .log-table thead { display: none; }
 .log-table tbody tr { display: grid; grid-template-columns: minmax(0,1fr) auto; padding: 15px 16px; border-bottom: 1px solid var(--line); gap: 8px 10px; }
 .log-table td { padding: 0; border: 0; font-size: 11px; }
 .log-table td:nth-child(1) { grid-column: 1; grid-row: 1; }
 .log-table td:nth-child(2) { grid-column: 1; grid-row: 2; color: var(--muted); }
 .log-table td:nth-child(3) { grid-column: 2; grid-row: 1; text-align: right; }
 .log-table td:nth-child(4) { grid-column: 2; grid-row: 2; text-align: right; }
 .log-table td:nth-child(5) { grid-column: 1/-1; grid-row: 3; max-width: none; font-size: 10px; }
 .log-table td:nth-child(6) { grid-column: 1; grid-row: 4; }
 .log-table td:nth-child(7) { grid-column: 2; grid-row: 4; text-align: right; }
 .cell-sub { font-size: 9px; }
 .cell-time { font-size: 9px !important; }
 .pagination { padding: 12px 13px; }
 .pagination :deep(.el-pagination) { flex-wrap: wrap; justify-content: flex-end; max-width: 100%; }
}
</style>
