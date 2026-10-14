import { test, expect } from './fixtures.js'
import { todayRunDateRange } from '../src/runDateFilter.js'

test.use({ timezoneId: 'America/Los_Angeles' })

async function selectDates(page, start, end) {
  await page.getByPlaceholder('开始日期', { exact: true }).fill(start)
  await page.getByPlaceholder('结束日期', { exact: true }).fill(end)
  await page.getByPlaceholder('结束日期', { exact: true }).press('Enter')
  await page.getByRole('heading', { name: '执行记录', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`startDate=${start}&endDate=${end}`))
}

for (const width of [1440, 390]) {
  test(`date-only run filters default to Beijing today and support ranges, clear and reset at ${width}px`, async ({ page, request }, info) => {
    const [today] = todayRunDateRange()
    const [yesterday] = todayRunDateRange(new Date(Date.now() - 86400000))
    await page.clock.setFixedTime(new Date(`${today}T00:00:00+08:00`))
    await page.setViewportSize({ width, height: 1000 })
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    const platformResponse = await request.post('/api/platforms', { data: { name: `日期筛选 ${width}`, enabled: true } })
    expect(platformResponse.status()).toBe(200)
    const platform = (await platformResponse.json()).id
    try {
      const accountResponse = await request.post(`/api/platforms/${platform}/accounts`, { data: { alias: '日期测试账号', enabled: true } })
      expect(accountResponse.status()).toBe(200)
      const account = (await accountResponse.json()).id
      const created = await request.post(`/api/accounts/${account}/requests`, { data: {
        name: '今天的执行', curl: "curl 'http://127.0.0.1:18081/date-filter'", enabled: true,
      } })
      expect(created.status()).toBe(200)
      const accepted = await request.post('/api/runs', { data: {
        scope: 'request', id: (await created.json()).id, force: false, key: `run-date-${width}`,
      } })
      expect(accepted.status()).toBe(202)
      const batch = (await accepted.json()).batchIds[0]
      await expect.poll(async () => (await (await request.get(`/api/batches/${batch}`)).json()).status).toBe('completed')
      const queries = []
      page.on('request', outgoing => {
        const url = new URL(outgoing.url())
        if (url.pathname === '/api/runs' && outgoing.method() === 'GET') queries.push(Object.fromEntries(url.searchParams))
      })
      await page.goto(`/#/runs?platformId=${platform}`)
      const start = page.getByPlaceholder('开始日期', { exact: true })
      const end = page.getByPlaceholder('结束日期', { exact: true })
      await expect(start).toHaveValue(today)
      await expect(end).toHaveValue(today)
      await expect(page.locator('.log-table tbody tr')).toHaveCount(1)
      await expect.poll(() => queries.at(-1)?.startDate).toBe(today)
      expect(queries.at(-1).endDate).toBe(today)
      await start.click()
      const picker = page.locator('.run-date-popper:visible')
      await expect(picker.locator('.el-date-range-picker')).toBeVisible()
      await expect(picker.locator('.el-time-spinner')).toHaveCount(0)
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width + 1)
      const bounds = await picker.boundingBox()
      expect(bounds.x).toBeGreaterThanOrEqual(-1)
      expect(bounds.x + bounds.width).toBeLessThanOrEqual(width + 1)
      const panels = picker.locator('.el-date-range-picker__content')
      await expect(panels).toHaveCount(2)
      const leftBounds = await panels.nth(0).boundingBox()
      const rightBounds = await panels.nth(1).boundingBox()
      for (const panel of [leftBounds,rightBounds]) {
        expect(panel.x).toBeGreaterThanOrEqual(bounds.x - 1)
        expect(panel.x + panel.width).toBeLessThanOrEqual(bounds.x + bounds.width + 1)
      }
      if (width === 390) {
        expect(rightBounds.y).toBeGreaterThanOrEqual(leftBounds.y + leftBounds.height - 1)
        expect(await picker.evaluate(el => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(1)
      } else {
        expect((await page.locator('.run-date-range').boundingBox()).width).toBeLessThanOrEqual(280)
      }
      await page.screenshot({ path: info.outputPath(`run-date-picker-${width}.png`), fullPage: true, animations: 'disabled' })
      const todayCell = panels.nth(0).locator('td.available:not(.prev-month):not(.next-month)')
        .filter({ hasText: new RegExp(`^${Number(today.slice(-2))}$`) })
      await todayCell.click()
      await todayCell.click()
      await expect(start).toHaveValue(today)
      await expect(end).toHaveValue(today)
      await selectDates(page, yesterday, yesterday)
      await expect(page.locator('.log-table tbody tr')).toHaveCount(0)
      await expect(page.locator('.pagination-copy')).toContainText('共 0 条记录')
      await selectDates(page, yesterday, today)
      await expect(page.locator('.log-table tbody tr')).toHaveCount(1)
      await page.reload()
      await expect(start).toHaveValue(yesterday)
      await expect(end).toHaveValue(today)
      await expect(page.locator('.log-table tbody tr')).toHaveCount(1)
      await page.locator('.run-date-range').hover()
      await page.locator('.run-date-range .el-range__close-icon').click()
      await expect(start).toHaveValue('')
      await expect(end).toHaveValue('')
      await expect.poll(() => queries.at(-1)?.startDate).toBe('')
      expect(queries.at(-1).endDate).toBe('')
      await page.reload()
      await expect(start).toHaveValue('')
      await expect(end).toHaveValue('')
      await page.getByRole('button', { name: '重置筛选', exact: true }).click()
      await expect(start).toHaveValue(today)
      await expect(end).toHaveValue(today)
      await expect.poll(() => queries.at(-1)?.platformId).toBe('')
      expect(queries.at(-1).startDate).toBe(today)
      expect(queries.at(-1).endDate).toBe(today)
      await expect(page.getByRole('button', { name: '重置筛选', exact: true })).toHaveCount(0)
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width + 1)
      await page.screenshot({ path: info.outputPath(`run-date-default-${width}.png`), fullPage: true, animations: 'disabled' })
      expect(errors).toEqual([])
    } finally {
      expect((await request.delete(`/api/platforms/${platform}`, { data: {} })).status()).toBe(200)
    }
  })
}

test('changing dates resets pagination and retains platform, result and source filters', async ({ page }) => {
  const queries = []
  await page.route('**/api/runs?*', async route => {
    const query = Object.fromEntries(new URL(route.request().url()).searchParams)
    queries.push(query)
    const [today] = todayRunDateRange()
    await route.fulfill({ json: {
      items: [{ id: `fixture-${query.page}`, platformId: 'fixture-platform', platformName: '分页测试平台',
        requestId: 'fixture-request', requestName: '分页测试请求', status: 'failed', source: 'manual',
        createdAt: `${today}T01:00:00Z` }],
      total: 25, page: Number(query.page), size: Number(query.size),
    } })
  })
  const [today] = todayRunDateRange()
  const [yesterday] = todayRunDateRange(new Date(Date.now() - 86400000))
  await page.goto('/#/runs?platformId=fixture-platform&status=failed&source=manual')
  await expect(page.locator('.el-pager li.number').filter({ hasText: /^2$/ })).toBeVisible()
  await page.locator('.el-pager li.number').filter({ hasText: /^2$/ }).click()
  await expect.poll(() => queries.at(-1)?.page).toBe('2')
  await page.getByRole('textbox', { name: '搜索当前页记录', exact: true }).fill('分页')
  await selectDates(page, yesterday, today)
  await expect.poll(() => queries.at(-1)).toMatchObject({
    platformId: 'fixture-platform', status: 'failed', source: 'manual',
    startDate: yesterday, endDate: today, page: '1', size: '20',
  })
  await expect(page.getByRole('textbox', { name: '搜索当前页记录', exact: true })).toHaveValue('')
  await expect(page.locator('.el-pager li.is-active')).toHaveText('1')
})
