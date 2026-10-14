import { test, expect } from '@playwright/test'

test('the tab icon is publicly served, renders the brand mark and survives login and navigation', async ({ page, request }) => {
  const response = await request.get('/favicon.svg')
  expect(response.status()).toBe(200)
  expect(response.headers()['content-type']).toMatch(/^image\/svg\+xml(?:;|$)/)
  await page.goto('/#/login')
  await expect(page.getByRole('heading', { name: '登录签行', exact: true })).toBeVisible()
  expect((await page.request.get('/api/platforms')).status()).toBe(401)
  const icon = page.locator('head link[rel="icon"]')
  await expect(icon).toHaveCount(1)
  await expect(icon).toHaveAttribute('href', '/favicon.svg')
  await expect(icon).toHaveAttribute('type', 'image/svg+xml')
  const pixels = await page.evaluate(async () => {
    const image = new Image()
    image.src = document.querySelector('link[rel="icon"]').href
    await image.decode()
    const canvas = document.createElement('canvas')
    canvas.width = canvas.height = 32
    const context = canvas.getContext('2d')
    context.drawImage(image, 0, 0, 32, 32)
    const data = context.getImageData(0, 0, 32, 32).data
    let lime = 0, dark = 0
    for (let i = 0; i < data.length; i += 4) {
      if (data[i + 3] !== 255) continue
      if (data[i] === 220 && data[i + 1] === 235 && data[i + 2] === 181) lime++
      if (data[i] === 23 && data[i + 1] === 59 && data[i + 2] === 49) dark++
    }
    return { width: image.naturalWidth, height: image.naturalHeight, lime, dark }
  })
  expect(pixels.width).toBe(32)
  expect(pixels.height).toBe(32)
  expect(pixels.lime).toBeGreaterThan(300)
  expect(pixels.dark).toBeGreaterThan(10)
  await page.getByLabel('用户名', { exact: true }).fill('admin')
  await page.getByLabel('密码', { exact: true }).fill('123456')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '今日概览', exact: true })).toBeVisible()
  await page.getByRole('link', { name: '执行记录', exact: true }).click()
  await page.reload()
  await expect(page.getByRole('heading', { name: '执行记录', exact: true })).toBeVisible()
  await expect(icon).toHaveAttribute('href', '/favicon.svg')
  expect((await page.request.get('/favicon.svg')).status()).toBe(200)
})
