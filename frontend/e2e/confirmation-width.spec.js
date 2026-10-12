import { test, expect, authenticatedContext } from './fixtures.js'

const created = new Set()
test.afterEach(async ({ playwright }) => {
  const cleanup = await authenticatedContext(playwright)
  try {
    for (const id of created) {
      expect((await cleanup.delete(`/api/platforms/${id}`, { data: {} })).ok()).toBe(true)
      created.delete(id)
    }
  } finally { await cleanup.dispose() }
})
async function post(request, url, data) {
  const response = await request.post(url, { data })
  expect(response.ok()).toBe(true)
  return response.json()
}
async function batch(request, id) {
  const response = await request.get(`/api/batches/${id}`)
  expect(response.ok()).toBe(true)
  return response.json()
}
async function received(request) {
  return (await (await request.get('http://127.0.0.1:18081/received')).json()).length
}

for (const width of [1920, 1440, 390]) {
  test(`secondary confirmation stays compact and requires explicit consent at ${width}px`, async ({ page, request }, info) => {
    const platform = await post(request, '/api/platforms', { name: `确认框宽度-${width}`, enabled: true })
    created.add(platform.id)
    const account = await post(request, `/api/platforms/${platform.id}/accounts`, { alias: '本地确认账号', enabled: true })
    const item = await post(request, `/api/accounts/${account.id}/requests`, {
      name: '确认后才重新执行', curl: "curl 'http://127.0.0.1:18081/check'", enabled: true,
    })
    const initial = await post(request, '/api/runs', { scope: 'request', id: item.id, force: false, key: `confirmation-layout-${item.id}` })
    await expect.poll(async () => (await batch(request, initial.batchIds[0])).status).toBe('completed')
    const before = await received(request)
    const errors = []
    let writes = 0, submitted
    page.on('pageerror', error => errors.push(error.message))
    page.on('request', outgoing => {
      if (new URL(outgoing.url()).pathname === '/api/runs' && outgoing.method() === 'POST') {
        writes++
        submitted = outgoing.postDataJSON()
      }
    })
    await page.setViewportSize({ width, height: width === 390 ? 844 : 1000 })
    await page.goto(`/#/runs?platformId=${platform.id}`)
    await page.locator('.log-table tbody tr[data-run-id]').getByRole('button', { name: '详情', exact: true }).click()
    const drawer = page.getByRole('dialog', { name: '执行记录详情', exact: true })
    const rerun = drawer.getByRole('button', { name: '重新执行', exact: true })
    await expect(rerun).toBeEnabled()
    await rerun.click()
    const confirmation = page.locator('.el-message-box')
    await expect(confirmation).toContainText('重新执行会忽略今日完成和待确认标记，可能重复操作')
    await page.screenshot({ path: info.outputPath(`confirmation-width-${width}.png`), animations: 'disabled' })
    const bounds = await confirmation.boundingBox()
    await info.attach('confirmation-layout.json', { body: JSON.stringify({ viewport: width, bounds }), contentType: 'application/json' })
    expect(bounds.width).toBeLessThanOrEqual(Math.min(420, width - 24) + 1)
    expect(bounds.width).toBeGreaterThanOrEqual(Math.min(400, width - 40))
    expect(Math.abs(bounds.x + bounds.width / 2 - width / 2)).toBeLessThanOrEqual(1)
    expect(await confirmation.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width + 1)
    await expect(confirmation.getByRole('button', { name: '取消', exact: true })).toBeInViewport()
    await expect(confirmation.getByRole('button', { name: '确认', exact: true })).toBeInViewport()
    await confirmation.getByRole('button', { name: '取消', exact: true }).click()
    await expect(confirmation).toHaveCount(0)
    expect(writes).toBe(0)
    expect(await received(request)).toBe(before)
    await expect(drawer).toBeVisible()
    await rerun.click()
    await expect(confirmation).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(confirmation).toHaveCount(0)
    expect(writes).toBe(0)
    expect(await received(request)).toBe(before)
    await rerun.click()
    await expect(confirmation).toBeVisible()
    const accepted = page.waitForResponse(response => new URL(response.url()).pathname === '/api/runs' && response.request().method() === 'POST')
    await confirmation.getByRole('button', { name: '确认', exact: true }).click()
    const response = await accepted
    expect(response.status()).toBe(202)
    const result = await response.json()
    await expect.poll(async () => (await batch(request, result.batchIds[0])).status).toBe('completed')
    expect(writes).toBe(1)
    expect(submitted).toMatchObject({ scope: 'request', id: item.id, force: true })
    expect(await received(request)).toBe(before + 1)
    expect(errors).toEqual([])
  })
}
