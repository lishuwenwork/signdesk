<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { api, dateTime } from '../api'
import { requestPresentation } from '../requestPresentation'
import DeskIcon from './DeskIcon.vue'
import StatusChip from './StatusChip.vue'
const props = defineProps({ modelValue: Boolean, request: Object, platform: Object, account: Object })
const emit = defineEmits(['update:modelValue', 'edit', 'show-run'])
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const revision = ref(null), loading = ref(false), error = ref(''), tab = ref('request')
let session = 0, disposed = false
function clear() { session++; revision.value = null; loading.value = false; error.value = ''; tab.value = 'request' }
async function load() {
  const current = session, id = props.request?.id
  if (!id || !visible.value) return
  loading.value = true
  try {
    const data = await api(`/requests/${id}/revision`)
    if (!disposed && visible.value && current === session) revision.value = data
  } catch (e) { if (!disposed && current === session) error.value = e.message }
  finally { if (!disposed && current === session) loading.value = false }
}
watch(() => [props.modelValue, props.request?.id], ([open]) => { clear(); if (open) load() }, { immediate: true, flush: 'sync' })
onUnmounted(() => { disposed = true; clear() })
const state = computed(() => props.request ? requestPresentation(props.request) : null)
const body = computed(() => {
  if (!revision.value?.spec.bodyBytes) return '无请求体'
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(Uint8Array.from(atob(revision.value.spec.bodyBytes), c => c.charCodeAt(0)))
  } catch { return `Base64 原始字节\n${revision.value.spec.bodyBytes}` }
})
const ruleNames = { success: '成功', alreadyDone: '已完成', expired: '凭证过期', failed: '失败' }
</script>
<template>
  <el-drawer v-model="visible" :title="request?.name || '请求详情'" size="min(570px, 100vw)" destroy-on-close>
    <template v-if="request"><p class="detail-context">{{ platform?.name }} / {{ account?.alias }} · 版本 {{ request.currentRevision }}</p><div class="detail-tabs"><button :class="{ active: tab === 'request' }" @click="tab = 'request'">完整请求</button><button :class="{ active: tab === 'rules' }" @click="tab = 'rules'">结果规则</button><button :class="{ active: tab === 'status' }" @click="tab = 'status'">状态与最近执行</button></div>
      <div v-if="tab === 'request'"><el-alert title="这里包含完整请求和凭证，关闭抽屉后将从页面状态清除。请求在服务器明文保存。" type="warning" :closable="false" /><p v-if="loading" class="muted space-top" role="status">正在读取完整请求…</p><el-alert v-if="error" :title="error" type="error" :closable="false" class="space-top" /><el-button v-if="error" @click="load">重新读取</el-button><template v-if="revision"><dl class="preview-grid"><dt>方法</dt><dd class="method">{{ revision.spec.method }}</dd><dt>URL</dt><dd class="mono">{{ revision.spec.rawUrl }}</dd><dt>超时</dt><dd>{{ revision.spec.timeoutMillis ? `${revision.spec.timeoutMillis / 1000} 秒，另受全局上限限制` : '使用全局超时' }}</dd><dt>重定向</dt><dd>{{ revision.spec.followRedirects ? '同源最多 5 跳，跨源不转发凭证' : '不自动跟随' }}</dd></dl><h3 class="detail-section-title">原始 cURL</h3><pre class="preview-code">{{ revision.rawCurl }}</pre><h3 class="detail-section-title">请求头（{{ revision.spec.headers.length }}）</h3><pre class="preview-code">{{ revision.spec.headers.map(h => `${h.name}: ${h.value}`).join('\n') || '无自定义请求头' }}</pre><h3 class="detail-section-title">请求体</h3><pre class="preview-code">{{ body }}</pre></template></div>
      <div v-else-if="tab === 'rules'"><p class="muted small">HTTP 200 不自动等于成功。JSON 判断保留值类型，不执行脚本。</p><div v-for="(rule, key) in request.rules" :key="key" class="rule-block"><h3>{{ ruleNames[key] || key }}</h3><pre class="preview-code">{{ typeof rule === 'object' ? JSON.stringify(rule, null, 2) : rule }}</pre></div><el-button class="space-top" @click="emit('edit', 'rules')">编辑名称 / 规则</el-button></div>
      <div v-else><span class="state-text"><DeskIcon :name="state.icon" />{{ state.label }}</span><dl class="preview-grid"><dt>自身启用</dt><dd>{{ request.enabled ? '已启用' : '已禁用' }}</dd><dt>凭证暂停</dt><dd>{{ request.authPaused ? '已暂停，更新 cURL 后解除' : '未暂停' }}</dd><dt>今日日标记</dt><dd>{{ { none:'无标记',completed:'已完成',pending:'待确认' }[request.todayState] || '无标记' }}</dd></dl><p class="muted small">日标记与历史执行独立，清理记录不改变今日状态。更新凭证不会清除已经完成或待确认的标记。</p><h3 class="detail-section-title">最近执行</h3><template v-if="request.lastRun"><StatusChip :status="request.lastRun.status" /><p class="muted small space-top">{{ dateTime(request.lastRun.finishedAt || request.lastRun.createdAt) }} · {{ request.lastRun.httpStatus ?? '—' }} · {{ request.lastRun.durationMs ?? '—' }} ms</p><el-button class="space-top" @click="emit('show-run', request.lastRun.id)">查看执行详情</el-button></template><p v-else class="muted space-top">暂无执行记录；这不代表没有日标记。</p></div>
    </template>
    <template #footer><div class="drawer-actions"><el-button @click="visible = false">关闭</el-button><el-button type="primary" @click="emit('edit', 'update')">更新 cURL</el-button></div></template>
  </el-drawer>
</template>
<style scoped>
.detail-context { color: var(--muted); font-size: 11px; margin-bottom: 18px; overflow-wrap: anywhere; }
.state-text { display: flex; align-items: center; gap: 6px; color: var(--forest); }
.rule-block { border: 1px solid var(--line); border-radius: 8px; padding: 13px 15px; margin-top: 12px; }
.rule-block .preview-code { margin-bottom: 0; }
.drawer-actions { display: flex; justify-content: flex-end; gap: 9px; }
</style>
