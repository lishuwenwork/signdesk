<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, confirm } from '../api'
import { weekdayNames } from '../schedulePresentation'
import { addScheduleTime, validateSchedule } from '../schedulesView.mjs'

const props = defineProps({ modelValue: Boolean, platformId: String, platformName: String })
const emit = defineEmits(['update:modelValue', 'saved'])
const visible = computed({
  get: () => props.modelValue,
  set: (value) => { if (!saving.value || value) emit('update:modelValue', value) },
})
const form = ref(null), loading = ref(false), saving = ref(false), error = ref(''), conflict = ref(false), timeInput = ref('')
let session = 0
watch(() => [props.modelValue, props.platformId], ([open]) => {
  session++
  form.value = null
  error.value = ''
  conflict.value = false
  timeInput.value = ''
  saving.value = false
  loading.value = false
  if (open && props.platformId) load()
}, { immediate: true })
onUnmounted(() => { session++; form.value = null })

async function load() {
  const current = ++session, id = props.platformId
  loading.value = true
  error.value = ''
  conflict.value = false
  form.value = null
  timeInput.value = ''
  try {
    const data = await api(`/platforms/${id}/schedule`)
    if (current === session && props.modelValue) form.value = structuredClone(data)
  } catch (e) {
    if (current === session) error.value = e.status === 404 ? '该平台的计划不存在，无法编辑。' : e.message
  } finally {
    if (current === session) loading.value = false
  }
}
async function reload() {
  if (saving.value || loading.value) return
  const current = session
  if (form.value && !(await confirm('载入最新计划会放弃当前未保存的编辑，是否继续？'))) return
  if (current === session && props.modelValue) await load()
}
function addTime() {
  if (!timeInput.value || !form.value || saving.value) return
  try {
    form.value.times = addScheduleTime(form.value.times, timeInput.value)
    timeInput.value = ''
    if (!conflict.value) error.value = ''
  } catch (e) { error.value = e.message }
}
async function save() {
  if (!form.value || loading.value || saving.value || conflict.value) return
  const validation = validateSchedule(form.value)
  if (validation) { error.value = validation; return }
  const current = session, id = props.platformId, data = JSON.parse(JSON.stringify(form.value))
  saving.value = true
  error.value = ''
  try {
    await api(`/platforms/${id}/schedule`, 'PUT', data)
    if (current !== session || !props.modelValue) return
    emit('update:modelValue', false)
    emit('saved', id)
    ElMessage.success('计划已保存')
  } catch (e) {
    if (current !== session || !props.modelValue) return
    conflict.value = e.status === 409
    error.value = conflict.value ? '计划修订已变化，本次未覆盖。草稿仍保留，请核对后载入最新计划再编辑。' : e.message
  } finally {
    if (current === session) saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" class="schedule-editor" title="编辑平台计划" width="min(620px, calc(100vw - 32px))"
    destroy-on-close :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <div class="schedule-editor-intro"><span>{{ platformName }}</span><span v-if="form" class="schedule-version">计划 v{{ form.revision }}</span></div>
    <p class="schedule-context">计划时刻表示平台批次开始，不会立即执行。已有批次继续使用创建时冻结的参数。</p>
    <div v-if="loading" v-loading="true" class="schedule-loading" role="status" aria-label="正在加载计划" />
    <p v-if="error" class="schedule-form-error" role="alert">{{ error }}</p>
    <el-form v-if="form" label-position="top" :disabled="saving" class="schedule-editor-form">
      <div class="schedule-enable-line"><div><strong>启用定时</strong><p>仍受平台启停与全局定时暂停约束</p></div>
        <el-switch v-model="form.enabled" aria-label="启用定时" /></div>
      <el-form-item label="频率">
        <el-radio-group v-model="form.frequency">
          <el-radio-button value="daily">每日</el-radio-button>
          <el-radio-button value="weekly">指定星期</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="form.frequency === 'weekly'" label="星期">
        <el-checkbox-group v-model="form.weekdays" class="schedule-weekdays">
          <el-checkbox-button v-for="(name, index) in weekdayNames" :key="index" :value="index + 1">{{ name }}</el-checkbox-button>
        </el-checkbox-group>
      </el-form-item>
      <el-form-item label="时间点">
        <div class="schedule-times">
          <div class="actions schedule-time-entry">
            <el-time-picker v-model="timeInput" value-format="HH:mm" format="HH:mm" placeholder="选择时间" aria-label="新增计划时刻" style="width: 160px" />
            <el-button :disabled="form.times.length >= 24" @click="addTime">添加时刻</el-button>
            <span class="schedule-field-hint">最多 24 个</span>
          </div>
          <div v-if="form.times.length" class="actions schedule-time-tags" aria-label="已添加时刻">
            <el-tag v-for="time in form.times" :key="time" :closable="!saving" @close="form.times = form.times.filter((t) => t !== time)">{{ time }}</el-tag>
          </div>
          <span v-else class="schedule-field-hint">至少添加一个时间点</span>
        </div>
      </el-form-item>
      <el-form-item label="平台业务时区">
        <el-select v-model="form.timezone" aria-label="平台业务时区">
          <el-option label="北京时间（Asia/Shanghai）" value="Asia/Shanghai" />
          <el-option label="协调世界时（UTC）" value="UTC" />
        </el-select>
      </el-form-item>
      <div class="schedule-field-grid">
        <el-form-item label="同平台请求间隔（秒）"><el-input-number v-model="form.intervalSeconds" aria-label="同平台请求间隔（秒）" :min="0" :max="60" :precision="0" /><span class="schedule-field-hint">0–60 秒，同平台依次发送</span></el-form-item>
        <el-form-item label="补执行窗口（分钟，0 表示不补执行）"><el-input-number v-model="form.catchupMinutes" aria-label="补执行窗口（分钟，0 表示不补执行）" :min="0" :max="1440" :precision="0" /><span class="schedule-field-hint">0–1440 分钟，仅当前业务日期</span></el-form-item>
      </div>
      <div class="schedule-enable-line schedule-skip-line"><div><strong>每日成功后跳过</strong><p>签到建议开启，查询类请求可关闭</p></div><el-switch v-model="form.skipCompletedDaily" aria-label="每日成功后跳过" /></div>
      <p class="schedule-safety">超时或中断为待确认，不自动重试；补执行不会重放运行中请求，也不会绕过凭证暂停。</p>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="visible = false">取消</el-button>
      <el-button v-if="error" :disabled="saving || loading" @click="reload">{{ form ? '载入最新计划' : '重试读取' }}</el-button>
      <el-button type="primary" :loading="saving" :disabled="!form || loading || conflict" @click="save">保存计划</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.schedule-editor { --el-color-primary: #214e3e; --el-color-primary-light-9: #eef3e5; --el-color-primary-light-3: #7f9b79; --el-border-radius-base: 7px; --el-dialog-border-radius: 12px; color: #173b31; font: 13px/1.6 "Microsoft YaHei", "微软雅黑", sans-serif; }
.schedule-editor :deep(.el-dialog__title) { color: #173b31; font-size: 18px; font-weight: 600; }
.schedule-editor :deep(.el-dialog__body) { padding: 12px 8px 8px; }
.schedule-editor :deep(.el-form-item__label) { font-size: 12px; color: #456044; padding-bottom: 7px; }
.schedule-editor :deep(.el-form-item) { margin-bottom: 19px; }
.schedule-editor-intro { display: flex; justify-content: space-between; gap: 14px; color: #214e3e; font-weight: 600; overflow-wrap: anywhere; }
.schedule-version { font-size: 11px; color: #8b987c; white-space: nowrap; font-weight: 400; }
.schedule-context { margin: 7px 0 17px; font-size: 11px; color: #7c8a73; line-height: 1.8; }
.schedule-loading { min-height: 110px; }
.schedule-enable-line { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 14px 15px; background: #f5f7ef; border: 1px solid #e6ebde; border-radius: 8px; margin-bottom: 19px; }
.schedule-enable-line strong { font-size: 12px; font-weight: 500; }
.schedule-enable-line p { font-size: 11px; color: #7c8a73; margin: 4px 0 0; }
.schedule-skip-line { margin-bottom: 12px; }
.schedule-times { display: grid; gap: 12px; width: 100%; }
.schedule-time-entry, .schedule-time-tags { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.schedule-time-entry :deep(.el-button) { margin: 0; }
.schedule-field-hint { font-size: 11px; color: #849176; line-height: 1.6; }
.schedule-field-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 17px; }
.schedule-field-grid :deep(.el-form-item__content) { display: grid; gap: 8px; }
.schedule-field-grid :deep(.el-input-number) { width: 100%; max-width: 190px; }
.schedule-safety { font-size: 11px; color: #7c8a73; margin: 0; line-height: 1.9; }
.schedule-form-error { padding: 11px 14px; background: #faf0e9; border: 1px solid #ead7c5; border-radius: 7px; color: #a44e42; font-size: 12px; margin: 0 0 15px; }
.schedule-weekdays { display: flex; flex-wrap: wrap; row-gap: 7px; }
.schedule-weekdays :deep(.el-checkbox-button__inner) { padding: 8px 12px; font-size: 12px; }
@media (max-width: 560px) { .schedule-editor :deep(.el-dialog__body) { padding: 10px 0 6px; } .schedule-field-grid { grid-template-columns: 1fr; gap: 0; } .schedule-weekdays :deep(.el-checkbox-button__inner) { padding: 8px 9px; } .schedule-enable-line { padding: 12px; } .schedule-editor :deep(.el-dialog__footer) { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 8px; } .schedule-editor :deep(.el-dialog__footer .el-button) { margin-left: 0; } }
</style>
