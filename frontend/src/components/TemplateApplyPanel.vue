<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { filterTemplateTargets, applicationTargets } from '../templateApplication'

const props = defineProps({ platformId: String, templateId: String, mode: { type: String, default: 'selected' } })
const emit = defineEmits(['cancel', 'applied', 'busy-change'])
const preview = ref(null), loading = ref(false), applying = ref(false), error = ref(''), keyword = ref('')
/** @type {import('vue').Ref<string[]>} */
const selectedIds = ref([])
const filtered = computed(() => filterTemplateTargets(preview.value?.requests || [], keyword.value))
const selectedRows = computed(() => (preview.value?.requests || []).filter(row => selectedIds.value.includes(row.id)))
const reassigned = computed(() => selectedRows.value.filter(row => row.templateId && row.templateId !== props.templateId).length)
let session = 0, sequence = 0, disposed = false
function clear() { session++; sequence++; preview.value = null; selectedIds.value = []; keyword.value = ''; error.value = ''; loading.value = false; applying.value = false }
async function load() {
  if (!props.platformId || !props.templateId) return
  const current = session, request = ++sequence
  const platformId = props.platformId, templateId = props.templateId, mode = props.mode
  loading.value = true; error.value = ''
  try {
    const data = await api(`/platforms/${platformId}/templates/${templateId}/requests?mode=${mode}`)
    if (disposed || current !== session || request !== sequence) return
    preview.value = data
    const available = new Set(data.requests.map(row => row.id))
    selectedIds.value = mode === 'linked' ? [...available] : selectedIds.value.filter(id => available.has(id))
  } catch (e) { if (!disposed && current === session && request === sequence) error.value = e.message }
  finally { if (!disposed && current === session && request === sequence) loading.value = false }
}
watch(() => [props.platformId, props.templateId, props.mode], () => { clear(); load() }, { immediate: true, flush: 'sync' })
watch(applying, busy => emit('busy-change', busy), { flush: 'sync' })
onUnmounted(() => { disposed = true; clear() })
function select(id, checked) { selectedIds.value = checked ? [...new Set([...selectedIds.value, id])] : selectedIds.value.filter(value => value !== id) }
function selectFiltered(checked) {
  const ids = filtered.value.map(row => row.id)
  selectedIds.value = checked ? [...new Set([...selectedIds.value, ...ids])] : selectedIds.value.filter(id => !ids.includes(id))
}
async function apply() {
  if (!preview.value || applying.value || loading.value) return
  const current = session, platformId = props.platformId, templateId = props.templateId
  let targets
  try { targets = applicationTargets(preview.value.requests, selectedIds.value) }
  catch (e) { error.value = e.message; return }
  const body = { mode: props.mode, templateVersion: preview.value.templateVersion, targets }
  applying.value = true; error.value = ''
  try {
    const result = await api(`/platforms/${platformId}/templates/${templateId}/apply`, 'POST', body)
    if (disposed || current !== session) return
    ElMessage.success(`已更新 ${result.applied} 个请求的规则，新增/改绑 ${result.newlyLinked} 个模板关联`)
    emit('applied')
  } catch (e) {
    if (!disposed && current === session) error.value = e.status === 409
      ? '模板、请求或关联范围已变化，本次没有覆盖任何请求。请重新加载范围后再次确认。' : e.message
  } finally { if (!disposed && current === session) applying.value = false }
}
</script>
<template>
  <div class="apply-panel">
    <div class="apply-panel-body">
      <el-alert title="仅覆盖结果规则，不修改请求名称、cURL、启停、凭证暂停和日标记；已排队的执行继续使用原规则，不会发送请求。" type="warning" :closable="false" />
      <el-alert v-if="error" :title="error" type="error" :closable="false" class="space-top" />
      <div v-loading="loading" class="space-top">
        <template v-if="preview">
          <p class="apply-context">{{ preview.templateName }} · 已保存版本 {{ preview.templateVersion }} · {{ mode === 'linked' ? '全部已关联请求' : '手动选择并建立关联' }}</p>
          <p v-if="mode === 'selected'" class="muted small">以前仅复制过模板规则的请求需要首次选择关联；以后可使用“应用到已关联请求”。手动修改请求规则不会自动解除关联，下次应用将覆盖这些修改。</p>
          <details class="space-top"><summary class="muted small">查看将应用的结果规则</summary><pre class="preview-code space-top">{{ JSON.stringify(preview.rules, null, 2) }}</pre></details>
          <div class="apply-toolbar"><el-input v-model="keyword" :disabled="applying" placeholder="筛选账号或请求名称" clearable aria-label="筛选模板应用请求" /><span class="muted small">已选 {{ selectedIds.length }} / {{ preview.requests.length }}</span></div>
          <div v-if="mode === 'selected' && filtered.length" class="actions apply-select"><el-button text size="small" :disabled="applying" @click="selectFiltered(true)">全选筛选结果</el-button><el-button text size="small" :disabled="applying" @click="selectFiltered(false)">取消筛选结果选择</el-button></div>
          <div v-if="filtered.length" class="apply-targets">
            <div v-for="row in filtered" :key="row.id" class="apply-target" :data-request-id="row.id">
              <el-checkbox v-if="mode === 'selected'" :model-value="selectedIds.includes(row.id)" :disabled="applying" :aria-label="`${row.accountAlias} / ${row.name}`" @update:model-value="value => select(row.id, value)" />
              <div class="apply-target-copy"><strong>{{ row.name }}</strong><p>{{ row.accountAlias }} · {{ row.enabled ? '已启用' : '已禁用，应用不会启用' }}</p><p>{{ row.templateId ? `关联模板：${row.templateName || row.templateId}` : '未关联模板' }}</p></div>
            </div>
          </div>
          <el-empty v-else :description="keyword ? '没有匹配的请求' : mode === 'linked' ? '还没有关联请求，请先使用手动选择应用' : '当前平台没有可应用的请求'" />
          <p v-if="reassigned" class="muted small space-top">本次将把 {{ reassigned }} 个请求从其他模板改关联到此模板。</p>
          <p v-if="selectedIds.length" class="muted small space-top">点击下方“{{ mode === 'linked' ? '确认更新已关联请求' : '应用并关联所选请求' }}”，即确认覆盖以上 {{ selectedIds.length }} 个请求的现有规则并建立/保留模板关联；取消不会保存。</p>
        </template>
      </div>
    </div>
    <div class="apply-footer"><el-button :disabled="applying || loading" @click="load">重新加载范围</el-button><div><el-button :disabled="applying" @click="emit('cancel')">取消</el-button><el-button type="primary" :loading="applying" :disabled="applying || loading || !preview || !selectedIds.length" @click="apply">{{ mode === 'linked' ? '确认更新已关联请求' : '应用并关联所选请求' }}</el-button></div></div>
  </div>
</template>
<style scoped>
.apply-panel { display: flex; flex-direction: column; flex: 1; min-height: 0; }
.apply-panel-body { flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior: contain; }
.apply-context { font-size: 13px; font-weight: 500; margin: 16px 0 8px; overflow-wrap: anywhere; }
.apply-toolbar { display: flex; align-items: center; gap: 14px; margin-top: 16px; }
.apply-toolbar .el-input { flex: 1; }
.apply-toolbar span { white-space: nowrap; }
.apply-select { margin: 8px 0; }
.apply-targets { max-height: 340px; overflow-y: auto; overscroll-behavior: contain; border: 1px solid var(--line); border-radius: 7px; }
.apply-toolbar + .apply-targets { margin-top: 12px; }
.apply-target { display: flex; align-items: flex-start; gap: 12px; padding: 12px 14px; border-bottom: 1px solid var(--line); }
.apply-target:last-child { border-bottom: 0; }
.apply-target-copy { min-width: 0; }
.apply-target strong { font-size: 12px; font-weight: 500; overflow-wrap: anywhere; }
.apply-target p { font-size: 11px; color: var(--muted); margin: 4px 0 0; overflow-wrap: anywhere; }
.apply-footer { display: flex; flex-shrink: 0; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--line); }
.apply-footer > div { display: flex; gap: 8px; flex-wrap: wrap; }
.apply-footer .el-button + .el-button { margin-left: 0; }
@media(max-width:730px) { .apply-toolbar { gap: 8px; } .apply-footer { justify-content: flex-end; } .apply-target { padding: 10px; } }
</style>
