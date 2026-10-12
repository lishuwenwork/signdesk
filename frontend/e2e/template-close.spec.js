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
async function selectPlatform(page, name) {
  const picker = page.getByRole('combobox', { name: '选择平台', exact: true })
  if (await picker.isVisible()) {
    await picker.press('ArrowDown')
    await page.getByRole('option', { name, exact: true }).click()
  } else await page.locator('.platform-choice').filter({ hasText: name }).click()
}
async function sampleClose(dialog) {
  await dialog.evaluate(element => {
    window.__templateCloseFrames = []
    window.__templateCloseDone = false
    const overlay = element.closest('.el-overlay')
    function sample() {
      const style = getComputedStyle(element), overlayStyle = getComputedStyle(overlay)
      const visible = element.isConnected && style.display !== 'none' && style.visibility !== 'hidden'
        && +style.opacity > 0.01 && overlayStyle.display !== 'none' && +overlayStyle.opacity > 0.01
      window.__templateCloseFrames.push({ visible, cards: element.querySelectorAll('.template-card').length, empty: !!element.querySelector('.el-empty') })
      if (visible) requestAnimationFrame(sample)
      else window.__templateCloseDone = true
    }
    sample()
  })
}

for (const width of [1440, 390]) {
  for (const method of ['footer', 'header']) {
    test(`template ${method} close preserves the list until the modal leaves at ${width}px`, async ({ page, request }, info) => {
      const name = `关闭退场-${method}-${width}`
      const platform = await post(request, '/api/platforms', { name, enabled: true })
      created.add(platform.id)
      for (const name of ['签到规则', '奖励规则']) await post(request, `/api/platforms/${platform.id}/templates`, { name })
      const errors = []
      page.on('pageerror', error => errors.push(error.message))
      await page.setViewportSize({ width, height: 844 })
      await page.goto('/#/platforms')
      await expect(page.locator('.platform-workspace')).toBeVisible()
      await selectPlatform(page, name)
      await page.getByRole('button', { name: '接口模板', exact: true }).click()
      const dialog = page.getByRole('dialog', { name: '接口模板', exact: true })
      await expect(dialog.locator('.template-card')).toHaveCount(2)
      await expect.poll(() => dialog.evaluate(element => !element.closest('.dialog-fade-enter-active'))).toBe(true)
      await sampleClose(dialog)
      if (method === 'footer') await dialog.getByRole('button', { name: '关闭', exact: true }).click()
      else await dialog.locator('.el-dialog__headerbtn').click()
      await expect(dialog).toHaveCount(0)
      await expect.poll(() => page.evaluate(() => window.__templateCloseDone)).toBe(true)
      const frames = await page.evaluate(() => window.__templateCloseFrames)
      await info.attach('template-close-frames.json', { body: JSON.stringify(frames), contentType: 'application/json' })
      const visibleFrames = frames.filter(frame => frame.visible)
      expect(visibleFrames.length).toBeGreaterThan(1)
      expect(visibleFrames.every(frame => frame.cards === 2 && !frame.empty)).toBe(true)
      expect((await (await request.get(`/api/platforms/${platform.id}/templates`)).json()).length).toBe(2)
      await page.getByRole('button', { name: '接口模板', exact: true }).click()
      await expect(dialog.locator('.template-card')).toHaveCount(2)
      await expect(dialog).toContainText('签到规则')
      await expect(dialog).toContainText('奖励规则')
      await dialog.locator('.el-dialog__headerbtn').click()
      await expect(dialog).toHaveCount(0)
      expect(errors).toEqual([])
    })
  }
}

test('closing template management rejects a late list response after another platform opens', async ({ page, request }) => {
  const first = await post(request, '/api/platforms', { name: '关闭前的平台', enabled: true })
  const second = await post(request, '/api/platforms', { name: '关闭后的平台', enabled: true })
  created.add(first.id); created.add(second.id)
  await post(request, `/api/platforms/${first.id}/templates`, { name: '迟到的旧模板' })
  await post(request, `/api/platforms/${second.id}/templates`, { name: '新平台模板' })
  const previous = await (await request.get(`/api/platforms/${first.id}/templates`)).json()
  let release, finished
  const gate = new Promise(resolve => { release = resolve })
  const done = new Promise(resolve => { finished = resolve })
  await page.route(`**/api/platforms/${first.id}/templates`, async route => {
    await gate
    await route.fulfill({ json: previous })
    finished()
  })
  try {
    await page.goto('/#/platforms')
    await expect(page.locator('.platform-workspace')).toBeVisible()
    await selectPlatform(page, '关闭前的平台')
    await page.getByRole('button', { name: '接口模板', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '接口模板', exact: true })
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(dialog).toHaveCount(0)
    await selectPlatform(page, '关闭后的平台')
    await page.getByRole('button', { name: '接口模板', exact: true }).click()
    await expect(dialog.locator('.template-card')).toHaveCount(1)
    await expect(dialog).toContainText('新平台模板')
    release()
    await done
    await expect(dialog.locator('.template-card')).toHaveCount(1)
    await expect(dialog).not.toContainText('迟到的旧模板')
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(dialog).toHaveCount(0)
  } finally {
    release()
    await page.unrouteAll({ behavior: 'ignoreErrors' })
  }
})
