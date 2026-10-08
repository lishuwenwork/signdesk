<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, report } from '../api'
const props = defineProps({
  modelValue: Boolean,
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
  ruleHttp = ref(200),
  ruleBody = ref('{"code":0}'),
  trial = ref('')
const kinds = [
  { key: 'success', name: '成功' },
  { key: 'alreadyDone', name: '已完成' },
  { key: 'expired', name: '凭证过期' },
  { key: 'failed', name: '业务失败' },
]
const rules = reactive({})
function initRules() {
  for (const { key } of kinds) {
    const match = props.request?.rules?.[key] || (key === 'success' ? { path: 'code', value: 0 } : null)
    rules[key] = {
      path: match?.path || '',
      valueText: match ? JSON.stringify(match.value) : '',
      contains: match?.contains || '',
    }
  }
}
watch(
  () => props.modelValue,
  async (v) => {
    if (!v) {
      form.curl = ''
      preview.value = null
      return
    }
    step.value = props.mode === 'rules' ? 2 : 0
    form.name = props.request?.name || '每日签到'
    form.curl = ''
    form.enabled = props.request ? !!props.request.enabled : true
    preview.value = null
    trial.value = ''
    initRules()
    if (props.mode === 'view') {
      busy.value = true
      try {
        const data = await api(`/requests/${props.request.id}/revision`)
        form.curl = data.rawCurl
        preview.value = { spec: data.spec, warnings: [] }
        step.value = 1
      } catch (e) {
        report(e)
        visible.value = false
      } finally {
        busy.value = false
      }
    }
  },
)
const title = computed(
  () =>
    ({ new: '导入完整 cURL', update: '更新 cURL', rules: '编辑请求与结果规则', view: '查看完整请求' })[
      props.mode
    ],
)
function collectRules() {
  const out = {}
  for (const { key } of kinds) {
    const r = rules[key]
    if (!r.path.trim() && !r.contains.trim()) {
      out[key] = null
      continue
    }
    let value = null
    if (r.path.trim()) {
      if (!r.valueText.trim()) throw new Error('字段规则需填写 JSON 等于值，例如 0、"0"、true 或 null')
      try {
        value = JSON.parse(r.valueText)
      } catch {
        throw new Error('等于值需使用合法 JSON，例如数字 0 或字符串 "0"')
      }
    }
    out[key] = { path: r.path.trim(), value, contains: r.contains.trim() || null }
  }
  return out
}
async function parse() {
  busy.value = true
  try {
    preview.value = await api('/requests/preview', 'POST', { curl: form.curl })
    step.value = 1
  } catch (e) {
    report(e)
  } finally {
    busy.value = false
  }
}
async function testRule() {
  try {
    const data = await api('/rules/test', 'POST', {
      rules: collectRules(),
      httpStatus: ruleHttp.value,
      body: ruleBody.value,
    })
    trial.value = data.status
  } catch (e) {
    report(e)
  }
}
async function save() {
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
        rules: collectRules(),
        version: props.request.version,
      })
    else
      await api(`/accounts/${props.accountId}/requests`, 'POST', {
        name: form.name,
        curl: form.curl,
        enabled: form.enabled,
        rules: collectRules(),
      })
    ElMessage.success(props.mode === 'update' ? '请求版本已更新，凭证暂停已解除' : '请求已保存')
    emit('saved')
    visible.value = false
  } catch (e) {
    report(e)
  } finally {
    busy.value = false
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
    <el-steps v-if="mode === 'new'" :active="step" simple class="space-top" style="margin-bottom: 25px"
      ><el-step title="粘贴 cURL" /><el-step title="解析预览" /><el-step title="结果规则"
    /></el-steps>
    <div v-loading="busy">
      <template v-if="step === 0"
        ><el-form label-position="top"
          ><el-form-item v-if="mode === 'new'" label="请求名称"
            ><el-input v-model="form.name" maxlength="60" /></el-form-item
          ><el-form-item label="完整 cURL"
            ><el-input
              v-model="form.curl"
              type="textarea"
              :rows="11"
              placeholder="curl 'https://example.com/api/checkin' -H 'Cookie: ...' --data-raw '{...}'"
              class="mono" /></el-form-item
        ></el-form>
        <div class="muted">
          支持 Bash / Windows CMD 常见格式。请求在服务器整体加密保存，不存入浏览器本地存储。
        </div>
        <div class="dialog-footer">
          <el-button @click="visible = false">取消</el-button
          ><el-button type="primary" :loading="busy" @click="parse">解析预览</el-button>
        </div></template
      >
      <template v-if="step === 1 && preview"
        ><el-alert
          v-if="mode === 'view'"
          title="这里包含完整请求和凭证，关闭窗口后将从页面状态清除"
          type="warning"
          :closable="false"
        />
        <dl class="preview-grid">
          <dt>方法</dt>
          <dd class="method">{{ preview.spec.method }}</dd>
          <dt>URL</dt>
          <dd class="mono">{{ preview.spec.rawUrl }}</dd>
          <dt>超时</dt>
          <dd>
            {{
              preview.spec.timeoutMillis
                ? `${preview.spec.timeoutMillis / 1000} 秒，另受全局超时上限限制`
                : '使用全局超时设置'
            }}
          </dd>
          <dt>重定向</dt>
          <dd>{{ preview.spec.followRedirects ? '同来源最多 5 跳；跨来源不会转发凭证' : '不自动跟随' }}</dd>
        </dl>
        <h3>请求头（{{ preview.spec.headers.length }}）</h3>
        <pre class="preview-code">{{
          preview.spec.headers.map((h) => `${h.name}: ${h.value}`).join('\n') || '无自定义请求头'
        }}</pre>
        <h3>请求体</h3>
        <pre class="preview-code">{{ bodyText || '无请求体' }}</pre>
        <el-alert
          v-for="warning in preview.warnings"
          :key="warning"
          :title="warning"
          type="warning"
          :closable="false"
          class="space-top"
        />
        <div class="dialog-footer">
          <el-button v-if="mode !== 'view'" @click="step = 0">返回修改</el-button
          ><el-button v-if="mode === 'new'" type="primary" @click="step = 2">设置结果规则</el-button
          ><el-button v-else-if="mode === 'update'" type="primary" @click="save" :loading="busy"
            >保存新版本</el-button
          ><el-button v-else @click="visible = false">关闭</el-button>
        </div></template
      >
      <template v-if="step === 2"
        ><el-form label-position="top"
          ><el-form-item label="请求名称"><el-input v-model="form.name" maxlength="60" /></el-form-item
        ></el-form>
        <p class="muted">
          HTTP 200 不自动代表签到成功。填写业务字段路径和 JSON 值；空白规则不参与判断。数字 0 与字符串 "0"
          分开判断。
        </p>
        <div class="rule-grid muted">
          <span>分类</span><span>JSON 字段路径</span><span>等于值（JSON）</span>
        </div>
        <template v-for="kind in kinds" :key="kind.key"
          ><div class="rule-grid">
            <span class="rule-label">{{ kind.name }}</span
            ><el-input
              v-model="rules[kind.key].path"
              placeholder="data.code"
              :aria-label="`${kind.name}字段路径`"
            /><el-input
              v-model="rules[kind.key].valueText"
              placeholder='0 或 "0"'
              :aria-label="`${kind.name}等于值`"
            />
          </div>
          <div class="rule-grid">
            <span class="rule-label small">可选文本包含</span
            ><el-input
              v-model="rules[kind.key].contains"
              placeholder="与字段规则共同判断；只填文本也可以"
              style="grid-column: 2 / -1"
            /></div></template
        ><el-divider />
        <h3>规则试算</h3>
        <div class="actions space-top">
          <span class="muted">HTTP</span
          ><el-input-number v-model="ruleHttp" :min="100" :max="599" size="small" /><el-button
            size="small"
            @click="testRule"
            >试算规则</el-button
          ><el-tag v-if="trial">{{ trial }}</el-tag>
        </div>
        <el-input
          v-model="ruleBody"
          type="textarea"
          :rows="3"
          class="space-top mono"
          aria-label="规则试算响应"
        />
        <div class="dialog-footer">
          <el-button v-if="mode === 'new'" @click="step = 1">上一步</el-button
          ><el-button v-else @click="visible = false">取消</el-button
          ><el-button type="primary" @click="save" :loading="busy">保存请求</el-button>
        </div></template
      >
    </div>
  </el-dialog>
</template>
