<script setup>
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import DeskIcon from './DeskIcon.vue'
import { ElMessage } from 'element-plus'
import { api, report, confirm } from '../api'
import { createRuleDraft, collectRules } from '../resultRules'
import ResultRulesEditor from './ResultRulesEditor.vue'
import TemplateApplyPanel from './TemplateApplyPanel.vue'

const props = defineProps({ modelValue: Boolean, platformId: String, seed: Object })
const emit = defineEmits(['update:modelValue', 'saved'])
const visible = computed({ get: () => props.modelValue, set: (v) => emit('update:modelValue', v) })
/** @type {import('vue').Ref<import('element-plus').DialogInstance | null>} */
const dialogRef = ref(null)
const templates = ref([]),
  editing = ref(false),
  loading = ref(false),
  saving = ref(false),
  error = ref(''),
  rules = ref(createRuleDraft())
const form = reactive({ id: '', name: '', version: 1 })
const application = reactive({ visible: false, templateId: '', mode: 'selected', busy: false })
const title = computed(() => application.visible
  ? (application.mode === 'linked' ? '应用到已关联请求' : '选择请求并应用模板')
  : editing.value ? (form.id ? '编辑接口模板' : '新增接口模板') : '接口模板')
function startApplication(template, mode) { Object.assign(application, { visible: true, templateId: template.id, mode, busy: false }) }
function cancelApplication() { if (!application.busy) application.visible = false }
/** @param {() => void} done @returns {void} */
function beforeClose(done) {
  if (application.busy) return
  if (application.visible) { cancelApplication(); return }
  done()
}
function applied() { application.visible = false; application.busy = false; emit('saved'); load() }
let session = 0, loadSequence = 0
onUnmounted(() => { session++; loadSequence++; templates.value = []; rules.value = createRuleDraft(); form.name = '' })
function edit(template = null) {
  Object.assign(form, { id: template?.id || '', name: template?.name || '', version: template?.version || 1 })
  rules.value = createRuleDraft(template?.rules)
  editing.value = true
}
async function load() {
  const current = session, sequence = ++loadSequence, platformId = props.platformId
  loading.value = true
  error.value = ''
  try {
    const data = await api(`/platforms/${platformId}/templates`)
    if (current === session && sequence === loadSequence) templates.value = data
  } catch (e) {
    if (current === session && sequence === loadSequence) error.value = e.message
  } finally {
    if (current === session && sequence === loadSequence) loading.value = false
  }
}
watch(
  () => [props.modelValue, props.platformId, props.seed],
  ([open]) => {
    session++
    application.visible = false
    application.busy = false
    templates.value = []
    editing.value = false
    saving.value = false
    error.value = ''
    form.name = ''
    rules.value = createRuleDraft()
    if (!open) return
    if (props.seed) edit(props.seed)
    load()
  },
  { flush: 'sync' },
)
async function save() {
  if (saving.value) return
  if (!form.name.trim()) return ElMessage.warning('请填写模板名称')
  const current = session, platformId = props.platformId
  saving.value = true
  try {
    await api(
      `/platforms/${platformId}/templates${form.id ? `/${form.id}` : ''}`,
      form.id ? 'PUT' : 'POST',
      { name: form.name.trim(), rules: collectRules(rules.value), version: form.version },
    )
    if (current !== session) return
    ElMessage.success('接口模板已保存')
    editing.value = false
    emit('saved')
    await load()
  } catch (e) {
    if (current === session) {
      error.value = e.status === 409 ? '模板版本冲突。草稿已保留，不会覆盖其他页面的修改；请返回列表重新读取最新模板。' : e.message
      report(e)
    }
  } finally {
    if (current === session) saving.value = false
  }
}
async function remove(template) {
  const current = session, platformId = props.platformId
  if (!(await confirm(`删除接口模板「${template.name}」？请求的现有规则、凭证和执行记录保留，模板关联将解除。`))) return
  if (current !== session) return
  saving.value = true
  try {
    await api(`/platforms/${platformId}/templates/${template.id}`, 'DELETE', {})
    if (current !== session) return
    ElMessage.success('接口模板已删除')
    emit('saved')
    await load()
  } catch (e) {
    if (current === session) {
      error.value = e.status === 409 ? '模板版本冲突。草稿已保留，不会覆盖其他页面的修改；请返回列表重新读取最新模板。' : e.message
      report(e)
    }
  } finally {
    if (current === session) saving.value = false
  }
}
</script>
<template>
  <el-dialog ref="dialogRef" v-model="visible" :title="title" class="template-manager-dialog" :class="{ 'is-application': application.visible }"
    width="730px" destroy-on-close :close-on-click-modal="false" :close-on-press-escape="!application.busy" :show-close="!application.busy" :before-close="beforeClose">
    <TemplateApplyPanel v-if="application.visible" :platform-id="platformId" :template-id="application.templateId" :mode="application.mode"
      @cancel="cancelApplication" @applied="applied" @busy-change="application.busy = $event" />
    <template v-else>
    <el-alert title="模板只保存名称与结果规则，不含 cURL。修改模板不会自动同步；可选择请求应用并建立关联，或一键更新已关联请求。"
      type="info" :closable="false" class="space-top" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="space-top" />
    <div v-if="editing" v-loading="saving" class="space-top">
      <el-form label-position="top" :disabled="saving">
        <el-form-item label="模板名称">
          <el-input v-model="form.name" maxlength="60" placeholder="例如：每日签到、领取奖励、查询积分" />
        </el-form-item>
      </el-form>
      <ResultRulesEditor v-model="rules" :disabled="saving" />
    </div>
    <div v-else v-loading="loading || saving" class="space-top">
      <div class="actions">
        <el-button type="primary" @click="edit()">＋ 新增模板</el-button>
        <span class="muted">{{ templates.length }} 份接口规则，可在添加请求时选择</span>
        <el-button v-if="error" @click="load">重新加载</el-button>
      </div>
      <div v-if="templates.length" class="template-cards space-top">
        <div v-for="template in templates" :key="template.id" class="template-card" :data-template-id="template.id">
      <div class="template-card-header"><h3><DeskIcon name="template" />{{ template.name }}</h3><div class="template-actions"><el-button text size="small" :disabled="saving" @click="startApplication(template, 'selected')">选择请求应用</el-button><el-button text size="small" :disabled="saving || !template.linkedRequestCount" @click="startApplication(template, 'linked')">应用到已关联请求</el-button><el-button text size="small" @click="edit(template)">编辑</el-button><el-button text size="small" type="danger" @click="remove(template)">删除</el-button></div></div>
          <p>版本 {{ template.version }} · 已关联 {{ template.linkedRequestCount || 0 }} 个请求 · 修改模板后需明确应用</p>
        </div>
      </div>
      <el-empty v-else-if="!loading && !error" description="还没有模板。也可以从已有请求的「更多」菜单保存一份。" />
    </div>
    </template>
    <template v-if="!application.visible" #footer>
      <el-button v-if="editing" :disabled="saving" @click="editing = false">返回模板列表</el-button>
      <el-button v-else @click="dialogRef?.handleClose()">关闭</el-button>
      <el-button v-if="editing" type="primary" :loading="saving" @click="save">保存模板</el-button>
    </template>
  </el-dialog>
</template>
<style>
.el-dialog.template-manager-dialog { display: flex; flex-direction: column; --el-dialog-margin-top: 16px; margin-bottom: 16px; max-height: calc(100dvh - 32px); overflow: hidden; }
.template-manager-dialog .el-dialog__header, .template-manager-dialog .el-dialog__footer { flex-shrink: 0; }
.template-manager-dialog .el-dialog__body { min-height: 0; overflow-y: auto; overscroll-behavior: contain; }
.template-manager-dialog.is-application .el-dialog__body { display: flex; flex-direction: column; overflow: hidden; }
</style>
<style scoped>
.template-cards { display: grid; gap: 12px; }
.template-card { border: 1px solid var(--line); border-radius: 8px; padding: 15px; }
.template-card-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
.template-card-header h3 { font-size: 13px; display: flex; align-items: center; gap: 8px; margin: 0; overflow-wrap: anywhere; min-width: 0; }
.template-actions .el-button + .el-button { margin-left: 0; }
.template-card-header h3 .icon { color: var(--muted); width: 16px; height: 16px; }
.template-card p { font-size: 11px; color: var(--muted); margin-top: 7px; }
.template-actions { display: flex; gap: 6px 12px; flex-wrap: wrap; justify-content: flex-end; }
.template-actions :deep(.el-button) { font-size: 11px; padding: 5px 0; }
</style>
