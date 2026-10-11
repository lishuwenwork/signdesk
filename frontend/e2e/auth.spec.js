import { test, expect } from '@playwright/test'
import { authenticate, authenticatedContext } from './fixtures.js'

async function signIn(page) {
  await page.getByLabel('用户名', { exact: true }).fill('admin')
  await page.getByLabel('密码', { exact: true }).fill('123456')
  await page.getByRole('button', { name: '登录', exact: true }).click()
}

for (const width of [1440, 390]) {
  test(`login protects deep links, supports refresh and logout at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 844 })
    await page.goto('/#/settings?tab=backup')
    await expect(page.getByRole('heading', { name: '登录签行', exact: true })).toBeVisible()
    await expect(page.locator('.app-layout')).toHaveCount(0)
    await expect(page.getByLabel('密码', { exact: true })).toHaveValue('')
    await expect(page.getByText(/注册|验证码/)).toHaveCount(0)
    expect((await page.request.get('/api/settings')).status()).toBe(401)
    const before = await page.request.get('/api/auth/session')
    expect(await before.json()).toEqual({ authenticated: false, username: null })
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width + 1)
    await page.screenshot({ path: info.outputPath(`login-${width}.png`), fullPage: true })
    await page.getByLabel('用户名', { exact: true }).fill('admin')
    await page.getByLabel('密码', { exact: true }).fill('incorrect-fixture')
    await page.getByRole('button', { name: '显示密码', exact: true }).click()
    await expect(page.getByLabel('密码', { exact: true })).toHaveAttribute('type', 'text')
    await page.getByRole('button', { name: '隐藏密码', exact: true }).click()
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('用户名或密码错误')
    await expect(page.getByLabel('密码', { exact: true })).toHaveValue('')
    await signIn(page)
    await expect(page).toHaveURL(/#\/settings\?tab=backup$/)
    await expect(page.getByRole('heading', { name: '设置与备份', exact: true })).toBeVisible()
    await page.reload()
    await expect(page.getByRole('heading', { name: '设置与备份', exact: true })).toBeVisible()
    expect((await page.request.get('/api/settings')).status()).toBe(200)
    const storage = await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }))
    expect(storage).not.toContain('123456')
    expect(storage).not.toContain('admin')
    expect(await page.evaluate(() => document.cookie)).not.toContain('JSESSIONID')
    // Build real in-app history; login/logout intentionally replace their current entry.
    await page.getByRole('link', { name: '执行记录', exact: true }).click()
    await expect(page.getByRole('heading', { name: '执行记录', exact: true })).toBeVisible()
    await page.getByRole('link', { name: '设置与备份', exact: true }).click()
    await expect(page.getByRole('heading', { name: '设置与备份', exact: true })).toBeVisible()
    await page.locator('.settings-heading').getByRole('button', { name: '退出登录', exact: true }).click()
    await expect(page).toHaveURL(/#\/login$/)
    await expect(page.locator('.app-layout')).toHaveCount(0)
    expect((await page.request.get('/api/settings')).status()).toBe(401)
    await page.goBack()
    await expect(page.getByRole('heading', { name: '登录签行', exact: true })).toBeVisible()
  })
}

test('a lost session clears sensitive drawers and prevents stale confirmations from blocking login', async ({ page, playwright }) => {
  const cleanup = await authenticatedContext(playwright)
  let platform
  try {
    await authenticate(page.request)
    platform = (await (await cleanup.post('/api/platforms', { data: { name: '登录失效隔离平台', enabled: true } })).json()).id
    const account = (await (await cleanup.post(`/api/platforms/${platform}/accounts`, { data: { alias: '隔离账号', enabled: true } })).json()).id
    const item = (await (await cleanup.post(`/api/accounts/${account}/requests`, { data: { name: '隔离请求', enabled: true, curl: "curl 'http://127.0.0.1:18081/check' -H 'Cookie: auth-drawer-fixture-private'" } })).json()).id
    await page.goto(`/#/platforms?platformId=${platform}&requestId=${item}&action=detail`)
    await expect(page.locator('.el-drawer .preview-code').first()).toContainText('auth-drawer-fixture-private')
    expect((await page.request.post('/api/auth/logout', { data: {} })).status()).toBe(200)
    await expect(page.getByRole('heading', { name: '登录签行', exact: true })).toBeVisible()
    await expect(page.locator('.el-drawer .preview-code').first()).toHaveCount(0)
    await expect(page.locator('.el-drawer, .el-overlay')).toHaveCount(0)
    expect(await page.locator('body').textContent()).not.toContain('auth-drawer-fixture-private')
    await signIn(page)
    await expect(page.locator('.app-layout')).toBeVisible()
    await page.goto('/#/settings')
    await page.getByRole('spinbutton', { name: '全局请求超时上限', exact: true }).fill('19')
    await page.getByRole('link', { name: '今日概览', exact: true }).click()
    await expect(page.locator('.el-message-box')).toBeVisible()
    await page.request.post('/api/auth/logout', { data: {} })
    await expect(page.getByRole('heading', { name: '登录签行', exact: true })).toBeVisible()
    await expect(page.locator('.el-message-box')).toHaveCount(0)
    await expect(page.locator('.settings-page, .app-layout')).toHaveCount(0)
  } finally {
    if (platform) expect((await cleanup.delete(`/api/platforms/${platform}`, { data: {} })).ok()).toBe(true)
    await cleanup.dispose()
  }
})

test('external return targets cannot redirect a successful login outside the app', async ({ page }) => {
  await page.goto('/#/login?redirect=https%3A%2F%2Fexample.invalid')
  await signIn(page)
  await expect(page).toHaveURL(/#\/$/)
  await expect(page.getByRole('heading', { name: '今日概览', exact: true })).toBeVisible()
})
