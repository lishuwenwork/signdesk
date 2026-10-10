import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'

const createdPlatforms = new Set()
test.afterEach(async ({ playwright }) => {
  const cleanup = await playwright.request.newContext({ baseURL: 'http://127.0.0.1:18080' })
  try {
    for (const id of createdPlatforms) {
      await cleanup.delete(`/api/platforms/${id}`, { data: {} })
      createdPlatforms.delete(id)
    }
  } finally { await cleanup.dispose() }
})

async function downloadBackup(page, info, includeRequests) {
  const requestPromise = page.waitForRequest((r) => new URL(r.url()).pathname === '/api/backups/export')
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: includeRequests ? '导出完整备份' : '导出配置', exact: true }).click()
  expect((await requestPromise).postDataJSON()).toEqual({ includeRequests })
  const download = await downloadPromise
  expect(download.suggestedFilename()).toMatch(includeRequests ? /^signdesk-full-backup-\d+\.json$/ : /^signdesk-config-\d+\.json$/)
  const filename = info.outputPath(includeRequests ? 'full-backup.json' : 'config.json')
  await download.saveAs(filename)
  const text = await readFile(filename, 'utf8'), backup = JSON.parse(text)
  expect(backup.format).toBe('signdesk-plain-v1')
  expect(Object.keys(backup).sort()).toEqual(['format', 'payload'])
  expect(Object.keys(backup.payload).sort()).toEqual([
    'accounts', 'completed', 'includesRequests', 'pending', 'platforms', 'requests', 'schedules', 'settings', 'templates',
  ])
  expect(backup.payload.includesRequests).toBe(includeRequests)
  return { filename, text, backup }
}

async function previewBackup(page, filename, backup) {
  await page.getByLabel('选择备份文件').setInputFiles(filename)
  await expect(page.getByRole('button', { name: '确认替换并恢复', exact: true })).toHaveCount(0)
  const previewRequest = page.waitForRequest((r) => new URL(r.url()).pathname === '/api/backups/preview')
  await page.getByRole('button', { name: '预览导入', exact: true }).click()
  expect((await previewRequest).postDataJSON()).toEqual({ backup })
  await expect(page.getByRole('button', { name: '确认替换并恢复', exact: true })).toBeVisible()
  const counts = page.locator('.inline-info').filter({ hasText: '个平台、' })
  await expect(counts).toContainText(`${backup.payload.platforms.length} 个平台、${backup.payload.accounts.length} 个账号、${backup.payload.requests.length} 个请求`)
  await expect(counts).toContainText(`${backup.payload.templates.length} 份接口模板`)
  await expect(counts).toContainText(backup.payload.includesRequests ? '包含完整请求' : '不含请求内容，恢复后需更新 cURL')
}

async function restoreBackup(page, backup) {
  await page.getByRole('button', { name: '确认替换并恢复', exact: true }).click()
  const confirmation = page.getByRole('dialog', { name: '确认整体替换配置', exact: true })
  await expect(confirmation).toContainText('不是合并')
  const confirmReplace = confirmation.getByRole('button', { name: '确认整体替换', exact: true })
  await expect(confirmReplace).toBeDisabled()
  await confirmation.getByRole('checkbox', { name: '我理解这是整体替换，而不是合并', exact: true }).check()
  const importRequest = page.waitForRequest((r) => new URL(r.url()).pathname === '/api/backups/import')
  await confirmReplace.click()
  expect((await importRequest).postDataJSON()).toEqual({ backup, replace: true })
  await expect(page.getByText('配置已恢复，定时保持暂停，请检查后恢复', { exact: true }).last()).toBeVisible()
  await expect(page.getByRole('button', { name: '确认替换并恢复', exact: true })).toHaveCount(0)
}

test('manage, import, send, update, schedule, and export through the real web UI', async ({
  page,
  request,
}, info) => {
  test.setTimeout(90000)
  const errors = []
  const receivedBefore = (await (await request.get('http://127.0.0.1:18081/received')).json()).length
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/#/platforms')
  await page.getByRole('button', { name: '新增平台', exact: true }).last().click()
  await page.getByPlaceholder('例如：平台 A').fill('回显测试平台')
  await page.getByRole('button', { name: '保存平台', exact: true }).click()
  await expect(page.getByRole('heading', { name: '回显测试平台' })).toBeVisible()
  const platform = (await (await request.get('/api/platforms')).json()).find((p) => p.name === '回显测试平台')
  createdPlatforms.add(platform.id)
  await page.locator('.accounts-heading').getByRole('button', { name: '添加账号', exact: true }).click()
  await page.getByPlaceholder('例如：主账号').fill('测试账号 A')
  await page.getByRole('button', { name: '保存账号', exact: true }).click()
  const accountGroup = page.getByRole('region', { name: '账号 测试账号 A', exact: true })
  await accountGroup.getByRole('button', { name: '添加请求', exact: true }).click()
  await page
    .getByRole('textbox', { name: '完整 cURL', exact: true })
    .fill(
      `curl 'http://127.0.0.1:18081/check?a=%2f&x=1&x=2' -H 'Cookie: browser-test=one' -H 'X-Device: device-A' --data-raw '{"value":"original"}'`,
    )
  await page.getByRole('button', { name: '解析预览' }).click()
  await expect(page.getByRole('dialog')).toContainText('browser-test=one')
  await page.getByRole('button', { name: '设置结果规则' }).click()
  await page.getByRole('button', { name: '试算规则' }).click()
  await expect(page.getByRole('dialog')).toContainText('success')
  await page.getByRole('button', { name: '保存请求', exact: true }).click()
  const requestRow = accountGroup.getByRole('group', { name: '请求 每日签到', exact: true })
  await expect(requestRow).toBeVisible()
  await requestRow.getByRole('button', { name: '每日签到的更多操作', exact: true }).click()
  await page.getByRole('menuitem', { name: '执行', exact: true }).click()
  await page.getByRole('link', { name: '执行记录', exact: true }).click()
  await expect(page.locator('.log-table tbody')).toContainText('成功')
  const received = await (await request.get('http://127.0.0.1:18081/received')).json()
  expect(received[receivedBefore]).toMatchObject({
    url: '/check?a=%2f&x=1&x=2',
    method: 'POST',
    cookie: 'browser-test=one',
    device: 'device-A',
    body: '{"value":"original"}',
  })
  await page.getByRole('button', { name: '详情' }).first().click()
  await expect(page.locator('.el-drawer')).toContainText('命中成功规则')
  await page.locator('.el-drawer').getByRole('button', { name: '响应体', exact: true }).click()
  await expect(page.getByTestId('response-body')).toHaveText('{"code":0}')
  await page.locator('.el-drawer__close-btn').click()
  await expect(page.getByTestId('response-body')).toHaveCount(0)
  await page.getByRole('link', { name: '平台与账号', exact: true }).click()
  await page.locator('.platform-choice').filter({ hasText: '回显测试平台' }).click()
  await requestRow.getByRole('button', { name: '更新 cURL', exact: true }).click()
  const fullCurl = `curl 'http://127.0.0.1:18081/check' -H 'Cookie: browser-test=two' --data-raw 'second-body'`
  await page.getByRole('textbox', { name: '完整 cURL', exact: true }).fill(fullCurl)
  await page.getByRole('button', { name: '解析预览' }).click()
  await page.getByRole('button', { name: '保存新版本' }).click()
  await expect(page.locator('.request-row')).toContainText('版本 2')
  await requestRow.getByRole('button', { name: '每日签到的更多操作', exact: true }).click()
  await page.getByRole('menuitem', { name: '重新执行（忽略当日标记）', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认', exact: true }).click()
  await expect
    .poll(async () => (await (await request.get('http://127.0.0.1:18081/received')).json()).length)
    .toBe(receivedBefore + 2)
  await page.getByRole('link', { name: '定时计划', exact: true }).click()
  const plan = page.locator(`.plan-card[data-plan-id="${platform.id}"]`)
  await plan.getByRole('button', { name: '编辑计划', exact: true }).click()
  await page.getByRole('dialog', { name: '编辑平台计划', exact: true }).locator('.el-switch').first().click()
  await page.getByRole('button', { name: '保存计划', exact: true }).click()
  await expect(plan).toContainText('09:00')
  const plans = await (await request.get('/api/schedules')).json()
  expect(plans.find((item) => item.platformId === platform.id).spec.enabled).toBe(true)
  await expect.poll(async () => {
    const logs = await (await request.get('/api/runs')).json()
    return logs.items.some((item) => ['queued', 'running'].includes(item.status))
  }).toBe(false)
  await page.getByRole('link', { name: '设置与备份', exact: true }).click()
  await page.goto('/#/settings?tab=backup')
  const includeRequests = page.getByRole('checkbox', { name: '包含完整请求（明文，可能含凭证）' })
  await expect(includeRequests).not.toBeChecked()
  await expect(page.locator('input[type="password"]')).toHaveCount(0)
  await expect(page.getByText('两种模式都可能包含手工填入名称、备注或规则值的敏感信息，不保证隐私安全，导出前请自行检查。', { exact: true })).toBeVisible()
  const config = await downloadBackup(page, info, false)
  expect(config.text).not.toContain('browser-test')
  expect(config.text).not.toContain('second-body')
  expect(config.text).not.toContain('18081')
  expect(config.backup.payload.requests).toHaveLength(1)
  for (const item of config.backup.payload.requests) expect(item).not.toHaveProperty('rawCurl')
  expect(config.backup.payload.completed).toEqual([])
  expect(config.backup.payload.pending).toEqual([])
  await previewBackup(page, config.filename, config.backup)

  await page.locator('.el-checkbox').filter({ has: includeRequests }).click()
  await expect(includeRequests).toBeChecked()
  await expect(page.getByText('完整备份含明文 Cookie、Token、签名等敏感信息，请仅保存到可信位置，不要公开分享。', { exact: true })).toBeVisible()
  const full = await downloadBackup(page, info, true)
  expect(full.backup.payload.requests[0].rawCurl).toBe(fullCurl)
  expect(full.text).toContain('browser-test=two')
  expect(full.text).toContain('second-body')
  expect(full.backup.payload.completed.length).toBeGreaterThan(0)
  expect(full.backup.payload.pending).toEqual([])
  await previewBackup(page, full.filename, full.backup)

  // Preview is read-only, and cancelling replacement must not submit an import.
  let importRequests = 0
  page.on('request', (r) => { if (new URL(r.url()).pathname === '/api/backups/import') importRequests++ })
  const savedRequest = (await (await request.get('/api/platforms')).json()).find((p) => p.id === platform.id).accounts[0].requests[0]
  await page.getByRole('button', { name: '确认替换并恢复', exact: true }).click()
  await page.getByRole('dialog', { name: '确认整体替换配置', exact: true })
    .getByRole('button', { name: '取消', exact: true }).click()
  expect(importRequests).toBe(0)
  expect((await (await request.get('/api/platforms')).json()).find((p) => p.id === platform.id).accounts[0].requests[0].currentRevision).toBe(2)
  const receivedBeforeImport = (await (await request.get('http://127.0.0.1:18081/received')).json()).length
  await restoreBackup(page, full.backup)
  expect(importRequests).toBe(1)
  const restored = await request.get(`/api/requests/${savedRequest.id}/revision`)
  expect(restored.headers()['cache-control']).toBe('no-store')
  expect(await restored.json()).toMatchObject({ rawCurl: fullCurl })
  expect((await (await request.get('/api/platforms')).json()).find((p) => p.id === platform.id).accounts[0].requests[0].currentRevision).toBe(1)
  expect((await (await request.get('/api/settings')).json()).paused).toBe(true)
  const fullRoundTrip = await (await request.post('/api/backups/export', { data: { includeRequests: true } })).json()
  expect(fullRoundTrip.payload.completed).toEqual(full.backup.payload.completed)
  expect(fullRoundTrip.payload.pending).toEqual(full.backup.payload.pending)

  await previewBackup(page, config.filename, config.backup)
  await restoreBackup(page, config.backup)
  expect(importRequests).toBe(2)
  const placeholders = (await (await request.get('/api/platforms')).json()).find((p) => p.id === platform.id).accounts[0].requests
  expect(placeholders[0]).toMatchObject({ id: savedRequest.id, enabled: 0, safeHost: '' })
  expect((await (await request.get('/api/settings')).json()).paused).toBe(true)
  expect((await (await request.get('http://127.0.0.1:18081/received')).json()).length).toBe(receivedBeforeImport)
  const browserStorage = await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }))
  expect(browserStorage).not.toContain('browser-test')
  expect(browserStorage).not.toContain('second-body')
  await page.getByRole('link', { name: '平台与账号', exact: true }).click()
  const placeholderRow = page.locator('.request-row').filter({ hasText: savedRequest.name })
  await expect(placeholderRow).toContainText('未配置 cURL，需更新')
  let revisionViews = 0
  page.on('request', (r) => { if (new URL(r.url()).pathname === `/api/requests/${savedRequest.id}/revision`) revisionViews++ })
  await placeholderRow.getByRole('button', { name: `${savedRequest.name}的更多操作`, exact: true }).click()
  await expect(page.getByRole('menuitem', { name: '查看完整请求', exact: true })).toBeDisabled()
  await page.keyboard.press('Escape')
  const updateCurl = placeholderRow.getByRole('button', { name: '更新 cURL', exact: true })
  await expect(updateCurl).toBeEnabled()
  await updateCurl.click()
  const curlInput = page.getByRole('textbox', { name: '完整 cURL', exact: true })
  await expect(curlInput).toBeEnabled()
  await expect(curlInput).toHaveValue('')
  await page.getByRole('button', { name: '取消', exact: true }).click()
  expect(revisionViews).toBe(0)
  await expect(page.locator('.el-message')).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('platform-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('heading', { name: '平台与账号', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
  await page.screenshot({ path: info.outputPath('platform-mobile.png'), fullPage: true })
  expect(errors).toEqual([])
})

test('legacy encrypted and old config files are explicitly unsupported', async ({ page, request }) => {
  await page.goto('/#/settings?tab=backup')
  let previewRequests = 0
  page.on('request', (r) => { if (new URL(r.url()).pathname === '/api/backups/preview') previewRequests++ })
  for (const format of ['signdesk-backup-v1', 'signdesk-config']) {
    const backup = { format, ciphertext: 'synthetic-legacy-fixture', payload: {} }
    await page.getByLabel('选择备份文件').setInputFiles({
      name: 'legacy-backup.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(backup)),
    })
    await expect(page.getByText('不支持此备份格式，仅接受 signdesk-plain-v1 明文 JSON 文件', { exact: true }).last()).toBeVisible()
    await expect(page.getByRole('button', { name: '预览导入', exact: true })).toBeDisabled()
    await expect(page.getByRole('button', { name: '确认替换并恢复', exact: true })).toHaveCount(0)
    const rejected = await request.post('/api/backups/preview', { data: { backup } })
    expect(rejected.status()).toBe(400)
    expect((await rejected.json()).message).toMatch(/不支持|格式/)
  }
  expect(previewRequests).toBe(0)
})

test('write boundaries reject cross-site and form requests without adding login', async ({ request }) => {
  const bad = await request.post('/api/platforms', {
    data: { name: '跨站', enabled: true },
    headers: { Origin: 'https://unrelated.example' },
  })
  expect(bad.status()).toBe(403)
  const form = await request.post('/api/platforms', { form: { name: '表单请求', enabled: 'true' } })
  expect(form.status()).toBe(415)
  const good = await request.get('/api/platforms')
  expect(good.ok()).toBe(true)
  const status = await request.get('/api/system/status')
  expect(status.headers()['cache-control']).toBe('no-store')
})

test('saved HTTP proxy survives page reload and routes the next execution', async ({ page, request }) => {
  const previous = await (await request.get('/api/settings')).json()
  await page.goto('/#/settings?tab=proxy')
  await page.getByRole('radio', { name: /HTTP 代理/ }).check()
  await page.getByRole('textbox', { name: '代理主机', exact: true }).fill('127.0.0.1')
  await page.getByRole('spinbutton', { name: '代理端口', exact: true }).fill('18081')
  await page.getByRole('button', { name: '保存设置', exact: true }).click()
  await expect(page.getByText('设置已保存', { exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('textbox', { name: '代理主机', exact: true })).toHaveValue('127.0.0.1')
  await expect(page.getByRole('spinbutton', { name: '代理端口', exact: true })).toHaveValue('18081')
  const saved = await (await request.get('/api/settings')).json()
  expect(saved.proxy).toEqual({ mode: 'http', host: '127.0.0.1', port: 18081 })

  const platform = await (await request.post('/api/platforms', {
    data: { name: '代理测试平台', note: '', enabled: true, version: 1 },
  })).json()
  createdPlatforms.add(platform.id)
  const account = await (await request.post(`/api/platforms/${platform.id}/accounts`, {
    data: { alias: '代理测试账号', enabled: true, version: 1 },
  })).json()
  const created = await (await request.post(`/api/accounts/${account.id}/requests`, {
    data: { name: '代理请求', enabled: true, curl: "curl 'http://127.0.0.1:1/check?proxy=on'" },
  })).json()
  const run = await (await request.post('/api/runs', {
    data: { scope: 'request', id: created.id, force: false, key: 'proxy-browser-fixture' },
  })).json()
  await expect.poll(async () => {
    const batch = await (await request.get(`/api/batches/${run.batchIds[0]}`)).json()
    return batch.items[0].status
  }).toBe('success')
  const received = await (await request.get('http://127.0.0.1:18081/received')).json()
  expect(received.at(-1).url).toBe('http://127.0.0.1:1/check?proxy=on')
  const current = await (await request.get('/api/settings')).json()
  const restored = await request.put('/api/settings', { data: { ...previous, version: current.version } })
  expect(restored.ok()).toBe(true)
})
