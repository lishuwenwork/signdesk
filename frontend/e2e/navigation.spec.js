import { test, expect } from '@playwright/test'

for (const width of [1440, 390]) {
  test(`main navigation collapses without losing routes or status at ${width}px`, async ({ page }, info) => {
    await page.route('**/api/system/status', (route) => route.fulfill({ json: { ready: true, paused: true } }))
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/#/platforms')
    const navigation = page.getByRole('navigation', { name: '主导航', exact: true })
    await expect(navigation).toBeVisible()
    await expect(page.locator('.page-status')).toContainText('定时已暂停')
    const collapse = page.getByRole('button', { name: '收起菜单导航', exact: true })
    await collapse.focus()
    await collapse.press('Enter')
    const expand = page.getByRole('button', { name: '展开菜单导航', exact: true })
    await expect(expand).toHaveAttribute('aria-expanded', 'false')
    await expect(expand).toHaveAttribute('aria-controls', 'main-navigation')
    await expect(page.locator('.brand')).toBeVisible()
    await expect(page.locator('.page-status')).toBeVisible()
    if (width === 390) {
      await expect(navigation).toBeHidden()
      await expect(page.locator('main')).toHaveCSS('margin-left', '0px')
    } else {
      await expect(navigation).toBeVisible()
      await expect(page.locator('.sidebar')).toHaveCSS('width', '80px')
      await expect(page.locator('main')).toHaveCSS('margin-left', '80px')
      await expect(navigation.getByRole('link')).toHaveCount(5)
      for (const name of ['今日概览', '平台与账号', '定时计划', '执行记录', '设置与备份']) {
        const link = navigation.getByRole('link', { name, exact: true })
        await expect(link).toHaveAttribute('title', name)
        await link.click()
        await expect(page.getByRole('heading', { name, level: 1, exact: true })).toBeVisible()
        await expect(expand).toBeVisible()
      }
    }
    await page.screenshot({ path: info.outputPath(`navigation-collapsed-${width}.png`), fullPage: true, animations: 'disabled' })
    for (const boundary of [761, 760, 1100, 1101]) {
      await page.setViewportSize({ width: boundary, height: 1000 })
      await expect(expand).toBeVisible()
      await expect(page.locator('main')).toHaveCSS('margin-left', boundary <= 760 ? '0px' : '80px')
      if (boundary <= 760) await expect(navigation).toBeHidden()
      else await expect(navigation).toBeVisible()
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(boundary + 1)
    }
    await page.setViewportSize({ width, height: 1000 })
    await expand.click()
    await expect(navigation).toBeVisible()
    await expect(page.getByRole('button', { name: '收起菜单导航', exact: true })).toHaveAttribute('aria-expanded', 'true')
    await page.getByRole('button', { name: '收起菜单导航', exact: true }).click()
    await page.unroute('**/api/system/status')
    await page.route('**/api/system/status', (route) => route.abort())
    await expect(page.locator('.connection-alert')).toBeVisible()
    await expect(page.locator('.page-status')).toBeVisible()
    await expect(page.getByRole('button', { name: '展开菜单导航', exact: true })).toBeVisible()
  })
}
