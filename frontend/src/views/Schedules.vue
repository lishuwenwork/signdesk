<script setup>
import { onUnmounted, ref } from 'vue'
import { api, periodic, report, dateTime } from '../api'
import { scheduleFrequency, scheduleStrategy } from '../schedulePresentation'
import ScheduleDialog from '../components/ScheduleDialog.vue'

const plans = ref([]), settings = ref(null), dialog = ref(false), error = ref('')
const platformId = ref(''), platformName = ref('')
let sequence = 0, disposed = false, latestLoad
function load() {
  const current = ++sequence
  latestLoad = (async () => {
    try {
      const [nextPlans, nextSettings] = await Promise.all([api('/schedules'), api('/settings')])
      if (disposed || current !== sequence) return false
      plans.value = nextPlans
      settings.value = nextSettings
      error.value = ''
      return true
    } catch (e) {
      if (!disposed && current === sequence) error.value = e.message
      return false
    }
  })()
  return latestLoad
}
async function refresh() {
  let pending = load(), accepted = await pending
  while (!disposed && pending !== latestLoad) {
    pending = latestLoad
    accepted = await pending
  }
  return !disposed && accepted
}
periodic(load)
onUnmounted(() => { disposed = true; sequence++ })
function edit(plan) {
  platformId.value = plan.platformId
  platformName.value = plan.name
  dialog.value = true
}
async function saved() {
  if (!(await refresh()) && !disposed) error.value = `计划已保存，但刷新失败：${error.value}`
}
async function toggle(plan) {
  try {
    await api(`/platforms/${plan.platformId}/schedule`, 'PUT', { ...plan.spec, enabled: !plan.spec.enabled })
    await refresh()
  } catch (e) {
    report(e)
  }
}
async function pause() {
  try {
    await api('/settings', 'PUT', { ...settings.value, paused: !settings.value.paused })
    await refresh()
  } catch (e) {
    report(e)
  }
}
</script>
<template>
  <div class="page-heading">
    <h1>定时计划</h1>
    <el-button v-if="settings" :type="settings.paused ? 'primary' : 'default'" @click="pause">{{
      settings.paused ? '恢复全局定时' : '暂停全局定时'
    }}</el-button>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-alert v-if="settings?.paused" title="全局定时已暂停，手动执行仍可使用，现有队列继续执行"
    type="warning" :closable="false" style="margin-bottom: 20px" />
  <section class="panel">
    <el-table :data="plans" empty-text="先在平台与账号页面添加一个平台">
      <el-table-column label="平台" min-width="160">
        <template #default="{ row }">
          <strong>{{ row.name }}</strong>
          <div v-if="!row.platformEnabled" class="muted small">平台已停用</div>
        </template>
      </el-table-column>
      <el-table-column label="时间 / 频率" min-width="200">
        <template #default="{ row }">
          <div class="schedule-time" style="font-size: 19px">{{ row.spec.times.join(' / ') }}</div>
          <div class="muted small">{{ scheduleFrequency(row.spec) }} · {{ row.spec.timezone }}</div>
        </template>
      </el-table-column>
      <el-table-column label="下一计划时刻" min-width="175">
        <template #default="{ row }">
          {{ dateTime(row.nextAt) }}
          <div class="muted small">显示转换为北京时间</div>
        </template>
      </el-table-column>
      <el-table-column label="执行策略" min-width="175">
        <template #default="{ row }"><div class="muted small">{{ scheduleStrategy(row.spec) }}</div></template>
      </el-table-column>
      <el-table-column label="启用" width="75">
        <template #default="{ row }">
          <el-switch :model-value="row.spec.enabled" @change="toggle(row)" :aria-label="`${row.name}定时启用`" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="95">
        <template #default="{ row }"><el-button size="small" @click="edit(row)">编辑</el-button></template>
      </el-table-column>
    </el-table>
  </section>
  <div class="inline-info">
    计划时间表示平台批次开始，同平台账号依次执行。服务器每 5
    秒扫描，实际发送受队列负载影响。服务停机期间不能执行，恢复后按补执行窗口处理。
  </div>
  <ScheduleDialog v-model="dialog" :platform-id="platformId" :platform-name="platformName" @saved="saved" />
</template>
