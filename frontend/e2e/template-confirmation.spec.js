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

for (const mode of ['selected', 'linked']) {
  for (const activation of ['mouse', 'keyboard']) {
    test(`${mode} preview confirms directly without repeated popups or writes using ${activation}`, async ({ page, request }) => {
      const name = `确认防重入-${mode}-${activation}`
      const platform = await post(request, '/api/platforms', { name, enabled: true })
      created.add(platform.id)
      const account = await post(request, `/api/platforms/${platform.id}/accounts`, { alias: '确认账号', enabled: true })
      const template = await post(request, `/api/platforms/${platform.id}/templates`, { name: '确认模板' })
      const item = await post(request, `/api/accounts/${account.id}/requests`, {
        name: '确认请求', enabled: true, curl: "curl 'http://127.0.0.1:18081/check'",
        templateId: mode === 'linked' ? template.id : null,
      })
      await page.goto('/#/platforms')
      await page.locator('.platform-choice').filter({ hasText: name }).click()
      await page.getByRole('button', { name: '接口模板', exact: true }).click()
      const manager = page.getByRole('dialog', { name: '接口模板', exact: true })
      await manager.locator(`[data-template-id="${template.id}"]`).getByRole('button', {
        name: mode === 'linked' ? '应用到已关联请求' : '选择请求应用', exact: true,
      }).click()
      const dialog = page.getByRole('dialog', { name: mode === 'linked' ? '应用到已关联请求' : '选择请求并应用模板', exact: true })
      await expect(dialog.locator('.apply-target')).toHaveCount(1)
      if (mode === 'selected') await dialog.locator('.el-checkbox').click()
      const button = dialog.getByRole('button', { name: mode === 'linked' ? '确认更新已关联请求' : '应用并关联所选请求', exact: true })
      await page.evaluate(() => {
        window.__confirmationCount = 0
        const observed = new WeakSet()
        new MutationObserver(() => {
          for (const box of document.querySelectorAll('.el-message-box')) {
            if (!observed.has(box)) { observed.add(box); window.__confirmationCount++ }
          }
        }).observe(document.body, { childList: true, subtree: true })
      })
      let release, started, writes = 0
      const gate = new Promise(resolve => { release = resolve })
      const captured = new Promise(resolve => { started = resolve })
      await page.route(`**/api/platforms/${platform.id}/templates/${template.id}/apply`, async route => {
        writes++
        started()
        await gate
        const response = await route.fetch()
        await route.fulfill({ response })
      })
      try {
        await expect(dialog).toContainText('即确认覆盖以上 1 个请求的现有规则')
        expect(writes).toBe(0)
        if (activation === 'keyboard') await button.press('Enter')
        else await button.dblclick()
        await captured
        await expect(button).toBeDisabled()
        await button.evaluate(element => { element.click(); element.click() })
        await expect(page.locator('.el-message-box')).toHaveCount(0)
        expect(writes).toBe(1)
        expect(await page.evaluate(() => window.__confirmationCount)).toBe(0)
        release()
        await expect(dialog).toBeHidden()
        await expect(manager).toContainText('已关联 1 个请求')
        expect(writes).toBe(1)
        expect(await page.evaluate(() => window.__confirmationCount)).toBe(0)
        const tree = await (await request.get('/api/platforms')).json()
        expect(tree.find(p => p.id === platform.id).accounts[0].requests.find(r => r.id === item.id)).toMatchObject({ templateId: template.id, version: 2 })
      } finally { release(); await page.unrouteAll({ behavior: 'ignoreErrors' }) }
    })
  }
}
