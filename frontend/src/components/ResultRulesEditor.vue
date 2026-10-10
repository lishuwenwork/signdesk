<script setup>
import { onUnmounted, ref, watch } from 'vue'
import { api, report } from '../api'
import { ruleKinds, collectRules } from '../resultRules'
const props = defineProps({ modelValue: { type: Object, required: true }, disabled: Boolean })
const emit = defineEmits(['update:modelValue'])
const ruleHttp = ref(200), ruleBody = ref('{"code":0}'), trial = ref(''), busy = ref(false)
let session = 0, disposed = false
watch(() => [props.modelValue, ruleHttp.value, ruleBody.value], () => { session++; trial.value = '' })
onUnmounted(() => { disposed = true; session++; ruleBody.value = ''; trial.value = '' })
function update(key, field, value) { if (props.disabled) return; emit('update:modelValue', { ...props.modelValue, [key]: { ...props.modelValue[key], [field]: value } }) }
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
  <div class="rules-editor"><p class="muted small">HTTP 200 不自动代表签到成功。JSON 比较保留值类型，数字 0 与字符串 "0" 不同；留空的分类不参与判断。</p>
    <div v-for="kind in ruleKinds" :key="kind.key" class="rule-edit"><h3>{{ kind.name }}</h3><div class="rule-edit-fields"><label><span>JSON 字段路径</span><el-input :disabled="disabled" :model-value="modelValue[kind.key].path" @update:model-value="update(kind.key,'path',$event)" placeholder="data.code" :aria-label="`${kind.name}字段路径`" /></label><label><span>等于值（JSON）</span><el-input :disabled="disabled" :model-value="modelValue[kind.key].valueText" @update:model-value="update(kind.key,'valueText',$event)" placeholder='0 或 "0"' :aria-label="`${kind.name}等于值`" /></label></div><label class="contains-field"><span>可选文本包含</span><el-input :disabled="disabled" :model-value="modelValue[kind.key].contains" @update:model-value="update(kind.key,'contains',$event)" placeholder="与字段规则共同判断；只填文本也可以" :aria-label="`${kind.name}文本包含`" /></label></div>
    <section class="rule-test"><div class="rule-test-heading"><h3>规则试算</h3><span class="muted small">不会向目标平台发送请求</span></div><div class="actions"><label class="muted small" for="rule-http">HTTP</label><el-input-number :disabled="disabled" id="rule-http" v-model="ruleHttp" :min="100" :max="599" size="small" aria-label="试算 HTTP 状态" /><el-button :disabled="disabled" size="small" :loading="busy" @click="testRule">试算规则</el-button><el-tag v-if="trial">{{ trial }}</el-tag></div><el-input :disabled="disabled" v-model="ruleBody" type="textarea" :rows="3" class="space-top mono" aria-label="规则试算响应" /></section>
  </div>
</template>
<style scoped>
.rule-edit { border: 1px solid var(--line); border-radius: 7px; padding: 12px; margin-top: 12px; }
.rule-edit h3 { font-size: 12px; margin-bottom: 10px; font-weight: 500; }
.rule-edit-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.rule-edit label { display: grid; gap: 6px; font-size: 11px; color: var(--muted); }
.contains-field { margin-top: 10px; }
.rule-test { margin-top: 18px; background: #f6f8f1; border: 1px solid #e4eadd; border-radius: 8px; padding: 13px; }
.rule-test-heading { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 13px; }
.rule-test-heading h3 { font-size: 12px; }
@media(max-width:730px) { .rule-edit-fields { gap: 8px; } .rule-test-heading { align-items: flex-start; flex-direction: column; gap: 4px; } }
</style>
