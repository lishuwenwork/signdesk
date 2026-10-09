<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, report, confirm } from '../api'
import { createRuleDraft, collectRules } from '../resultRules'
import ResultRulesEditor from './ResultRulesEditor.vue'

const props = defineProps({
  modelValue: Boolean,
  platformId: String,
  accountId: String,
  request: Object,
  mode: { type: String, default: 'new' },
})
const emit = defineEmits(['update:modelValue', 'saved'])
const visible = computed({ get: () => props.modelValue, set: (v) => emit('update:modelValue', v) })
const form = reactive({ name: '每日签到', curl: '', enabled: true })
const preview = ref(null),
  step = ref(0),
  busy = ref(false),
  rules = ref(createRuleDraft()),
  templates = ref([]),
  selectedTemplate = ref(''),
  templatesLoading = ref(false),
  templatesError = ref('')
let baseline = '', session = 0
function signature() {
  return JSON.stringify({ name: form.name, rules: rules.value })
}
watch(
  () => [props.modelValue, props.platformId, props.accountId, props.mode],
  async ([open]) => {
    const current = ++session
    form.curl = ''
    preview.value = null
    busy.value = false
    selectedTemplate.value = ''
    templates.value = []
    templatesLoading.value = false
    templatesError.value = ''
    rules.value = createRuleDraft(props.request?.rules)
    if (!open) return
    step.value = props.mode === 'rules' ? 2 : 0
    form.name = props.request?.name || '每日签到'
    form.enabled = props.request ? !!props.request.enabled : true
    baseline = signature()
    if (props.mode === 'new' && props.platformId) {
      templatesLoading.value = true
      try {
        const data = await api(`/platforms/${props.platformId}/templates`)
        if (current === session) templates.value = data
      } catch (e) {
        if (current === session) templatesError.value = e.message
      } finally {
        if (current === session) templatesLoading.value = false
      }
    }
    if (props.mode === 'view') {
      busy.value = true
      try {
        const data = await api(`/requests/${props.request.id}/revision`)
        if (current !== session) return
        form.curl = data.rawCurl
        preview.value = { spec: data.spec, warnings: [] }
        step.value = 1
      } catch (e) {
        if (current === session) {
          report(e)
          visible.value = false
        }
      } finally {
        if (current === session) busy.value = false
      }
    }
  },
)
async function applyTemplate(id) {
  if (!id) {
    selectedTemplate.value = ''
    return
  }
  const template = templates.value.find((t) => t.id === id)
  if (!template || id === selectedTemplate.value) return
  const current = session
  if (signature() !== baseline && !(await confirm('应用模板将覆盖手工填写的名称和结果规则，但不会修改 cURL。是否继续？'))) return
  if (current !== session) return
  form.name = template.name
  rules.value = createRuleDraft(template.rules)
  selectedTemplate.value = id
  baseline = signature()
}
const title = computed(
  () => ({ new: '导入完整 cURL', update: '更新 cURL', rules: '编辑请求与结果规则', view: '查看完整请求' })[props.mode],
)
async function parse() {
  const current = session
  busy.value = true
  try {
    const data = await api('/requests/preview', 'POST', { curl: form.curl })
    if (current === session) {
      preview.value = data
      step.value = 1
    }
  } catch (e) {
    if (current === session) report(e)
  } finally {
    if (current === session) busy.value = false
  }
}
async function save() {
  const current = session
  busy.value = true
  try {
    if (props.mode === 'update')
      await api(`/requests/${props.request.id}/revisions`, 'POST', {
        curl: form.curl,
        version: props.request.version,
      })
    else if (props.mode === 'rules')
      await api(`/requests/${props.request.id}`, 'PUT', {
        name: form.name,
        enabled: form.enabled,
        rules: collectRules(rules.value),
        version: props.request.version,
      })
    else
      await api(`/accounts/${props.accountId}/requests`, 'POST', {
        name: form.name,
        curl: form.curl,
        enabled: form.enabled,
        rules: collectRules(rules.value),
      })
    if (current !== session) return
    ElMessage.success(props.mode === 'update' ? '请求版本已更新，凭证暂停已解除' : '请求已保存')
    emit('saved')
    visible.value = false
  } catch (e) {
    if (current === session) report(e)
  } finally {
    if (current === session) busy.value = false
  }
}
const bodyText = computed(() => {
  try {
    return new TextDecoder().decode(
      Uint8Array.from(atob(preview.value?.spec.bodyBytes || ''), (c) => c.charCodeAt(0)),
    )
  } catch {
    return ''
  }
})
</script>
<template>
  <el-dialog v-model="visible" :title="title" width="730px" destroy-on-close :close-on-click-modal="false">
    <el-steps v-if="mode === 'new'" :active="step" simple class="space-top" style="margin-bottom: 25px">
      <el-step title="粘贴 cURL" /><el-step title="解析预览" /><el-step title="结果规则" />
    </el-steps>
    <div v-loading="busy">
      <template v-if="step === 0">
        <el-form label-position="top">
          <el-form-item v-if="mode === 'new'" label="接口模板（可选）">
            <el-select :model-value="selectedTemplate" @change="applyTemplate" :loading="templatesLoading"
              :disabled="templatesLoading || !templates.length" placeholder="不使用模板，手动填写" aria-label="接口模板（可选）" style="width: 100%">
              <el-option label="不使用模板，保留当前填写内容" value="" />
              <el-option v-for="template in templates" :key="template.id" :label="template.name" :value="template.id" />
            </el-select>
            <div class="muted space-top">仅带入名称和结果规则；请粘贴当前账号自己的完整 cURL。</div>
          </el-form-item>
          <el-alert v-if="mode === 'new' && templatesError" :title="`接口模板暂时无法加载：${templatesError}；仍可手动填写`"
            type="warning" :closable="false" class="space-top" />
          <el-form-item v-if="mode === 'new'" label="请求名称">
            <el-input v-model="form.name" maxlength="60" />
          </el-form-item>
          <el-form-item label="完整 cURL">
            <el-input v-model="form.curl" type="textarea" :rows="11"
              placeholder="curl 'https://example.com/api/checkin' -H 'Cookie: ...' --data-raw '{...}'" class="mono" />
          </el-form-item>
        </el-form>
        <div class="muted">支持 Bash / Windows CMD 常见格式。请求及凭证在服务器明文保存，请限制数据目录访问；不存入浏览器本地存储。</div>
        <div class="dialog-footer">
          <el-button @click="visible = false">取消</el-button>
          <el-button type="primary" :loading="busy" @click="parse">解析预览</el-button>
        </div>
      </template>
      <template v-if="step === 1 && preview">
        <el-alert v-if="mode === 'view'" title="这里包含完整请求和凭证，关闭窗口后将从页面状态清除" type="warning" :closable="false" />
        <dl class="preview-grid">
          <dt>方法</dt><dd class="method">{{ preview.spec.method }}</dd>
          <dt>URL</dt><dd class="mono">{{ preview.spec.rawUrl }}</dd>
          <dt>超时</dt><dd>{{ preview.spec.timeoutMillis ? `${preview.spec.timeoutMillis / 1000} 秒，另受全局超时上限限制` : '使用全局超时设置' }}</dd>
          <dt>重定向</dt><dd>{{ preview.spec.followRedirects ? '同来源最多 5 跳；跨来源不会转发凭证' : '不自动跟随' }}</dd>
        </dl>
        <h3>请求头（{{ preview.spec.headers.length }}）</h3>
        <pre class="preview-code">{{ preview.spec.headers.map((h) => `${h.name}: ${h.value}`).join('\n') || '无自定义请求头' }}</pre>
        <h3>请求体</h3>
        <pre class="preview-code">{{ bodyText || '无请求体' }}</pre>
        <el-alert v-for="warning in preview.warnings" :key="warning" :title="warning" type="warning" :closable="false" class="space-top" />
        <div class="dialog-footer">
          <el-button v-if="mode !== 'view'" @click="step = 0">返回修改</el-button>
          <el-button v-if="mode === 'new'" type="primary" @click="step = 2">设置结果规则</el-button>
          <el-button v-else-if="mode === 'update'" type="primary" @click="save" :loading="busy">保存新版本</el-button>
          <el-button v-else @click="visible = false">关闭</el-button>
        </div>
      </template>
      <template v-if="step === 2">
        <el-form label-position="top">
          <el-form-item label="请求名称"><el-input v-model="form.name" maxlength="60" /></el-form-item>
        </el-form>
        <ResultRulesEditor v-model="rules" />
        <div class="dialog-footer">
          <el-button v-if="mode === 'new'" @click="step = 1">上一步</el-button>
          <el-button v-else @click="visible = false">取消</el-button>
          <el-button type="primary" @click="save" :loading="busy">保存请求</el-button>
        </div>
      </template>
    </div>
  </el-dialog>
</template>
