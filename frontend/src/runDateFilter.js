/**
 * @param {Date} [now] 当前时刻。
 * @returns {[string, string]} 北京时间的今天，作为日期区间的两端。
 */
export function todayRunDateRange(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(now)
  const values = Object.fromEntries(parts.map(({ type, value }) => [type, value]))
  const today = `${values.year}-${values.month}-${values.day}`
  return [today, today]
}

/**
 * @param {import('vue-router').LocationQuery} query 页面查询参数。
 * @param {Date} [now] 当前时刻。
 * @returns {string[]} 已选日期区间；显式空日期表示全部历史。
 */
export function runDateRangeFromQuery(query, now = new Date()) {
  if (query.startDate === undefined && query.endDate === undefined) return todayRunDateRange(now)
  const start = String(query.startDate || query.endDate || '')
  const end = String(query.endDate || query.startDate || '')
  return start && end ? [start, end] : []
}
