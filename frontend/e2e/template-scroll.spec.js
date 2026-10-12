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

for (const width of [1440, 390]) {
  for (const mode of ['selected', 'linked']) {
    test(`template ${mode} view uses one stable modal while scrolling at ${width}px`, async ({ page, request }, info) => {
      const name = `滚动稳定-${mode}-${width}`
      const platform = await post(request, '/api/platforms', { name, enabled: true })
      created.add(platform.id)
      const account = await post(request, `/api/platforms/${platform.id}/accounts`, { alias: '滚动账号', enabled: true })
      const template = await post(request, `/api/platforms/${platform.id}/templates`, { name: '滚动模板' })
      for (let index = 0; index < 24; index++) await post(request, `/api/accounts/${account.id}/requests`, {
        name: `滚动请求 ${index + 1} · 保留自己的名称和凭证`, enabled: true,
        curl: "curl 'http://127.0.0.1:18081/check'", templateId: mode === 'linked' ? template.id : null,
      })
      const errors = []
      page.on('pageerror', error => errors.push(error.message))
      await page.setViewportSize({ width, height: width === 390 ? 844 : 650 })
      await page.goto('/#/platforms')
      await expect(page.locator('.platform-workspace')).toBeVisible()
      const picker = page.getByRole('combobox', { name: '选择平台', exact: true })
      if (await picker.isVisible()) {
        await picker.press('ArrowDown')
        await page.getByRole('option', { name, exact: true }).click()
      } else await page.locator('.platform-choice').filter({ hasText: name }).click()
      await page.getByRole('button', { name: '接口模板', exact: true }).click()
      const manager = page.getByRole('dialog', { name: '接口模板', exact: true })
      const modalElement = await manager.elementHandle()
      await manager.getByRole('button', { name: mode === 'linked' ? '应用到已关联请求' : '选择请求应用', exact: true }).click()
      const dialog = page.getByRole('dialog', { name: mode === 'linked' ? '应用到已关联请求' : '选择请求并应用模板', exact: true })
      await expect(dialog.locator('.apply-target')).toHaveCount(24)
      await expect.poll(() => dialog.evaluate(element => !element.closest('.dialog-fade-enter-active'))).toBe(true)
      const listGap = () => dialog.evaluate(element => {
        const toolbar = element.querySelector('.apply-toolbar').getBoundingClientRect()
        const targets = element.querySelector('.apply-targets').getBoundingClientRect()
        return targets.top - toolbar.bottom
      })
      expect(await listGap()).toBeGreaterThanOrEqual(12)
      const filter = dialog.getByRole('textbox', { name: '筛选模板应用请求', exact: true })
      await filter.fill('滚动请求 24')
      await expect(dialog.locator('.apply-target')).toHaveCount(1)
      expect(await listGap()).toBeGreaterThanOrEqual(12)
      await filter.fill('')
      await expect(dialog.locator('.apply-target')).toHaveCount(24)
      await dialog.evaluate(element => {
        window.__scrollFrames = []
        window.__scrollDone = false
        function sample() {
          const rect = element.getBoundingClientRect(), style = getComputedStyle(element)
          const overlays = [...document.querySelectorAll('.el-overlay')].filter(node => {
            const css = getComputedStyle(node)
            return css.display !== 'none' && css.visibility !== 'hidden' && node.getBoundingClientRect().height > 0
          }).length
          window.__scrollFrames.push({ connected: element.isConnected, visible: style.display !== 'none' && style.visibility !== 'hidden' && +style.opacity > 0.99, x: rect.x, y: rect.y, overlays })
          if (window.__scrollFrames.length < 120) requestAnimationFrame(sample)
          else window.__scrollDone = true
        }
        requestAnimationFrame(sample)
      })
      await dialog.locator('.apply-targets').hover()
      for (let index = 0; index < 6; index++) await page.mouse.wheel(0, index % 2 ? -900 : 900)
      const bounds = await dialog.boundingBox()
      await page.mouse.move(bounds.x + 10, Math.max(35, Math.min(bounds.y + 140, 400)))
      for (let index = 0; index < 6; index++) await page.mouse.wheel(0, index % 2 ? -900 : 900)
      await expect.poll(() => page.evaluate(() => window.__scrollDone)).toBe(true)
      const frames = await page.evaluate(() => window.__scrollFrames)
      await info.attach('scroll-frames.json', { body: JSON.stringify(frames), contentType: 'application/json' })
      expect(frames.every(frame => frame.connected && frame.visible)).toBe(true)
      expect(Math.max(...frames.map(frame => frame.overlays))).toBe(1)
      expect(Math.max(...frames.map(frame => frame.y)) - Math.min(...frames.map(frame => frame.y))).toBeLessThanOrEqual(1)
      await expect(page.getByRole('dialog')).toHaveCount(1)
      expect(await dialog.evaluate((element, original) => element === original, modalElement)).toBe(true)
      await expect(dialog.getByRole('button', { name: '取消', exact: true })).toBeInViewport()
      await page.screenshot({ path: info.outputPath(`template-scroll-${mode}-${width}.png`), animations: 'disabled' })
      await dialog.getByRole('button', { name: '取消', exact: true }).click()
      await expect(manager).toBeVisible()
      await expect(page.getByRole('dialog')).toHaveCount(1)
      await manager.getByRole('button', { name: mode === 'linked' ? '应用到已关联请求' : '选择请求应用', exact: true }).click()
      await expect(dialog.locator('.apply-target')).toHaveCount(24)
      await page.keyboard.press('Escape')
      await expect(manager).toBeVisible()
      expect(await manager.evaluate((element, original) => element === original, modalElement)).toBe(true)
      await page.keyboard.press('Escape')
      await expect(page.getByRole('dialog')).toHaveCount(0)
      expect(errors).toEqual([])
    })
  }
}
