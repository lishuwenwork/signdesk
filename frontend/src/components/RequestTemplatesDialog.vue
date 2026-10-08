<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, report, confirm } from '../api'
import { createRuleDraft, collectRules } from '../resultRules'
import ResultRulesEditor from './ResultRulesEditor.vue'

const props = defineProps({ modelValue: Boolean, platformId: String, seed: Object })
const emit = defineEmits(['update:modelValue', 'saved'])
const visible = computed({ get: () => props.modelValue, set: (v) => emit('update:modelValue', v) })
const templates = ref([]),
  editing = ref(false),
  loading = ref(false),
  saving = ref(false),
  error = ref(''),
  rules = ref(createRuleDraft())
const form = reactive({ id: '', name: '', version: 1 })
let session = 0
function edit(template = null) {
  Object.assign(form, { id: template?.id || '', name: template?.name || '', version: template?.version || 1 })
  rules.value = createRuleDraft(template?.rules)
  editing.value = true
}
async function load() {
  const current = session, platformId = props.platformId
  loading.value = true
  error.value = ''
  try {
    const data = await api(`/platforms/${platformId}/templates`)
    if (current === session) templates.value = data
  } catch (e) {
    if (current === session) error.value = e.message
  } finally {
    if (current === session) loading.value = false
  }
}
watch(
  () => [props.modelValue, props.platformId],
  ([open]) => {
    session++
    templates.value = []
    editing.value = false
    saving.value = false
    error.value = ''
    if (!open) return
    if (props.seed) edit(props.seed)
    load()
  },
)
async function save() {
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
    if (current === session) report(e)
  } finally {
    if (current === session) saving.value = false
  }
}
async function remove(template) {
  const current = session, platformId = props.platformId
  if (!(await confirm(`删除接口模板「${template.name}」？已有请求和执行记录不会受到影响。`))) return
  if (current !== session) return
  saving.value = true
  try {
    await api(`/platforms/${platformId}/templates/${template.id}`, 'DELETE', {})
    if (current !== session) return
    ElMessage.success('接口模板已删除')
    emit('saved')
    await load()
  } catch (e) {
    if (current === session) report(e)
  } finally {
    if (current === session) saving.value = false
  }
}
</script>
<template>
  <el-dialog v-model="visible" :title="editing ? (form.id ? '编辑接口模板' : '新增接口模板') : '接口模板'"
    width="730px" destroy-on-close :close-on-click-modal="false">
    <el-alert title="只复用名称和结果规则，不保存 cURL 或账号凭证；修改模板不会影响已有请求。"
      type="info" :closable="false" class="space-top" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="space-top" />
    <div v-if="editing" v-loading="saving" class="space-top">
      <el-form label-position="top">
        <el-form-item label="模板名称">
          <el-input v-model="form.name" maxlength="60" placeholder="例如：每日签到、领取奖励、查询积分" />
        </el-form-item>
      </el-form>
      <ResultRulesEditor v-model="rules" />
    </div>
    <div v-else v-loading="loading || saving" class="space-top">
      <div class="actions">
        <el-button type="primary" @click="edit()">＋ 新增模板</el-button>
        <span class="muted">{{ templates.length }} 份接口规则，可在添加请求时选择</span>
        <el-button v-if="error" @click="load">重新加载</el-button>
      </div>
      <el-table v-if="templates.length" :data="templates" class="space-top">
        <el-table-column prop="name" label="接口模板" min-width="160" />
        <el-table-column label="操作" width="145">
          <template #default="{ row }">
            <el-button size="small" @click="edit(row)">编辑</el-button>
            <el-button size="small" text type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else-if="!loading && !error" description="还没有模板。也可以从已有请求的「更多」菜单保存一份。" />
    </div>
    <template #footer>
      <el-button v-if="editing" :disabled="saving" @click="editing = false">返回模板列表</el-button>
      <el-button v-else @click="visible = false">关闭</el-button>
      <el-button v-if="editing" type="primary" :loading="saving" @click="save">保存模板</el-button>
    </template>
  </el-dialog>
</template>
