import { test, expect } from '@playwright/test'

const pages = [
  { path: '/', title: '今日概览', endpoint: '/api/dashboard', action: '执行全部平台' },
  { path: '/platforms', title: '平台与账号', endpoint: '/api/platforms', action: '新增平台' },
  { path: '/schedules', title: '定时计划', endpoint: '/api/schedules', action: '暂停全局定时' },
  { path: '/runs', title: '执行记录', endpoint: '/api/runs', action: '刷新记录' },
  { path: '/settings', title: '设置与备份', endpoint: '/api/settings' },
]
const viewports = [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'mobile', width: 390, height: 844 },
]

async function readSettings(request) {
  const response = await request.get('/api/settings')
  expect(response.ok()).toBe(true)
  return response.json()
}

async function saveSettings(request, settings) {
  const response = await request.put('/api/settings', { data: settings })
  expect(response.ok()).toBe(true)
  expect(await response.json()).toEqual({ saved: true })
}

async function expectNoOverflow(page, width) {
  expect(await page.evaluate(() => Math.max(
    document.documentElement.scrollWidth, document.body.scrollWidth,
  ))).toBeLessThanOrEqual(width + 1)
}

for (const viewport of viewports) {
  test(`page headings, five-route navigation and conditional status on ${viewport.name}`, async ({ page, request, baseURL }) => {
    expect(baseURL).toBe('http://127.0.0.1:18080')
    const original = await readSettings(request)
    try {
      await saveSettings(request, { ...original, paused: false })
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      const readyResponse = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/system/status')
      await page.goto('/#/settings')
      const ready = await readyResponse
      expect(ready.ok()).toBe(true)
      expect(await ready.json()).toMatchObject({ ready: true, paused: false })
      await expect(page.locator('.connection-alert')).toHaveCount(0)
      if (viewport.name === 'mobile') {
        await expect(page.locator('.brand')).toBeHidden()
        await expect(page.locator('.sidebar-footer')).toBeHidden()
      } else {
        await expect(page.locator('.brand')).toBeVisible()
        await expect(page.locator('.sidebar-footer')).toContainText('服务在线')
      }

      const navigation = page.getByRole('navigation', { name: '主导航', exact: true })
      await expect(navigation).toBeVisible()
      await expect(navigation.getByRole('link')).toHaveCount(5)
      for (const target of pages) {
        const responsePromise = page.waitForResponse((response) =>
          new URL(response.url()).pathname === target.endpoint && response.request().method() === 'GET',
        )
        const link = navigation.getByRole('link', { name: target.title, exact: true })
        await link.click()
        expect((await responsePromise).ok()).toBe(true)
        await expect(page).toHaveURL(new RegExp(`#${target.path}$`))
        await expect(link).toHaveClass(/selected/)
        await expect(link).toHaveAttribute('aria-current', 'page')
        await expect(navigation.locator('a.selected')).toHaveCount(1)

        const heading = page.locator('.page-heading')
        await expect(heading).toHaveCount(1)
        await expect(page.locator('h1')).toHaveCount(1)
        await expect(heading.getByRole('heading', { name: target.title, level: 1, exact: true })).toBeVisible()
        await expect(page.locator('.nav-toggle, .topbar, .topbar-info, .private-pill')).toHaveCount(0)
        await expect(page.locator('.app-layout')).not.toContainText(/工作空间|个人 Web 工具|个人任务工作台/)
        await expect(page.locator('.page-status')).toHaveCount(0)

        if (target.action) {
          await expect(heading.getByRole('button', { name: target.action, exact: true })).toBeVisible()
        } else {
          // Settings opens execution preferences; backup and about are separate, addressable tabs.
          await expect(page.getByRole('button', { name: '保存设置', exact: true })).toBeVisible()
          await page.goto('/#/settings?tab=backup')
          await expect(page.getByRole('button', { name: '导出配置', exact: true })).toBeVisible()
          await expect(page.getByRole('button', { name: '预览导入', exact: true })).toBeVisible()
          await expect(page.getByLabel('选择备份文件', { exact: true })).toBeVisible()
          await expectNoOverflow(page, viewport.width)
          await page.goto('/#/settings?tab=about')
          await expect(page.getByText(/配置保存在服务器，运行数据独立于程序包/)).toBeVisible()
        }
        if (target.path === '/runs') {
          await expect(page.locator('.run-filter-note')).toHaveText('HTTP 200 不自动代表业务成功')
          await expect(page.locator('.log-table')).toBeVisible()
        }
        await expectNoOverflow(page, viewport.width)
      }

      await saveSettings(request, { ...await readSettings(request), paused: true })
      const status = page.locator('.page-content > .page-status')
      await expect(status).toBeVisible()
      await expect(status).toContainText('定时已暂停')
      await expect(status).toContainText('手动执行和活动队列不受影响')
      await expectNoOverflow(page, viewport.width)

      await saveSettings(request, { ...await readSettings(request), paused: false })
      await expect(status).toHaveCount(0)
      await expect(page.getByRole('heading', { name: '设置与备份', level: 1, exact: true })).toBeVisible()

      await page.route('**/api/system/status', (route) => route.abort())
      await expect(page.locator('.connection-alert')).toBeVisible()
      await expect(page.locator('.connection-alert')).toHaveAttribute('role', 'alert')
      await expect(page.locator('.connection-alert')).toContainText('后端连接暂不可用，保存和执行需要服务运行')
      await page.unroute('**/api/system/status')
      await expect(page.locator('.connection-alert')).toHaveCount(0)
    } finally {
      // Preserve all original settings, including proxy fields, with the latest optimistic-lock version.
      const latest = await readSettings(request)
      await saveSettings(request, { ...original, version: latest.version })
      const restored = await readSettings(request)
      expect(restored).toEqual({ ...original, version: restored.version })
      expect(restored.version).toBeGreaterThan(latest.version)
    }
  })
}
