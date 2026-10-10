import { test, expect } from '@playwright/test'

const platforms = new Set()
test.afterEach(async ({ playwright }) => {
  const cleanup = await playwright.request.newContext({ baseURL: 'http://127.0.0.1:18080' })
  try {
    for (const id of platforms) {
      await cleanup.delete(`/api/platforms/${id}`, { data: {} })
      platforms.delete(id)
    }
  } finally { await cleanup.dispose() }
})

async function seed(request, name) {
  const platform = await (await request.post('/api/platforms', {
    data: { name, enabled: true, version: 1 },
  })).json()
  expect(platform.id).toBeTruthy()
  platforms.add(platform.id)
  const account = await (await request.post(`/api/platforms/${platform.id}/accounts`, {
    data: { alias: '本地响应账号', enabled: true, version: 1 },
  })).json()
  expect(account.id).toBeTruthy()
  return account.id
}

async function run(request, accountId, name, path, options = '') {
  const created = await (await request.post(`/api/accounts/${accountId}/requests`, {
    data: { name, curl: `curl 'http://127.0.0.1:18081${path}' ${options}`, enabled: true },
  })).json()
  expect(created.id).toBeTruthy()
  const accepted = await request.post('/api/runs', {
    data: { scope: 'request', id: created.id, force: false, key: `response-browser-${created.id}` },
  })
  expect(accepted.status()).toBe(202)
  const batchId = (await accepted.json()).batchIds[0]
  await expect.poll(async () => (await (await request.get(`/api/batches/${batchId}`)).json()).status).toBe('completed')
  return (await (await request.get(`/api/batches/${batchId}`)).json()).items[0].id
}

async function openDetail(page, name, { responseTab = true } = {}) {
  await page.locator('.log-table tbody tr[data-run-id]').filter({ hasText: name })
    .getByRole('button', { name: '详情', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '执行记录详情', exact: true })
  await expect(drawer).toBeVisible()
  // Tabs are rendered after detail arrives. A deliberately held response must not block closing.
  if (responseTab) {
    await expect(drawer.getByRole('button', { name: '执行信息', exact: true })).toHaveClass(/active/)
    await drawer.getByRole('button', { name: '响应体', exact: true }).click()
  }
}
async function closeDetail(page) {
  await page.locator('.el-drawer__close-btn').click()
  await expect(page.getByTestId('response-body')).toHaveCount(0)
}

test('response details show raw text, errors, empty, binary, partial and truncated bodies safely', async ({ page, request }, info) => {
  test.setTimeout(90000)
  const account = await seed(request, '响应详情测试平台')
  const jsonId = await run(request, account, '响应原文', '/responses/json')
  await run(request, account, '错误响应', '/responses/error')
  await run(request, account, '空响应', '/responses/empty')
  await run(request, account, '二进制响应', '/responses/binary')
  await run(request, account, '超大响应', '/responses/large')
  await run(request, account, '部分响应', '/responses/partial', '--max-time 1')
  const logs = await (await request.get('/api/runs')).text()
  expect(logs).not.toContain('response-fixture-secret')
  expect(logs).not.toContain('fixture-error-response')
  let detailRequests = 0, listRequests = 0
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  page.on('request', (r) => {
    const url = new URL(r.url())
    if (url.pathname === `/api/runs/${jsonId}`) detailRequests++
    if (url.pathname === '/api/runs' && url.search) listRequests++
  })
  await page.goto('/#/runs')
  await openDetail(page, '响应原文')
  await expect(page.getByTestId('response-body')).toContainText('响应体 response-fixture-secret')
  await expect(page.getByTestId('response-body')).toContainText('<img src=x onerror=')
  await expect(page.getByTestId('response-body').locator('img')).toHaveCount(0)
  expect(await page.evaluate(() => !!window.__responseExecuted)).toBe(false)
  const before = listRequests
  await expect.poll(() => listRequests).toBeGreaterThan(before)
  expect(detailRequests).toBe(1)
  await closeDetail(page)

  await openDetail(page, '错误响应')
  await expect(page.getByTestId('response-body')).toContainText('<div>fixture-error-response</div>')
  await expect(page.locator('.el-drawer')).toContainText('待确认')
  expect(await page.evaluate(() => !!window.__responseExecuted)).toBe(false)
  await closeDetail(page)
  await openDetail(page, '空响应')
  await expect(page.locator('.el-drawer')).toContainText('响应体为空（0 字节）')
  await expect(page.getByTestId('response-body')).toHaveCount(0)
  await closeDetail(page)
  await openDetail(page, '二进制响应')
  await expect(page.locator('.el-drawer')).toContainText('Base64')
  await expect(page.getByTestId('response-body')).toHaveText('AAEC/w==')
  await closeDetail(page)
  await openDetail(page, '超大响应')
  await expect(page.locator('.el-drawer')).toContainText('以下内容已截断')
  await expect.poll(() => page.getByTestId('response-body').evaluate((el) => el.textContent.length)).toBe(1048576)
  await closeDetail(page)
  await openDetail(page, '部分响应')
  await expect(page.locator('.el-drawer')).toContainText('并非完整响应')
  await expect(page.getByTestId('response-body')).toHaveText('partial-response-fixture-secret')
  await closeDetail(page)
  await openDetail(page, '响应原文')
  await expect(page.getByTestId('response-body')).toContainText('响应体 response-fixture-secret')
  const drawer = page.locator('.el-drawer')
  await expect.poll(async () => Math.round((await drawer.boundingBox()).x)).toBe(870)
  await expect.poll(async () => Math.round((await drawer.boundingBox()).width)).toBe(570)
  await page.screenshot({ path: info.outputPath('response-detail-desktop.png'), fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(async () => Math.round((await drawer.boundingBox()).x)).toBe(0)
  await expect.poll(async () => Math.round((await drawer.boundingBox()).width)).toBe(390)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
  await page.screenshot({ path: info.outputPath('response-detail-mobile.png'), fullPage: true, animations: 'disabled' })
  await closeDetail(page)
  expect(errors).toEqual([])
})

test('closing a detail clears its body and a late response cannot replace another record', async ({ page, request }) => {
  const account = await seed(request, '响应隔离测试平台')
  const jsonId = await run(request, account, '迟到的响应', '/responses/json')
  await run(request, account, '当前空响应', '/responses/empty')
  let release, started, finished
  const gate = new Promise((resolve) => { release = resolve })
  const ready = new Promise((resolve) => { started = resolve })
  const done = new Promise((resolve) => { finished = resolve })
  await page.route(`**/api/runs/${jsonId}`, async (route) => {
    const response = await route.fetch()
    started()
    await gate
    await route.fulfill({ response })
    finished()
  })
  try {
    let listRequests = 0
    page.on('request', (r) => { if (new URL(r.url()).pathname === '/api/runs') listRequests++ })
    await page.goto('/#/runs')
    await openDetail(page, '迟到的响应', { responseTab: false })
    await ready
    await closeDetail(page)
    await openDetail(page, '当前空响应')
    await expect(page.locator('.el-drawer')).toContainText('响应体为空（0 字节）')
    release()
    await done
    const before = listRequests
    await expect.poll(() => listRequests).toBeGreaterThan(before)
    await expect(page.locator('.el-drawer')).toContainText('响应体为空（0 字节）')
    await expect(page.getByTestId('response-body')).toHaveCount(0)
    await expect(page.locator('.el-drawer')).not.toContainText('response-fixture-secret')
    await closeDetail(page)
  } finally {
    release()
    if (!page.isClosed()) await page.unrouteAll({ behavior: 'ignoreErrors' })
  }
})
