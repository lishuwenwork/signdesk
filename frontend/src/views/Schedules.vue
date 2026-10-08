<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, periodic, report, dateTime } from '../api'
const plans = ref([]),
  settings = ref(null),
  dialog = ref(false),
  saving = ref(false),
  error = ref(''),
  platformId = ref(''),
  timeInput = ref('09:00')
const weekdays = ['一', '二', '三', '四', '五', '六', '日']
const form = reactive({
  enabled: false,
  frequency: 'daily',
  weekdays: [1, 2, 3, 4, 5, 6, 7],
  times: ['09:00'],
  timezone: 'Asia/Shanghai',
  intervalSeconds: 2,
  catchupMinutes: 0,
  skipCompletedDaily: true,
  revision: 1,
})
async function load() {
  try {
    ;[plans.value, settings.value] = await Promise.all([api('/schedules'), api('/settings')])
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}
periodic(load)
function edit(plan) {
  platformId.value = plan.platformId
  Object.assign(form, JSON.parse(JSON.stringify(plan.spec)))
  timeInput.value = ''
  dialog.value = true
}
function addTime() {
  if (!timeInput.value) return
  form.times = [...new Set([...form.times, timeInput.value])].sort()
  timeInput.value = ''
}
async function save() {
  saving.value = true
  try {
    await api(`/platforms/${platformId.value}/schedule`, 'PUT', form)
    dialog.value = false
    await load()
    ElMessage.success('计划已保存')
  } catch (e) {
    report(e)
  } finally {
    saving.value = false
  }
}
async function toggle(plan) {
  try {
    await api(`/platforms/${plan.platformId}/schedule`, 'PUT', { ...plan.spec, enabled: !plan.spec.enabled })
    await load()
  } catch (e) {
    report(e)
  }
}
async function pause() {
  try {
    await api('/settings', 'PUT', { ...settings.value, paused: !settings.value.paused })
    await load()
  } catch (e) {
    report(e)
  }
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>按平台，安排自己的节奏。</h1>
      <div class="muted">平台 A 09:00，平台 B 10:00。每个平台可以设置多个时间点。</div>
    </div>
    <el-button v-if="settings" :type="settings.paused ? 'primary' : 'default'" @click="pause">{{
      settings.paused ? '恢复全局定时' : '暂停全局定时'
    }}</el-button>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-alert
    v-if="settings?.paused"
    title="全局定时已暂停，手动执行仍可使用，现有队列继续执行"
    type="warning"
    :closable="false"
    style="margin-bottom: 20px"
  />
  <section class="panel">
    <el-table :data="plans" empty-text="先在平台与账号页面添加一个平台"
      ><el-table-column label="平台" min-width="160"
        ><template #default="{ row }"
          ><strong>{{ row.name }}</strong>
          <div v-if="!row.platformEnabled" class="muted small">平台已停用</div></template
        ></el-table-column
      ><el-table-column label="时间 / 频率" min-width="200"
        ><template #default="{ row }"
          ><div class="schedule-time" style="font-size: 19px">{{ row.spec.times.join(' / ') }}</div>
          <div class="muted small">
            {{
              row.spec.frequency === 'daily'
                ? '每日'
                : `星期${row.spec.weekdays.map((d) => weekdays[d - 1]).join('、')}`
            }}
            · {{ row.spec.timezone }}
          </div></template
        ></el-table-column
      ><el-table-column label="下次执行" min-width="175"
        ><template #default="{ row }"
          >{{ dateTime(row.nextAt) }}
          <div class="muted small">显示转换为北京时间</div></template
        ></el-table-column
      ><el-table-column label="执行策略" min-width="175"
        ><template #default="{ row }"
          ><span>{{ row.spec.skipCompletedDaily ? '每日成功后跳过' : '每个时刻可执行' }}</span>
          <div class="muted small">
            间隔 {{ row.spec.intervalSeconds }} 秒 ·
            {{ row.spec.catchupMinutes ? `补执行 ${row.spec.catchupMinutes} 分钟` : '不补执行' }}
          </div></template
        ></el-table-column
      ><el-table-column label="启用" width="75"
        ><template #default="{ row }"
          ><el-switch
            :model-value="row.spec.enabled"
            @change="toggle(row)"
            :aria-label="`${row.name}定时启用`" /></template></el-table-column
      ><el-table-column label="操作" width="95"
        ><template #default="{ row }"
          ><el-button size="small" @click="edit(row)">编辑</el-button></template
        ></el-table-column
      ></el-table
    >
  </section>
  <div class="inline-info">
    计划时间表示平台批次开始，同平台账号依次执行。服务器每 5
    秒扫描，实际发送受队列负载影响。服务停机期间不能执行，恢复后按补执行窗口处理。
  </div>
  <el-dialog v-model="dialog" title="编辑平台计划" width="580px" :close-on-click-modal="false"
    ><el-form label-position="top"
      ><el-form-item label="启用定时"><el-switch v-model="form.enabled" /></el-form-item
      ><el-form-item label="频率"
        ><el-radio-group v-model="form.frequency"
          ><el-radio-button value="daily">每日</el-radio-button
          ><el-radio-button value="weekly">指定星期</el-radio-button></el-radio-group
        ></el-form-item
      ><el-form-item v-if="form.frequency === 'weekly'" label="星期"
        ><el-checkbox-group v-model="form.weekdays"
          ><el-checkbox-button v-for="(name, index) in weekdays" :key="index" :value="index + 1">{{
            name
          }}</el-checkbox-button></el-checkbox-group
        ></el-form-item
      ><el-form-item label="时间点"
        ><div class="actions">
          <el-tag
            v-for="time in form.times"
            :key="time"
            closable
            @close="form.times = form.times.filter((t) => t !== time)"
            >{{ time }}</el-tag
          >
        </div>
        <div class="actions space-top">
          <el-time-picker
            v-model="timeInput"
            value-format="HH:mm"
            format="HH:mm"
            placeholder="选择时间"
            style="width: 160px"
          /><el-button @click="addTime">添加时刻</el-button>
        </div></el-form-item
      ><el-form-item label="平台业务时区"
        ><el-select v-model="form.timezone"
          ><el-option label="北京时间（Asia/Shanghai）" value="Asia/Shanghai" /><el-option
            label="协调世界时（UTC）"
            value="UTC" /></el-select></el-form-item
      ><el-form-item label="同平台请求间隔（秒）"
        ><el-input-number v-model="form.intervalSeconds" :min="0" :max="60" /></el-form-item
      ><el-form-item label="补执行窗口（分钟，0 表示不补执行）"
        ><el-input-number v-model="form.catchupMinutes" :min="0" :max="1440" /></el-form-item
      ><el-form-item label="每日成功后跳过"
        ><el-switch v-model="form.skipCompletedDaily" /><span class="muted" style="margin-left: 12px"
          >签到建议开启，查询类请求可关闭</span
        ></el-form-item
      ></el-form
    ><template #footer
      ><el-button @click="dialog = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="save">保存计划</el-button></template
    ></el-dialog
  >
</template>
