<script setup>
import { ref, watch } from 'vue'
import { api, report } from '../api'
import { ruleKinds, collectRules } from '../resultRules'

const props = defineProps({ modelValue: { type: Object, required: true } })
const emit = defineEmits(['update:modelValue'])
const ruleHttp = ref(200),
  ruleBody = ref('{"code":0}'),
  trial = ref(''),
  busy = ref(false)
watch(() => [props.modelValue, ruleHttp.value, ruleBody.value], () => { trial.value = '' })
function update(key, field, value) {
  emit('update:modelValue', {
    ...props.modelValue,
    [key]: { ...props.modelValue[key], [field]: value },
  })
}
async function testRule() {
  busy.value = true
  trial.value = ''
  const draft = props.modelValue, httpStatus = ruleHttp.value, body = ruleBody.value
  try {
    const data = await api('/rules/test', 'POST', {
      rules: collectRules(draft), httpStatus, body,
    })
    if (draft === props.modelValue && httpStatus === ruleHttp.value && body === ruleBody.value)
      trial.value = data.status
  } catch (e) {
    report(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div>
    <p class="muted">
      HTTP 200 不自动代表签到成功。填写业务字段路径和 JSON 值；空白规则不参与判断。数字 0 与字符串 "0" 分开判断。
    </p>
    <div class="rule-grid muted">
      <span>分类</span><span>JSON 字段路径</span><span>等于值（JSON）</span>
    </div>
    <template v-for="kind in ruleKinds" :key="kind.key">
      <div class="rule-grid">
        <span class="rule-label">{{ kind.name }}</span>
        <el-input :model-value="modelValue[kind.key].path" @update:model-value="update(kind.key, 'path', $event)"
          placeholder="data.code" :aria-label="`${kind.name}字段路径`" />
        <el-input :model-value="modelValue[kind.key].valueText" @update:model-value="update(kind.key, 'valueText', $event)"
          placeholder='0 或 "0"' :aria-label="`${kind.name}等于值`" />
      </div>
      <div class="rule-grid">
        <span class="rule-label small">可选文本包含</span>
        <el-input :model-value="modelValue[kind.key].contains" @update:model-value="update(kind.key, 'contains', $event)"
          placeholder="与字段规则共同判断；只填文本也可以" :aria-label="`${kind.name}文本包含`" style="grid-column: 2 / -1" />
      </div>
    </template>
    <el-divider />
    <h3>规则试算</h3>
    <div class="actions space-top">
      <span class="muted">HTTP</span>
      <el-input-number v-model="ruleHttp" :min="100" :max="599" size="small" />
      <el-button size="small" :loading="busy" @click="testRule">试算规则</el-button>
      <el-tag v-if="trial">{{ trial }}</el-tag>
    </div>
    <el-input v-model="ruleBody" type="textarea" :rows="3" class="space-top mono" aria-label="规则试算响应" />
  </div>
</template>
