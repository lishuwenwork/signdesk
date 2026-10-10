import test from 'node:test'
import assert from 'node:assert/strict'
import { addScheduleTime, filterPlans, validateSchedule } from '../src/schedulesView.mjs'
import { cloneSettings, settingsDirty, validateSettings } from '../src/settingsDraft.mjs'

const schedule = () => ({ enabled: true, frequency: 'daily', weekdays: [1, 2, 3, 4, 5, 6, 7], times: ['09:00'],
  timezone: 'Asia/Shanghai', intervalSeconds: 2, catchupMinutes: 0, skipCompletedDaily: true, revision: 3 })
const settings = () => ({ paused: true, concurrency: 2, timeoutSeconds: 20, retentionDays: 30, version: 4,
  proxy: { mode: 'system', host: '', port: 0 } })

test('plan search and enabled filters work together without changing source plans', () => {
  const plans = [{ name: '阅读平台 A', spec: { enabled: true } }, { name: '阅读平台 B', spec: { enabled: false } },
    { name: 'Workspace', spec: { enabled: true } }]
  assert.deepEqual(filterPlans(plans, ' 阅读 ', 'enabled'), [plans[0]])
  assert.deepEqual(filterPlans(plans, '', 'disabled'), [plans[1]])
  assert.deepEqual(filterPlans(plans, 'WORKspace'), [plans[2]])
  assert.deepEqual(filterPlans(plans, '不存在'), [])
  assert.equal(plans.length, 3)
})

test('schedule validates daily and weekly, supported timezones and numeric boundaries', () => {
  assert.equal(validateSchedule(schedule()), '')
  assert.equal(validateSchedule({ ...schedule(), frequency: 'weekly', weekdays: [7], timezone: 'UTC', intervalSeconds: 0, catchupMinutes: 1440 }), '')
  assert.equal(validateSchedule({ ...schedule(), times: [] }), '请至少设置一个时间点')
  assert.equal(validateSchedule({ ...schedule(), frequency: 'weekly', weekdays: [] }), '请至少选择一个星期')
  for (const value of ['24:00', '8:30', '09:99', null]) assert.match(validateSchedule({ ...schedule(), times: [value] }), /HH:mm/)
  assert.match(validateSchedule({ ...schedule(), times: Array(25).fill('09:00') }), /1～24/)
  for (const value of [-1, 61, null, 0.5]) assert.match(validateSchedule({ ...schedule(), intervalSeconds: value }), /间隔/)
  for (const value of [-1, 1441, null, 0.5]) assert.match(validateSchedule({ ...schedule(), catchupMinutes: value }), /补执行窗口/)
  assert.match(validateSchedule({ ...schedule(), weekdays: [8] }), /星期/)
  assert.match(validateSchedule({ ...schedule(), timezone: 'Europe/London' }), /时区/)
})

test('adding times deduplicates and sorts without mutating the original list', () => {
  const original = ['09:00', '20:30']
  assert.deepEqual(addScheduleTime(original, '08:00'), ['08:00', '09:00', '20:30'])
  assert.deepEqual(addScheduleTime(original, '09:00'), original)
  assert.deepEqual(original, ['09:00', '20:30'])
  assert.throws(() => addScheduleTime(original, '25:00'), /HH:mm/)
  const full = Array.from({ length: 24 }, (_, hour) => `${String(hour).padStart(2, '0')}:00`)
  assert.deepEqual(addScheduleTime(full, '09:00'), full)
  assert.throws(() => addScheduleTime(full, '09:30'), /24/)
})

test('settings snapshot is independent, includes proxy drafts and preserves the optimistic version', () => {
  const original = settings(), copy = cloneSettings(original)
  assert.equal(settingsDirty(copy, original), false)
  copy.proxy.host = '127.0.0.1'
  assert.equal(original.proxy.host, '')
  assert.equal(settingsDirty(copy, original), true)
  assert.equal(copy.version, 4)
  assert.equal(settingsDirty(null, original), false)
  for (const key of ['paused', 'concurrency', 'timeoutSeconds', 'retentionDays']) {
    const changed = cloneSettings(original)
    changed[key] = key === 'paused' ? false : changed[key] + 1
    assert.equal(settingsDirty(changed, original), true)
  }
})

test('execution settings require integers in server ranges, including empty inputs', () => {
  assert.equal(validateSettings(settings()), '')
  assert.equal(validateSettings({ ...settings(), concurrency: 8, timeoutSeconds: 120, retentionDays: 365 }), '')
  for (const [key, invalid] of [['concurrency', 9], ['timeoutSeconds', 121], ['retentionDays', 366]]) {
    for (const value of [0, invalid, null, undefined, 1.5, '2']) assert.notEqual(validateSettings({ ...settings(), [key]: value }), '')
  }
})

test('proxy validation allows direct/system and unauthenticated host-only HTTP or SOCKS addresses', () => {
  for (const mode of ['system', 'direct']) assert.equal(validateSettings({ ...settings(), proxy: { mode, host: '', port: 0 } }), '')
  for (const mode of ['http', 'socks']) {
    for (const host of ['127.0.0.1', 'localhost', 'proxy.example.org', '::1', '[::1]'])
      assert.equal(validateSettings({ ...settings(), proxy: { mode, host, port: 8080 } }), '', host)
    for (const host of ['', 'http://localhost', 'localhost:8080', 'user@localhost', 'localhost/path', 'host name', '主机名', 'proxy_1', 'a..com', 'a'.repeat(254)])
      assert.match(validateSettings({ ...settings(), proxy: { mode, host, port: 8080 } }), /主机名或 IP/, host)
    for (const port of [0, 65536, null, 1.5, '8080'])
      assert.match(validateSettings({ ...settings(), proxy: { mode, host: 'localhost', port } }), /端口/)
  }
  assert.match(validateSettings({ ...settings(), proxy: { mode: 'auto' } }), /模式/)
})
