<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, periodic, confirm, dateTime } from '../api'
import StatusChip from './StatusChip.vue'
const props = defineProps({ modelValue: Boolean, batchId: String })
const emit = defineEmits(['update:modelValue', 'show-run', 'cancelled'])
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const batch = ref(null), loading = ref(false), error = ref(''), cancelling = ref(false)
let session = 0, sequence = 0, disposed = false
async function load() {
  if (!visible.value || !props.batchId) return
  const current = session, request = ++sequence, id = props.batchId
  loading.value = !batch.value
  try {
    const data = await api(`/batches/${id}`)
    if (!disposed && current === session && request === sequence && visible.value) { batch.value = data; error.value = '' }
  } catch (e) { if (!disposed && current === session && request === sequence) error.value = e.message }
  finally { if (!disposed && current === session && request === sequence) loading.value = false }
}
watch(() => [props.modelValue, props.batchId], ([open]) => { session++; sequence++; batch.value = null; error.value = ''; loading.value = false; cancelling.value = false; if (open) load() }, { immediate: true, flush: 'sync' })
periodic(async () => { if (batch.value && ['queued','running'].includes(batch.value.status)) await load() }, 2000)
onUnmounted(() => { disposed = true; session++; sequence++; batch.value = null })
const done = computed(() => batch.value?.items.filter(item => !['queued','running'].includes(item.status)).length || 0)
async function cancel() {
  const id = props.batchId, current = session
  if (!(await confirm('取消此批次尚未发送的剩余请求？已发送请求可能保留为待确认，不会自动重试。')) || current !== session) return
  cancelling.value = true
  try { await api(`/batches/${id}/cancel`, 'POST', {}); if (current !== session || disposed) return; await load(); emit('cancelled') }
  catch(e) { if (current === session && !disposed) error.value = e.message }
  finally { if (current === session) cancelling.value = false }
}
</script>
<template>
  <el-drawer v-model="visible" title="执行批次详情" size="min(570px, 100vw)" destroy-on-close>
    <div v-if="loading" v-loading="true" style="min-height:120px" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-button v-if="error" class="space-top" @click="load">重新读取</el-button>
    <template v-if="batch"><p class="muted small">批次 {{ batch.id }}</p><dl class="preview-grid"><dt>来源</dt><dd>{{ batch.source === 'auto' ? '平台定时' : '手动执行' }}</dd><dt>状态</dt><dd>{{ batch.status === 'running' ? '执行中' : batch.status === 'queued' ? '排队中' : batch.status === 'cancelled' ? '已取消' : '已结束' }}{{ batch.cancelRequested ? ' · 已请求取消' : '' }}</dd><dt>业务日期</dt><dd>{{ batch.businessDate }} · {{ batch.timezone }}</dd><dt>创建（北京）</dt><dd>{{ dateTime(batch.createdAt) }}</dd><dt>计划时间</dt><dd>{{ dateTime(batch.scheduledAt) }}</dd><dt>冻结间隔</dt><dd>{{ batch.intervalSeconds }} 秒</dd><dt>进度</dt><dd>{{ done }} / {{ batch.items.length }}</dd></dl><p class="muted small">创建时已冻结请求修订、规则和计划参数；浏览器关闭不影响持久队列。</p><h3 class="detail-section-title">批次请求</h3><button v-for="item in batch.items" :key="item.id" class="batch-item" @click="emit('show-run', item.id)"><span><strong>{{ item.requestName || '请求' }}</strong><small>{{ item.alias }} · 版本 {{ item.requestRevision }} · {{ item.safeSummary || '等待执行' }}</small></span><StatusChip :status="item.status" /></button></template>
    <template #footer><div class="drawer-actions"><el-button @click="visible = false">关闭</el-button><el-button :loading="cancelling" :disabled="!batch || !['queued','running'].includes(batch.status) || !!batch.cancelRequested" @click="cancel">取消剩余</el-button></div></template>
  </el-drawer>
</template>
<style scoped>
.batch-item { width: 100%; display: grid; grid-template-columns: minmax(0,1fr) auto; gap: 14px; align-items: center; padding: 13px 0; border: 0; border-bottom: 1px solid var(--line); text-align: left; background: transparent; color: var(--ink); }
.batch-item strong { font-size: 12px; font-weight: 500; }
.batch-item small { display: block; font-size: 10px; color: var(--muted); margin-top: 4px; overflow-wrap: anywhere; }
.drawer-actions { display: flex; gap: 9px; justify-content: flex-end; }
</style>
