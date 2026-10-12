<script setup>
import { computed, onUnmounted, ref, useId, watch } from 'vue'
import { api, labels, report } from '../api'
import { ruleKinds, collectRules, failureOnMismatchHint } from '../resultRules'
/** @type {{ modelValue: import('../resultRules').RuleDraft, disabled?: boolean }} */
const props = defineProps({ modelValue: { type: Object, required: true }, disabled: Boolean })
const emit = defineEmits(['update:modelValue'])
const ruleHttp = ref(200), ruleBody = ref('{"code":0}'), trial = ref(''), busy = ref(false)
/** @type {import('vue').Ref<string[]>} */
const openKinds = ref([])
const editorId = useId()
const mismatchHint = computed(() => failureOnMismatchHint(props.modelValue))
let session = 0, disposed = false
watch(() => [props.modelValue, ruleHttp.value, ruleBody.value], () => { session++; trial.value = '' })
watch(() => ruleKinds.filter(({ key }) => key !== 'success' &&
  (props.modelValue[key].path.trim() || props.modelValue[key].contains.trim())).map(({ key }) => key).join(','),
  configured => { openKinds.value = [...new Set([...openKinds.value, ...configured.split(',').filter(Boolean)])] },
  { immediate: true })
onUnmounted(() => { disposed = true; session++; ruleBody.value = ''; trial.value = '' })
function update(key, field, value) { if (props.disabled) return; emit('update:modelValue', { ...props.modelValue, [key]: { ...props.modelValue[key], [field]: value } }) }
function updateFailure(value) { if (props.disabled) return; emit('update:modelValue', { ...props.modelValue, failureOnSuccessMismatch: value }) }
function toggle(key) { openKinds.value = openKinds.value.includes(key) ? openKinds.value.filter(item => item !== key) : [...openKinds.value, key] }
async function testRule() {
  if (busy.value || props.disabled) return
  const current = session
  busy.value = true; trial.value = ''
  const draft = props.modelValue, httpStatus = ruleHttp.value, body = ruleBody.value
  try {
    const data = await api('/rules/test', 'POST', { rules: collectRules(draft), httpStatus, body })
    if (!disposed && current === session) trial.value = data.status
  } catch (e) { if (!disposed && current === session) report(e) }
  finally { if (!disposed) busy.value = false }
}
</script>
<template>
  <div class="rules-editor">
    <p class="muted small">先配置成功条件，其他分类按需填写。HTTP 200 不自动代表业务成功；JSON 比较保留类型，数字 0 与字符串 "0" 不同。</p>
    <div v-for="kind in ruleKinds" :key="kind.key" class="rule-edit" :class="{ 'rule-primary': kind.key === 'success' }">
      <h3 v-if="kind.key === 'success'">成功 <span class="muted">主要条件</span></h3>
      <button v-else type="button" class="rule-toggle" :aria-label="`${openKinds.includes(kind.key) ? '收起' : '展开'}${kind.name}规则`"
        :aria-expanded="openKinds.includes(kind.key)" :aria-controls="`${editorId}-${kind.key}`" @click="toggle(kind.key)">
        <span>{{ kind.name }} <small>{{ kind.key === 'failed' ? '高级 · 可选' : '可选' }}</small></span>
        <span aria-hidden="true">{{ openKinds.includes(kind.key) ? '−' : '＋' }}</span>
      </button>
      <div v-if="kind.key === 'success' || openKinds.includes(kind.key)" :id="`${editorId}-${kind.key}`" class="rule-fields">
        <p v-if="kind.key === 'alreadyDone'" class="muted small rule-help">例如“今日已签到”。即使同时命中普通失败，也记录当天完成。</p>
        <p v-else-if="kind.key === 'expired'" class="muted small rule-help">例如“登录已失效”。命中后暂停该请求，等待更新 cURL；HTTP 401 自动识别。</p>
        <p v-else-if="kind.key === 'failed'" class="muted small rule-help">仅在需要额外识别特定错误时填写；常见接口可直接使用成功条件下的反向失败判断。</p>
        <div class="rule-edit-fields">
          <label><span>JSON 字段路径</span><el-input :disabled="disabled" :model-value="modelValue[kind.key].path" @update:model-value="update(kind.key,'path',$event)" placeholder="data.code" :aria-label="`${kind.name}字段路径`" /></label>
          <label><span>等于值（JSON）</span><el-input :disabled="disabled" :model-value="modelValue[kind.key].valueText" @update:model-value="update(kind.key,'valueText',$event)" placeholder='0 或 "0"' :aria-label="`${kind.name}等于值`" /></label>
        </div>
        <label class="contains-field"><span>可选文本包含</span><el-input :disabled="disabled" :model-value="modelValue[kind.key].contains" @update:model-value="update(kind.key,'contains',$event)" placeholder="与字段规则共同判断；只填文本也可以" :aria-label="`${kind.name}文本包含`" /></label>
        <div v-if="kind.key === 'success'" class="failure-option">
          <div class="failure-option-heading"><span>非成功值视为业务失败</span><el-switch :model-value="modelValue.failureOnSuccessMismatch" :disabled="disabled || (!!mismatchHint && !modelValue.failureOnSuccessMismatch)" aria-label="反向失败判断" @update:model-value="updateFailure" /></div>
          <p class="muted small">可选，默认关闭。字段存在且类型正确，但不等于成功值时判为失败，例如 code ≠ 0 或 success = false，无需枚举错误码。</p>
          <p v-if="mismatchHint" class="small mismatch-hint" :class="{ 'is-error': modelValue.failureOnSuccessMismatch }" role="status">{{ mismatchHint }}{{ modelValue.failureOnSuccessMismatch ? ' 请修正规则或关闭此开关后再保存。' : '' }}</p>
          <p v-else class="muted small">字段缺失、类型不符或响应不完整仍为待确认，不会直接判失败。</p>
        </div>
      </div>
    </div>
    <p class="muted small rule-order">完整 2xx 响应按：凭证过期 → 已完成 → 显式失败 → 成功 → 反向失败判断。留空的分类不参与判断，无法判断则待确认。</p>
    <section class="rule-test"><div class="rule-test-heading"><h3>规则试算</h3><span class="muted small">不会向目标平台发送请求</span></div><div class="actions"><label class="muted small" :for="`${editorId}-http`">HTTP</label><el-input-number :disabled="disabled" :id="`${editorId}-http`" v-model="ruleHttp" :min="100" :max="599" size="small" aria-label="试算 HTTP 状态" /><el-button :disabled="disabled" size="small" :loading="busy" @click="testRule">试算规则</el-button><el-tag v-if="trial" role="status">{{ labels[trial] || trial }} · {{ trial }}</el-tag></div><el-input :disabled="disabled" v-model="ruleBody" type="textarea" :rows="3" class="space-top mono" aria-label="规则试算响应" /></section>
  </div>
</template>
<style scoped>
.rule-edit { border: 1px solid var(--line); border-radius: 7px; padding: 12px; margin-top: 12px; }
.rule-primary { border-color: #c8d2bd; }
.rule-edit h3 { font-size: 12px; margin-bottom: 10px; font-weight: 500; }
.rule-edit h3 span { font-size: 10px; margin-left: 6px; font-weight: 400; }
.rule-toggle { display: flex; align-items: center; justify-content: space-between; width: 100%; border: 0; background: none; padding: 0; color: var(--ink); font: inherit; font-size: 12px; text-align: left; cursor: pointer; }
.rule-toggle small { font-size: 10px; color: var(--muted); margin-left: 6px; }
.rule-toggle:focus-visible { outline: 2px solid var(--forest); outline-offset: 4px; }
.rule-help { margin: 12px 0; }
.rule-edit-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.rule-edit label { display: grid; gap: 6px; font-size: 11px; color: var(--muted); }
.contains-field { margin-top: 10px; }
.failure-option { margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--line); }
.failure-option-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 12px; }
.failure-option p { margin-top: 6px; }
.mismatch-hint { color: var(--muted); }
.mismatch-hint.is-error { color: #a94438; }
.rule-order { margin-top: 14px; line-height: 1.7; }
.rule-test { margin-top: 18px; background: #f6f8f1; border: 1px solid #e4eadd; border-radius: 8px; padding: 13px; }
.rule-test-heading { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 13px; }
.rule-test-heading h3 { font-size: 12px; }
@media(max-width:730px) { .rule-edit-fields { gap: 8px; } .rule-test-heading { align-items: flex-start; flex-direction: column; gap: 4px; } }
</style>
