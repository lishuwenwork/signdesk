import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'

const created = new Set()
async function api(request, method, path, data) {
  const response = await request[method](path, data === undefined ? {} : { data })
  expect(response.ok()).toBe(true)
  return response.json()
}
async function createPlatform(request, name) {
  const result = await api(request, 'post', '/api/platforms', { name, note: '', enabled: true, version: 1 })
  created.add(result.id)
  return { ...result, name }
}
async function restoreSettings(request, original) {
  const current = await api(request, 'get', '/api/settings')
  await api(request, 'put', '/api/settings', { ...original, version: current.version })
}
const card = (page, item) => page.locator(`[data-plan-id="${item.id}"]`)
const editor = (page) => page.getByRole('dialog', { name: '编辑平台计划', exact: true })
const replacement = (page) => page.getByRole('dialog', { name: '确认整体替换配置', exact: true })
async function chooseBackup(page, backup) {
  await page.getByLabel('选择备份文件', { exact: true }).setInputFiles({
    name: 'isolated-backup.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(backup)),
  })
  await expect(page.getByRole('button', { name: '预览导入', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '预览导入', exact: true }).click()
  await expect(page.getByRole('button', { name: '确认替换并恢复', exact: true })).toBeVisible()
}

test.beforeEach(async ({ baseURL }) => { expect(baseURL).toBe('http://127.0.0.1:18080') })
test.afterEach(async ({ playwright }) => {
  const request = await playwright.request.newContext({ baseURL: 'http://127.0.0.1:18080' })
  try {
    for (const id of created) {
      expect((await request.delete(`/api/platforms/${id}`, { data: {} })).ok()).toBe(true)
      created.delete(id)
    }
  } finally { await request.dispose() }
})

test('schedule cards search, filter, locate and open a query-selected plan on desktop and mobile', async ({ page, request }, info) => {
  const original = await api(request, 'get', '/api/settings')
  await api(request, 'put', '/api/settings', { ...original, paused: true })
  const first = await createPlatform(request, '分类搜索计划甲'), second = await createPlatform(request, '分类搜索计划乙')
  try {
    const spec = await api(request, 'get', `/api/platforms/${first.id}/schedule`)
    await api(request, 'put', `/api/platforms/${first.id}/schedule`, {
      ...spec, enabled: true, frequency: 'weekly', weekdays: [1, 3, 7], times: ['08:30', '20:45'], timezone: 'UTC', intervalSeconds: 7, catchupMinutes: 15,
    })
    await page.goto(`/#/schedules?platformId=${first.id}`)
    await expect(editor(page)).toContainText(first.name)
    await expect(editor(page).getByRole('spinbutton', { name: '同平台请求间隔（秒）', exact: true })).toHaveValue('7')
    await page.keyboard.press('Escape')
    await expect(editor(page)).toBeHidden()
    await expect(card(page, first)).toHaveClass(/focused/)
    await expect(card(page, first)).toContainText('08:30 / 20:45')
    await expect(card(page, first)).toContainText('UTC')
    await expect(card(page, first)).toContainText('全局暂停中')
    await page.getByLabel('搜索平台计划', { exact: true }).fill('分类搜索计划')
    await page.getByRole('button', { name: '已启用', exact: true }).click()
    await expect(card(page, first)).toBeVisible()
    await expect(card(page, second)).toHaveCount(0)
    await page.getByRole('button', { name: '已暂停', exact: true }).click()
    await expect(card(page, second)).toBeVisible()
    await expect(card(page, first)).toHaveCount(0)
    await page.getByLabel('搜索平台计划', { exact: true }).fill('无匹配的名称')
    await expect(page.getByRole('heading', { name: '暂无匹配的计划', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '显示全部计划', exact: true }).click()
    await page.setViewportSize({ width: 390, height: 844 })
    await expect(card(page, first)).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
    await card(page, first).getByRole('button', { name: '编辑计划', exact: true }).click()
    await expect(editor(page).getByLabel('已添加时刻', { exact: true }).locator('.el-tag')).toHaveText(['08:30', '20:45'])
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
    await page.screenshot({ path: info.outputPath('schedule-cards-mobile.png'), fullPage: true, animations: 'disabled' })
    await editor(page).getByRole('button', { name: '取消', exact: true }).click()
    await card(page, first).getByRole('button', { name: `定位平台 ${first.name}`, exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`#/platforms\\?platformId=${first.id}$`))
    await expect(page.getByRole('heading', { name: first.name, level: 2, exact: true })).toBeVisible()
  } finally { await restoreSettings(request, original) }
})

test('schedule switches isolate duplicate writes and reject a stale revision without overwriting newer parameters', async ({ page, request }) => {
  const original = await api(request, 'get', '/api/settings')
  await api(request, 'put', '/api/settings', { ...original, paused: true })
  const item = await createPlatform(request, '计划开关竞态')
  let release, started
  const gate = new Promise((resolve) => { release = resolve }), captured = new Promise((resolve) => { started = resolve })
  try {
    const before = await api(request, 'get', `/api/platforms/${item.id}/schedule`)
    await page.goto('/#/schedules')
    await expect(card(page, item)).toBeVisible()
    let writes = 0
    await page.route(`**/api/platforms/${item.id}/schedule`, async (route) => {
      if (route.request().method() !== 'PUT') return route.continue()
      writes++
      expect(route.request().postDataJSON()).toEqual({ ...before, enabled: true })
      started()
      await gate
      const response = await route.fetch()
      await route.fulfill({ response })
    })
    const control = card(page, item).getByRole('switch', { name: `${item.name}定时启用`, exact: true })
    await card(page, item).locator('.el-switch').click()
    await captured
    await expect(control).toBeDisabled()
    await card(page, item).locator('.el-switch').dispatchEvent('click')
    expect(writes).toBe(1)
    release()
    await expect(control).toBeEnabled()
    await expect(control).toBeChecked()
    await page.unrouteAll({ behavior: 'wait' })
    const current = await api(request, 'get', `/api/platforms/${item.id}/schedule`)
    expect(current).toMatchObject({ enabled: true, revision: before.revision + 1 })
    const stalePlans = await api(request, 'get', '/api/schedules')
    await page.route('**/api/schedules', (route) => route.fulfill({ json: stalePlans }))
    await api(request, 'put', `/api/platforms/${item.id}/schedule`, { ...current, intervalSeconds: 11 })
    const failed = page.waitForResponse((response) => response.request().method() === 'PUT' && new URL(response.url()).pathname === `/api/platforms/${item.id}/schedule`)
    await card(page, item).locator('.el-switch').click()
    expect((await failed).status()).toBe(409)
    await expect(card(page, item).getByRole('alert')).toContainText('计划版本已变化')
    expect(await api(request, 'get', `/api/platforms/${item.id}/schedule`)).toMatchObject({ intervalSeconds: 11, enabled: true })
  } finally { release(); await page.unrouteAll({ behavior: 'ignoreErrors' }); await restoreSettings(request, original) }
})

test('settings tabs retain drafts, support keyboard navigation and confirm before abandoning edits', async ({ page }, info) => {
  await page.goto('/#/settings')
  const concurrency = page.getByRole('spinbutton', { name: '同时执行的平台数', exact: true })
  await expect(concurrency).toBeVisible()
  const previous = Number(await concurrency.inputValue()), changed = previous === 8 ? 7 : previous + 1
  await concurrency.fill(String(changed))
  await concurrency.blur()
  await expect(page.getByRole('button', { name: '保存设置', exact: true })).toBeEnabled()
  await page.getByRole('tab', { name: '网络代理', exact: true }).click()
  await expect(page.getByRole('radio', { name: /HTTP 代理/ })).toBeVisible()
  await page.getByRole('tab', { name: '执行偏好', exact: true }).click()
  await expect(concurrency).toHaveValue(String(changed))
  await page.getByRole('link', { name: '定时计划', exact: true }).click()
  await expect(page.locator('.el-message-box')).toContainText('未保存的修改')
  await page.locator('.el-message-box').getByRole('button', { name: '取消', exact: true }).click()
  await expect(page).toHaveURL(/#\/settings/)
  await expect(concurrency).toHaveValue(String(changed))
  const executionTab = page.getByRole('tab', { name: '执行偏好', exact: true })
  await executionTab.focus()
  await executionTab.press('ArrowRight')
  await expect(page.getByRole('tab', { name: '网络代理', exact: true })).toHaveAttribute('aria-selected', 'true')
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
  await page.screenshot({ path: info.outputPath('settings-proxy-mobile.png'), fullPage: true, animations: 'disabled' })
  await page.getByRole('link', { name: '定时计划', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认', exact: true }).click()
  await expect(page).toHaveURL(/#\/schedules$/)
})

test('settings validate before writing and preserve a conflicting draft until explicit reload', async ({ page, request }) => {
  const original = await api(request, 'get', '/api/settings')
  try {
    await page.goto('/#/settings?tab=proxy')
    await page.getByRole('radio', { name: /HTTP 代理/ }).check()
    await page.getByRole('textbox', { name: '代理主机', exact: true }).fill('http://127.0.0.1:8080')
    await page.getByRole('spinbutton', { name: '代理端口', exact: true }).fill('8080')
    await page.getByRole('spinbutton', { name: '代理端口', exact: true }).blur()
    let writes = 0
    page.on('request', (req) => { if (req.method() === 'PUT' && new URL(req.url()).pathname === '/api/settings') writes++ })
    await page.getByRole('button', { name: '保存设置', exact: true }).click()
    await expect(page.getByRole('alert').filter({ hasText: '代理地址仅填写' })).toBeVisible()
    expect(writes).toBe(0)
    await page.getByRole('textbox', { name: '代理主机', exact: true }).fill('127.0.0.1')
    const latest = await api(request, 'get', '/api/settings')
    const newerTimeout = latest.timeoutSeconds === 120 ? 119 : latest.timeoutSeconds + 1
    await api(request, 'put', '/api/settings', { ...latest, timeoutSeconds: newerTimeout })
    const conflict = page.waitForResponse((response) => response.request().method() === 'PUT' && new URL(response.url()).pathname === '/api/settings')
    await page.getByRole('button', { name: '保存设置', exact: true }).click()
    expect((await conflict).status()).toBe(409)
    await expect(page.getByRole('alert')).toContainText('草稿仍保留')
    await expect(page.getByRole('textbox', { name: '代理主机', exact: true })).toHaveValue('127.0.0.1')
    await expect(page.getByRole('button', { name: '保存设置', exact: true })).toBeDisabled()
    expect(writes).toBe(1)
    expect((await api(request, 'get', '/api/settings')).timeoutSeconds).toBe(newerTimeout)
    await page.getByRole('button', { name: '重新载入', exact: true }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '取消', exact: true }).click()
    await expect(page.getByRole('textbox', { name: '代理主机', exact: true })).toHaveValue('127.0.0.1')
    await page.getByRole('button', { name: '重新载入', exact: true }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '确认', exact: true }).click()
    await expect(page.getByRole('button', { name: '保存设置', exact: true })).toBeDisabled()
  } finally { await restoreSettings(request, original) }
})

test('backup uses the plaintext contract, explicit replacement consent and leaves automatic scheduling paused', async ({ page, request }, info) => {
  const original = await api(request, 'get', '/api/settings')
  const originalBackup = await api(request, 'post', '/api/backups/export', { includeRequests: true })
  const receivedBefore = (await api(request, 'get', 'http://127.0.0.1:18081/received')).length
  try {
    await page.goto('/#/settings?tab=backup')
    const downloadPending = page.waitForEvent('download')
    await page.getByRole('button', { name: '导出配置', exact: true }).click()
    const download = await downloadPending, path = info.outputPath('isolated-config.json')
    await download.saveAs(path)
    const backup = JSON.parse(await readFile(path, 'utf8'))
    expect(backup.format).toBe('signdesk-plain-v1')
    expect(backup).not.toHaveProperty('formatVersion')
    expect(backup.payload.includesRequests).toBe(false)
    expect(backup.payload.completed).toEqual([])
    expect(backup.payload.pending).toEqual([])
    await chooseBackup(page, backup)
    let imports = 0
    page.on('request', (req) => { if (new URL(req.url()).pathname === '/api/backups/import') imports++ })
    await page.getByRole('button', { name: '确认替换并恢复', exact: true }).click()
    await expect(replacement(page).getByRole('button', { name: '确认整体替换', exact: true })).toBeDisabled()
    await page.keyboard.press('Escape')
    await expect(replacement(page)).toBeHidden()
    expect(imports).toBe(0)
    await page.getByRole('button', { name: '确认替换并恢复', exact: true }).click()
    await replacement(page).getByRole('checkbox', { name: '我理解这是整体替换，而不是合并', exact: true }).check()
    const importRequest = page.waitForRequest((req) => new URL(req.url()).pathname === '/api/backups/import')
    await replacement(page).getByRole('button', { name: '确认整体替换', exact: true }).click()
    expect((await importRequest).postDataJSON()).toEqual({ backup, replace: true })
    await expect(replacement(page)).toBeHidden()
    await expect(page.getByLabel('选择备份文件', { exact: true })).toHaveValue('')
    await expect(page.getByRole('button', { name: '确认替换并恢复', exact: true })).toHaveCount(0)
    expect((await api(request, 'get', '/api/settings')).paused).toBe(true)
    expect((await api(request, 'get', 'http://127.0.0.1:18081/received')).length).toBe(receivedBefore)
    const storage = await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }))
    expect(storage).not.toContain('signdesk-plain-v1')
  } finally {
    await api(request, 'post', '/api/backups/import', { backup: originalBackup, replace: true })
    await restoreSettings(request, original)
  }
})

test('late preview and export responses cannot revive cleared backup state or download after leaving the category', async ({ page, request }) => {
  const backup = await api(request, 'post', '/api/backups/export', { includeRequests: false })
  let release, started
  let gate = new Promise((resolve) => { release = resolve }), captured = new Promise((resolve) => { started = resolve })
  try {
    await page.goto('/#/settings?tab=backup')
    await page.getByLabel('选择备份文件', { exact: true }).setInputFiles({ name: 'late.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(backup)) })
    await expect(page.getByRole('button', { name: '预览导入', exact: true })).toBeEnabled()
    await page.route('**/api/backups/preview', async (route) => {
      const response = await route.fetch()
      started()
      await gate
      await route.fulfill({ response })
    })
    await page.getByRole('button', { name: '预览导入', exact: true }).click()
    await captured
    await page.getByRole('tab', { name: '关于系统', exact: true }).click()
    await expect(page.getByRole('button', { name: '刷新系统状态', exact: true })).toBeVisible()
    release()
    await page.getByRole('tab', { name: '配置备份', exact: true }).click()
    await expect(page.getByLabel('选择备份文件', { exact: true })).toHaveValue('')
    await expect(page.getByRole('button', { name: '确认替换并恢复', exact: true })).toHaveCount(0)
    await page.unrouteAll({ behavior: 'wait' })
    gate = new Promise((resolve) => { release = resolve })
    captured = new Promise((resolve) => { started = resolve })
    let downloads = 0
    page.on('download', () => { downloads++ })
    await page.route('**/api/backups/export', async (route) => {
      const response = await route.fetch()
      started()
      await gate
      await route.fulfill({ response })
    })
    await page.getByRole('button', { name: '导出配置', exact: true }).click()
    await captured
    await page.getByRole('tab', { name: '执行偏好', exact: true }).click()
    release()
    await page.unrouteAll({ behavior: 'wait' })
    await page.getByRole('tab', { name: '配置备份', exact: true }).click()
    expect(downloads).toBe(0)
    await expect(page.getByRole('button', { name: '导出配置', exact: true })).toBeEnabled()
  } finally { release(); await page.unrouteAll({ behavior: 'ignoreErrors' }) }
})

test('oversized and legacy backup files fail locally and never reach preview or replacement', async ({ page }) => {
  await page.goto('/#/settings?tab=backup')
  let previews = 0
  page.on('request', (req) => { if (new URL(req.url()).pathname === '/api/backups/preview') previews++ })
  const input = page.getByLabel('选择备份文件', { exact: true })
  await input.setInputFiles({ name: 'oversized.json', mimeType: 'application/json', buffer: Buffer.alloc(12_582_913, 32) })
  await expect(page.getByRole('alert')).toContainText('文件超过 12 MiB')
  await expect(page.getByRole('button', { name: '预览导入', exact: true })).toBeDisabled()
  await input.setInputFiles({ name: 'legacy.json', mimeType: 'application/json', buffer: Buffer.from('{"format":"signdesk-backup-v1"}') })
  await expect(page.getByRole('alert')).toContainText('不支持此备份格式')
  await expect(page.getByRole('button', { name: '预览导入', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '确认替换并恢复', exact: true })).toHaveCount(0)
  expect(previews).toBe(0)
})
