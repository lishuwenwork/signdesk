export const weekdayNames = ['一', '二', '三', '四', '五', '六', '日']

export function scheduleFrequency(spec) {
  return spec.frequency === 'daily' ? '每日' : `星期${spec.weekdays.map((day) => weekdayNames[day - 1]).join('、')}`
}

export function scheduleStrategy(spec) {
  return `${spec.skipCompletedDaily ? '每日成功后跳过' : '每个时刻可执行'} · 间隔 ${spec.intervalSeconds} 秒 · ${
    spec.catchupMinutes ? `补执行 ${spec.catchupMinutes} 分钟` : '不补执行'}`
}

export function schedulePresentation(plan, { loading = false, error = '', platformEnabled = true, paused = null } = {}) {
  if (loading) return { label: '正在读取计划…', tone: 'info', notices: [] }
  if (error) return { label: '计划读取失败', tone: 'warning', notices: [] }
  if (!plan) return { label: '未找到计划', tone: 'warning', notices: [] }
  const notices = []
  if (!platformEnabled) notices.push('平台已禁用，不会自动触发')
  if (paused === true) notices.push('全局定时已暂停')
  else if (paused === null) notices.push('全局定时状态暂不可用')
  return {
    label: plan.spec.enabled ? '启用' : '禁用',
    tone: plan.spec.enabled ? 'success' : 'info',
    frequency: scheduleFrequency(plan.spec),
    times: plan.spec.times.join(' / '),
    timezone: plan.spec.timezone,
    strategy: scheduleStrategy(plan.spec),
    notices,
  }
}
