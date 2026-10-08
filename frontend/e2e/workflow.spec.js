import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'

test('manage, import, send, update, schedule, and export through the real web UI', async ({
  page,
  request,
}, info) => {
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/#/platforms')
  await page.getByRole('button', { name: '＋ 新增平台' }).click()
  await page.getByPlaceholder('例如：平台 A').fill('回显测试平台')
  await page.getByRole('button', { name: '保存平台', exact: true }).click()
  await expect(page.getByRole('heading', { name: '回显测试平台' })).toBeVisible()
  await page.getByRole('button', { name: '＋ 添加账号' }).click()
  await page.getByPlaceholder('例如：主账号').fill('测试账号 A')
  await page.getByRole('button', { name: '保存账号', exact: true }).click()
  await page.getByRole('button', { name: '＋ 添加请求' }).click()
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
  await expect(page.locator('.request-row')).toContainText('每日签到')
  await page.getByRole('button', { name: '执行', exact: true }).click()
  await page.getByRole('link', { name: '执行记录', exact: true }).click()
  await expect(page.locator('.el-table__body-wrapper')).toContainText('成功')
  const received = await (await request.get('http://127.0.0.1:18081/received')).json()
  expect(received[0]).toMatchObject({
    url: '/check?a=%2f&x=1&x=2',
    method: 'POST',
    cookie: 'browser-test=one',
    device: 'device-A',
    body: '{"value":"original"}',
  })
  await page.getByRole('button', { name: '详情' }).first().click()
  await expect(page.locator('.el-drawer')).toContainText('命中成功规则')
  await page.locator('.el-drawer__close-btn').click()
  await page.getByRole('link', { name: '平台与账号', exact: true }).click()
  await page.getByRole('button', { name: '更多 ▾' }).click()
  await page.getByText('更新 cURL', { exact: true }).click()
  await page
    .getByRole('textbox', { name: '完整 cURL', exact: true })
    .fill(`curl 'http://127.0.0.1:18081/check' -H 'Cookie: browser-test=two' --data-raw 'second-body'`)
  await page.getByRole('button', { name: '解析预览' }).click()
  await page.getByRole('button', { name: '保存新版本' }).click()
  await expect(page.locator('.request-row')).toContainText('版本 2')
  await page.getByRole('button', { name: '更多 ▾' }).click()
  await page.getByText('重新执行（忽略当日标记）', { exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认', exact: true }).click()
  await expect
    .poll(async () => (await (await request.get('http://127.0.0.1:18081/received')).json()).length)
    .toBe(2)
  await page.getByRole('link', { name: '定时计划', exact: true }).click()
  await page.getByRole('button', { name: '编辑', exact: true }).click()
  await page.getByRole('dialog').locator('.el-switch').first().click()
  await page.getByRole('button', { name: '保存计划', exact: true }).click()
  await expect(page.locator('.el-table__body-wrapper')).toContainText('09:00')
  const plans = await (await request.get('/api/schedules')).json()
  expect(plans[0].spec.enabled).toBe(true)
  await page.getByRole('link', { name: '设置与备份', exact: true }).click()
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出配置', exact: true }).click()
  const download = await downloadPromise
  const filename = info.outputPath('export.json')
  await download.saveAs(filename)
  const text = await readFile(filename, 'utf8')
  expect(text).not.toContain('browser-test')
  expect(text).not.toContain('second-body')
  expect(text).not.toContain('18081')
  await page.getByText('包含完整请求（必须密码加密）', { exact: true }).click()
  await expect(page.getByRole('checkbox', { name: '包含完整请求（必须密码加密）' })).toBeChecked()
  await page.getByPlaceholder('设置 10～200 字符备份密码').fill('browser-only-backup-password')
  const encryptedDownload = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出加密备份', exact: true }).click()
  const encryptedFilename = info.outputPath('encrypted-backup.json')
  await (await encryptedDownload).saveAs(encryptedFilename)
  expect(await readFile(encryptedFilename, 'utf8')).not.toContain('browser-test')
  await page.getByPlaceholder('加密备份密码，普通配置留空').fill('browser-only-backup-password')
  await page.getByLabel('选择备份文件').setInputFiles(encryptedFilename)
  await page.getByRole('button', { name: '预览导入', exact: true }).click()
  await expect(page.getByRole('button', { name: '确认替换并恢复', exact: true })).toBeVisible()
  await expect(
    page.locator('.inline-info').filter({ hasText: '1 个平台、1 个账号、1 个请求' }),
  ).toContainText('包含完整请求')
  await page.getByRole('link', { name: '平台与账号', exact: true }).click()
  await expect(page.locator('.el-message')).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('platform-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('heading', { name: '平台与账号', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
  await page.screenshot({ path: info.outputPath('platform-mobile.png'), fullPage: true })
  expect(errors).toEqual([])
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
  await page.goto('/#/settings')
  await page.getByRole('combobox', { name: '请求代理模式' }).press('ArrowDown')
  await page.getByRole('option', { name: 'HTTP 代理', exact: true }).click()
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
