<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, report } from '../api'
import { weekdayNames } from '../schedulePresentation'

const props = defineProps({ modelValue: Boolean, platformId: String, platformName: String })
const emit = defineEmits(['update:modelValue', 'saved'])
const visible = computed({
  get: () => props.modelValue,
  set: (value) => { if (!saving.value || value) emit('update:modelValue', value) },
})
const form = ref(null), loading = ref(false), saving = ref(false), error = ref(''), timeInput = ref('')
let session = 0
watch(() => [props.modelValue, props.platformId], ([open]) => {
  session++
  form.value = null
  error.value = ''
  timeInput.value = ''
  saving.value = false
  loading.value = false
  if (open && props.platformId) load()
})
onUnmounted(() => { session++ })

async function load() {
  const current = ++session, id = props.platformId
  loading.value = true
  error.value = ''
  form.value = null
  try {
    const data = await api(`/platforms/${id}/schedule`)
    if (current === session && props.modelValue) form.value = structuredClone(data)
  } catch (e) {
    if (current === session) error.value = e.status === 404 ? '该平台的计划不存在，无法编辑。' : e.message
  } finally {
    if (current === session) loading.value = false
  }
}
function addTime() {
  if (!timeInput.value || !form.value) return
  form.value.times = [...new Set([...form.value.times, timeInput.value])].sort()
  timeInput.value = ''
}
async function save() {
  if (!form.value || loading.value || saving.value) return
  if (!form.value.times.length) return ElMessage.warning('请至少设置一个时间点')
  if (form.value.frequency === 'weekly' && !form.value.weekdays.length)
    return ElMessage.warning('请至少选择一个星期')
  const current = session, id = props.platformId, data = JSON.parse(JSON.stringify(form.value))
  saving.value = true
  try {
    await api(`/platforms/${id}/schedule`, 'PUT', data)
    if (current !== session || !props.modelValue) return
    emit('update:modelValue', false)
    emit('saved', id)
    ElMessage.success('计划已保存')
  } catch (e) {
    if (current === session) report(e)
  } finally {
    if (current === session) saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="编辑平台计划" width="580px" destroy-on-close
    :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <p class="muted schedule-context">{{ platformName }}</p>
    <div v-if="loading" v-loading="true" class="schedule-loading" aria-label="正在加载计划" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form v-if="form" label-position="top" :disabled="saving">
      <el-form-item label="启用定时"><el-switch v-model="form.enabled" /></el-form-item>
      <el-form-item label="频率">
        <el-radio-group v-model="form.frequency">
          <el-radio-button value="daily">每日</el-radio-button>
          <el-radio-button value="weekly">指定星期</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="form.frequency === 'weekly'" label="星期">
        <el-checkbox-group v-model="form.weekdays">
          <el-checkbox-button v-for="(name, index) in weekdayNames" :key="index" :value="index + 1">{{ name }}</el-checkbox-button>
        </el-checkbox-group>
      </el-form-item>
      <el-form-item label="时间点">
        <div class="schedule-times">
          <div class="actions schedule-time-entry">
            <el-time-picker v-model="timeInput" value-format="HH:mm" format="HH:mm" placeholder="选择时间" style="width: 160px" />
            <el-button :disabled="form.times.length >= 24" @click="addTime">添加时刻</el-button>
          </div>
          <div v-if="form.times.length" class="actions schedule-time-tags" aria-label="已添加时刻">
            <el-tag v-for="time in form.times" :key="time" :closable="!saving" @close="form.times = form.times.filter((t) => t !== time)">{{ time }}</el-tag>
          </div>
        </div>
      </el-form-item>
      <el-form-item label="平台业务时区">
        <el-select v-model="form.timezone">
          <el-option label="北京时间（Asia/Shanghai）" value="Asia/Shanghai" />
          <el-option label="协调世界时（UTC）" value="UTC" />
        </el-select>
      </el-form-item>
      <el-form-item label="同平台请求间隔（秒）"><el-input-number v-model="form.intervalSeconds" :min="0" :max="60" /></el-form-item>
      <el-form-item label="补执行窗口（分钟，0 表示不补执行）"><el-input-number v-model="form.catchupMinutes" :min="0" :max="1440" /></el-form-item>
      <el-form-item label="每日成功后跳过">
        <el-switch v-model="form.skipCompletedDaily" />
        <span class="muted" style="margin-left: 12px">签到建议开启，查询类请求可关闭</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="visible = false">取消</el-button>
      <el-button v-if="error" @click="load">重试读取</el-button>
      <el-button type="primary" :loading="saving" :disabled="!form || loading" @click="save">保存计划</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.schedule-context { margin: 0 0 16px; overflow-wrap: anywhere; }
.schedule-loading { min-height: 100px; }
.schedule-times { display: grid; gap: 12px; width: 100%; }
</style>
