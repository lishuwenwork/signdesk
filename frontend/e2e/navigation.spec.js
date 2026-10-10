import { test, expect } from '@playwright/test'

const routes = [
  { path: '/', title: '今日概览' },
  { path: '/platforms', title: '平台与账号' },
  { path: '/schedules', title: '定时计划' },
  { path: '/runs', title: '执行记录' },
  { path: '/settings', title: '设置与备份' },
]

for (const width of [1440, 390]) {
  test(`compact rail or bottom navigation preserves all routes and status at ${width}px`, async ({ page }, info) => {
    await page.route('**/api/system/status', (route) => route.fulfill({ json: { ready: true, paused: true } }))
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/#/platforms')
    const navigation = page.getByRole('navigation', { name: '主导航', exact: true })
    await expect(navigation).toBeVisible()
    await expect(navigation.getByRole('link')).toHaveCount(5)
    await expect(page.locator('.page-status')).toContainText('定时已暂停')
    await expect(page.locator('.nav-toggle, .topbar')).toHaveCount(0)
    for (const target of routes) {
      const link = navigation.getByRole('link', { name: target.title, exact: true })
      await expect(link).toHaveAttribute('title', target.title)
      await link.focus()
      await link.press('Enter')
      await expect(page).toHaveURL(new RegExp(`#${target.path}$`))
      await expect(page.getByRole('heading', { name: target.title, level: 1, exact: true })).toBeVisible()
      await expect(link).toHaveAttribute('aria-current', 'page')
      await expect(navigation.locator('a.selected')).toHaveCount(1)
      await expect(page.locator('.page-status')).toBeVisible()
    }
    if (width === 390) {
      await expect(page.locator('main')).toHaveCSS('margin-left', '0px')
      await expect(page.locator('.brand')).toBeHidden()
      const bounds = await page.locator('.sidebar').boundingBox()
      expect(Math.round(bounds.width)).toBe(width)
      expect(Math.round(bounds.y + bounds.height)).toBe(1000)
    } else {
      await expect(page.locator('.sidebar')).toHaveCSS('width', '84px')
      await expect(page.locator('main')).toHaveCSS('margin-left', '84px')
      await expect(page.locator('.brand')).toBeVisible()
    }
    await page.screenshot({ path: info.outputPath(`navigation-${width}.png`), fullPage: true, animations: 'disabled' })
    for (const boundary of [1241, 1240, 1021, 1020, 731, 730]) {
      await page.setViewportSize({ width: boundary, height: 1000 })
      await expect(navigation).toBeVisible()
      await expect(navigation.getByRole('link')).toHaveCount(5)
      const margin = boundary <= 730 ? '0px' : boundary <= 1020 ? '74px' : boundary <= 1240 ? '76px' : '84px'
      await expect(page.locator('main')).toHaveCSS('margin-left', margin)
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(boundary + 1)
    }
    await page.setViewportSize({ width, height: 1000 })
    await page.unroute('**/api/system/status')
    await page.route('**/api/system/status', (route) => route.abort())
    await expect(page.locator('.connection-alert')).toBeVisible()
    await expect(page.locator('.page-status')).toBeVisible()
    await expect(navigation).toBeVisible()
    await expect(navigation.getByRole('link')).toHaveCount(5)
  })
}
