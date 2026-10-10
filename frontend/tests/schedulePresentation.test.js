import { test } from 'node:test'
import assert from 'node:assert/strict'
import { scheduleFrequency, scheduleStrategy, schedulePresentation } from '../src/schedulePresentation.js'

const spec = {
  enabled: false, frequency: 'daily', weekdays: [1, 2, 3, 4, 5, 6, 7], times: ['09:00'],
  timezone: 'Asia/Shanghai', intervalSeconds: 2, catchupMinutes: 0, skipCompletedDaily: true, revision: 1,
}
const plan = { platformId: '9223372036854775807', spec, nextAt: null }

test('daily and weekly summaries keep business timezone and all configured times', () => {
  assert.equal(scheduleFrequency(spec), '每日')
  assert.equal(scheduleFrequency({ ...spec, frequency: 'weekly', weekdays: [1, 3, 7] }), '星期一、三、日')
  const result = schedulePresentation({ ...plan, spec: { ...spec, times: ['09:00', '20:00'], timezone: 'UTC' } }, { paused: false })
  assert.equal(result.times, '09:00 / 20:00')
  assert.equal(result.timezone, 'UTC')
  assert.deepEqual(result.notices, [])
})

test('default disabled plan differs from missing, loading and failed reads', () => {
  assert.equal(schedulePresentation(plan, { paused: false }).label, '禁用')
  assert.equal(schedulePresentation(null).label, '未找到计划')
  assert.equal(schedulePresentation(null, { loading: true }).label, '正在读取计划…')
  assert.equal(schedulePresentation(plan, { error: 'offline' }).label, '计划读取失败')
  assert.equal(schedulePresentation(plan, { error: 'offline' }).frequency, undefined)
})

test('own plan state, parent disablement and global pause remain independent', () => {
  const result = schedulePresentation({ ...plan, spec: { ...spec, enabled: true } }, { platformEnabled: 0, paused: true })
  assert.equal(result.label, '启用')
  assert.deepEqual(result.notices, ['平台已禁用，不会自动触发', '全局定时已暂停'])
  assert.deepEqual(schedulePresentation(plan, { platformEnabled: 1, paused: false }).notices, [])
  assert.deepEqual(schedulePresentation(plan).notices, ['全局定时状态暂不可用'])
})

test('enabled plan summary is not a promise to send and does not calculate or mutate nextAt', () => {
  const value = { ...plan, nextAt: '2026-10-11T01:00:00Z', spec: { ...spec, enabled: true } }
  const original = structuredClone(value)
  assert.equal(schedulePresentation(value, { paused: true }).label, '启用')
  assert.deepEqual(schedulePresentation(value, { paused: true }).notices, ['全局定时已暂停'])
  assert.deepEqual(value, original)
  assert.equal(value.platformId, '9223372036854775807')
})

test('policy formatting retains zero interval, catchup and daily-completion policy', () => {
  assert.equal(scheduleStrategy(spec), '每日成功后跳过 · 间隔 2 秒 · 不补执行')
  assert.equal(scheduleStrategy({ ...spec, intervalSeconds: 0, catchupMinutes: 30, skipCompletedDaily: false }),
    '每个时刻可执行 · 间隔 0 秒 · 补执行 30 分钟')
})
