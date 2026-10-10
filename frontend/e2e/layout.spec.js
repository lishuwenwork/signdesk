import { test, expect } from '@playwright/test'

const pages = [
  { path: '/', title: '今日概览', endpoint: '/api/dashboard', action: '执行全部平台' },
  { path: '/platforms', title: '平台与账号', endpoint: '/api/platforms', action: '＋ 新增平台' },
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
  test(`compact page headings, navigation and conditional status on ${viewport.name}`, async ({ page, request, baseURL }) => {
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
      await expect(page.locator('.brand')).toBeVisible()
      await expect(page.locator('.sidebar-footer small')).toHaveText('v0.1.0')
      if (viewport.name === 'mobile') await expect(page.locator('.sidebar-footer')).toBeHidden()
      else await expect(page.locator('.sidebar-footer')).toContainText('服务运行中')

      const navigation = page.getByRole('navigation', { name: '主导航', exact: true })
      await expect(navigation).toBeVisible()
      await expect(navigation.getByRole('link')).toHaveCount(5)
      for (const target of pages) {
        const responsePromise = page.waitForResponse((response) =>
          new URL(response.url()).pathname === target.endpoint && response.request().method() === 'GET',
        )
        const link = navigation.getByRole('link', { name: target.title, exact: true })
        await link.click()
        const response = await responsePromise
        expect(response.ok()).toBe(true)
        await expect(page).toHaveURL(new RegExp(`#${target.path}$`))
        await expect(link).toHaveClass(/selected/)
        await expect(navigation.locator('a.selected')).toHaveCount(1)

        const heading = page.locator('.page-heading')
        await expect(heading).toHaveCount(1)
        await expect(page.locator('h1')).toHaveCount(1)
        await expect(page.getByRole('heading', { name: target.title, level: 1, exact: true })).toBeVisible()
        await expect(heading.locator(':scope > h1')).toHaveText(target.title)
        await expect(heading.locator('.muted')).toHaveCount(0)
        await expect(heading).toHaveCSS('margin-bottom', viewport.name === 'mobile' ? '16px' : '20px')
        await expect(heading.locator('h1')).toHaveCSS('margin-bottom', '0px')
        await expect(page.locator('.nav-label, .topbar, .topbar-info, .private-pill, .divider')).toHaveCount(0)
        await expect(page.locator('.app-layout')).not.toContainText(/工作空间|个人 Web 工具|个人任务工作台/)
        await expect(page.locator('.page-status')).toHaveCount(0)
        await expect(page.locator('.page-content > .page-heading:first-child')).toHaveCount(1)

        if (target.action) {
          await expect(heading.getByRole('button', { name: target.action, exact: true })).toBeVisible()
        } else {
          await expect(page.getByRole('button', { name: '保存设置', exact: true })).toBeVisible()
          await expect(page.getByRole('button', { name: '导出配置', exact: true })).toBeVisible()
          await expect(page.getByRole('button', { name: '预览导入', exact: true })).toBeVisible()
          await expect(page.getByLabel('选择备份文件', { exact: true })).toBeVisible()
          await expect(page.locator('.page-content > .inline-info')).toContainText('配置保存在服务器，运行数据独立于程序包。')
        }
        if (target.path === '/runs') {
          const resultNote = page.locator('.panel > .filters + .muted.small')
          await expect(resultNote).toHaveText('业务结果单独判断，不将 HTTP 200 直接视为成功。')
          await expect(resultNote).toHaveCSS('margin-bottom', '12px')
          await expect(page.locator('.panel > .filters + .muted.small + .el-table')).toBeVisible()
        }
        await expectNoOverflow(page, viewport.width)
      }

      await saveSettings(request, { ...await readSettings(request), paused: true })
      const status = page.locator('.page-content > .page-status')
      await expect(status).toBeVisible()
      await expect(status.locator('.el-tag')).toHaveText('定时已暂停')
      await expect(status).toHaveCSS('justify-content', 'flex-start')
      await expect(status).toHaveCSS('margin-bottom', '12px')
      await expect(page.locator('.page-content > .page-status + .page-heading')).toHaveCount(1)
      await expectNoOverflow(page, viewport.width)

      await saveSettings(request, { ...await readSettings(request), paused: false })
      await expect(status).toHaveCount(0)
      await expect(page.locator('.page-content > .page-heading:first-child')).toHaveCount(1)

      await page.route('**/api/system/status', (route) => route.abort())
      await expect(page.locator('.connection-alert')).toBeVisible()
      await expect(page.locator('.connection-alert')).toHaveAttribute('role', 'alert')
      await expect(page.locator('.connection-alert')).toContainText('后端连接暂不可用，保存和执行需要 Spring Boot 服务运行')
      await page.unroute('**/api/system/status')
      await expect(page.locator('.connection-alert')).toHaveCount(0)
    } finally {
      // Keep every original setting, including proxy fields, but use the latest optimistic-lock version.
      const latest = await readSettings(request)
      await saveSettings(request, { ...original, version: latest.version })
      const restored = await readSettings(request)
      expect(restored).toEqual({ ...original, version: restored.version })
      expect(restored.version).toBeGreaterThan(latest.version)
    }
  })
}
