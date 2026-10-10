const clockPattern = /^(?:[01]\d|2[0-3]):[0-5]\d$/

export function filterPlans(plans, search = '', filter = 'all') {
  const term = search.trim().toLocaleLowerCase()
  return plans.filter((plan) => (!term || plan.name.toLocaleLowerCase().includes(term))
    && (filter === 'all' || (filter === 'enabled' ? plan.spec.enabled : !plan.spec.enabled)))
}

export function validateSchedule(spec) {
  if (!spec || !['daily', 'weekly'].includes(spec.frequency)) return '请选择每日或指定星期'
  if (!Array.isArray(spec.times) || !spec.times.length) return '请至少设置一个时间点'
  if (spec.times.length > 24 || spec.times.some((time) => !clockPattern.test(time)))
    return '计划需设置 1～24 个 HH:mm 时间点'
  if (!Array.isArray(spec.weekdays) || spec.weekdays.some((day) => !Number.isInteger(day) || day < 1 || day > 7))
    return '请选择星期一至星期日'
  if (spec.frequency === 'weekly' && !spec.weekdays.length) return '请至少选择一个星期'
  if (!['Asia/Shanghai', 'UTC'].includes(spec.timezone)) return '请选择支持的业务时区'
  if (!Number.isInteger(spec.intervalSeconds) || spec.intervalSeconds < 0 || spec.intervalSeconds > 60)
    return '请求间隔应为 0～60 秒的整数'
  if (!Number.isInteger(spec.catchupMinutes) || spec.catchupMinutes < 0 || spec.catchupMinutes > 1440)
    return '补执行窗口应为 0～1440 分钟的整数'
  return ''
}

export function addScheduleTime(times, time) {
  if (!clockPattern.test(time)) throw new Error('请填写有效的 HH:mm 时间点')
  if (times.includes(time)) return [...times]
  if (times.length >= 24) throw new Error('最多设置 24 个时间点')
  return [...times, time].sort()
}
