<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, execute, periodic, dateTime } from '../api'
import { platformLocation } from '../requestPresentation'
import StatusChip from './StatusChip.vue'
const props = defineProps({ modelValue: Boolean, runId: String })
const emit = defineEmits(['update:modelValue'])
const router = useRouter()
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const detail = ref(null), loading = ref(false), error = ref(''), tab = ref('result')
let session = 0, disposed = false, refreshing = false
function clear() { session++; detail.value = null; error.value = ''; loading.value = false; tab.value = 'result' }
async function load(id, current) {
  loading.value = true
  error.value = ''
  try {
    const result = await api(`/runs/${id}`)
    if (!disposed && current === session && visible.value) detail.value = result
  } catch (e) {
    if (!disposed && current === session && visible.value) error.value = e.message
  } finally {
    if (!disposed && current === session) loading.value = false
  }
}
watch(() => [props.modelValue, props.runId], ([open, id]) => {
  clear()
  if (open && id) load(id, session)
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { disposed = true; clear() })
// 仅轮询批次的元数据。详情第一次进入终态后停止获取正文，不重复下载大响应。
periodic(async () => {
  if (!visible.value || refreshing || !detail.value || !['queued', 'running'].includes(detail.value.status)) return
  const current = session, id = detail.value.id, batchId = detail.value.batchId
  refreshing = true
  try {
    const batch = await api(`/batches/${batchId}`)
    if (disposed || current !== session || !visible.value || detail.value?.id !== id) return
    const item = batch.items?.find(item => item.id === id)
    if (!item) return
    if (!['queued', 'running'].includes(item.status)) await load(id, current)
    else detail.value = { ...detail.value, status: item.status }
  } catch { /* 元数据轮询失败不清空已读取详情；用户仍可手动重读。 */ }
  finally { refreshing = false }
}, 2000)
const responseMessage = computed(() => {
  if (['queued', 'running'].includes(detail.value?.status)) return '请求尚未完成，等待获取响应体。'
  if (['skipped', 'cancelled'].includes(detail.value?.status)) return '本次没有发送请求，无响应体。'
  return { not_recorded: '这条记录没有保存响应体。', unavailable: '本次请求未获取到响应体，例如在连接或发送阶段失败、超时。' }[detail.value?.response?.state] || '没有可查看的响应体。'
})
const captureLabels = { complete: '完整响应', partial: '部分响应', truncated: '已截断', unavailable: '未获取', not_recorded: '未保存' }
async function locate() {
  const id = detail.value?.requestId
  if (!id) return
  const current = session
  try {
    const platforms = await api('/platforms')
    if (current !== session || !visible.value || disposed) return
    const platform = platforms.find(p => p.accounts.some(a => a.requests.some(r => r.id === id)))
    if (!platform) { error.value = '关联请求已不存在，无法定位。'; return }
    visible.value = false
    router.push(platformLocation(platform.id, id))
  } catch (e) { if (current === session && !disposed) error.value = e.message }
}
</script>
<template>
  <el-drawer v-model="visible" title="执行记录详情" size="min(570px, 100vw)" destroy-on-close>
    <div v-if="loading" v-loading="true" style="min-height: 120px" aria-label="加载执行详情" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-button v-if="error" class="space-top" @click="load(runId, session)">重新读取</el-button>
    <template v-if="detail">
      <p class="detail-context">执行 {{ detail.id }} · 请求版本 {{ detail.requestRevision }}</p>
      <div class="detail-tabs"><button :class="{ active: tab === 'result' }" @click="tab = 'result'">执行信息</button><button :class="{ active: tab === 'response' }" @click="tab = 'response'">响应体</button></div>
      <div v-show="tab === 'result'"><StatusChip :status="detail.status" /><dl class="preview-grid">
        <dt>来源</dt><dd>{{ detail.source === 'auto' ? '平台定时' : '手动执行' }}</dd>
        <dt>业务日期</dt><dd>{{ detail.businessDate }}</dd><dt>请求版本</dt><dd>{{ detail.requestRevision }}</dd>
        <dt>HTTP</dt><dd>{{ detail.httpStatus ?? '—' }}</dd><dt>耗时</dt><dd>{{ detail.durationMs == null ? '—' : `${detail.durationMs} ms` }}</dd>
        <dt>开始（北京）</dt><dd>{{ dateTime(detail.startedAt) }}</dd><dt>结束（北京）</dt><dd>{{ dateTime(detail.finishedAt) }}</dd><dt>摘要</dt><dd>{{ detail.safeSummary || '等待执行' }}</dd>
      </dl><el-alert v-if="detail.status === 'unknown'" title="可能已在目标平台完成操作，请先核对结果，再决定是否重新执行" type="warning" :closable="false" /></div>
      <section v-show="tab === 'response'" aria-label="执行响应体">
        <h3>响应体 <span class="section-count">{{ captureLabels[detail.response?.state] || '无响应' }}</span></h3>
        <p class="muted small response-note">响应可能包含个人信息或凭证，在服务器明文保存；仅按需展示，关闭详情后清除页面内容。HTML 始终作为纯文本。</p>
        <template v-if="detail.response?.body != null">
          <div class="muted small">{{ detail.response.contentType || '未提供 Content-Type' }} · {{ detail.response.byteLength }} 字节<template v-if="detail.response.encoding === 'text'"> · {{ detail.response.charset }}</template></div>
          <el-alert v-if="detail.response.state === 'truncated'" class="space-top" type="warning" :closable="false" title="响应超过 1 MiB，仅保存前 1 MiB；以下内容已截断，执行结果仍为待确认。" />
          <el-alert v-else-if="detail.response.state === 'partial'" class="space-top" type="warning" :closable="false" title="响应读取中断，以下仅是实际已收到的内容，并非完整响应。" />
          <el-alert v-if="detail.response.encoding === 'base64'" class="space-top" type="info" :closable="false" title="二进制内容或无法按字符集解码，以下以 Base64 原样展示。" />
          <el-empty v-if="detail.response.byteLength === 0" description="响应体为空（0 字节）" :image-size="50" />
          <pre v-else class="preview-code response-body" data-testid="response-body">{{ detail.response.body }}</pre>
        </template><el-alert v-else :title="responseMessage" class="space-top" :closable="false" type="info" />
      </section>
    </template>
    <template #footer><div class="drawer-actions"><el-button @click="visible = false">关闭</el-button><el-button :disabled="!detail" @click="locate">定位请求</el-button><el-button type="primary" :disabled="!detail || ['queued','running','expired'].includes(detail.status)" @click="execute('request', detail.requestId, true)">重新执行</el-button></div></template>
  </el-drawer>
</template>
<style scoped>
.detail-context { font: 10px/1.8 var(--mono); color: var(--muted); margin-bottom: 18px; overflow-wrap: anywhere; }
.response-note { margin: 9px 0 13px; }
.response-body { max-height: 52vh; }
.drawer-actions { display: flex; gap: 8px; justify-content: flex-end; flex-wrap: wrap; }
</style>
