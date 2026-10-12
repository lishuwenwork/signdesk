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
async function post(request, path, data) {
  const response = await request.post(path, { data })
  expect(response.ok()).toBe(true)
  return response.json()
}
async function saved(request, platformId, id) {
  const tree = await (await request.get('/api/platforms')).json()
  return tree.find(platform => platform.id === platformId).accounts.flatMap(account => account.requests).find(row => row.id === id)
}
async function selectPlatform(page, name) {
  await expect(page.locator('.platform-workspace')).toBeVisible()
  const picker = page.getByRole('combobox', { name: '选择平台', exact: true })
  if (await picker.isVisible()) {
    await picker.press('ArrowDown')
    await page.getByRole('option', { name, exact: true }).click()
  } else await page.locator('.platform-choice').filter({ hasText: name }).click()
}
async function trial(dialog, body, status) {
  await dialog.getByRole('textbox', { name: '规则试算响应', exact: true }).fill(JSON.stringify(body))
  await expect(dialog.locator('.rule-test .el-tag')).toHaveCount(0)
  await dialog.getByRole('button', { name: '试算规则', exact: true }).click()
  await expect(dialog.locator('.rule-test .el-tag')).toContainText(status)
}
async function run(request, id, key, status) {
  const accepted = await post(request, '/api/runs', { scope: 'request', id, force: false, key })
  await expect.poll(async () => {
    const batch = await (await request.get(`/api/batches/${accepted.batchIds[0]}`)).json()
    return batch.status === 'completed' ? batch.items[0].status : batch.status
  }).toBe(status)
}

for (const width of [1440, 390]) {
  test(`inverse rules stay explicit, resolve completion conflicts and agree with actual execution at ${width}px`, async ({ page, request }, info) => {
    test.setTimeout(90000)
    const platformName = `规则判断平台-${width}`
    const platform = await post(request, '/api/platforms', { name: platformName, enabled: true })
    created.add(platform.id)
    const account = await post(request, `/api/platforms/${platform.id}/accounts`, { alias: '规则账号', enabled: true })
    const receivedBefore = (await (await request.get('http://127.0.0.1:18081/received')).json()).length
    await page.setViewportSize({ width, height: width === 390 ? 844 : 1000 })
    await page.goto('/#/platforms')
    await selectPlatform(page, platformName)
    await page.getByRole('region', { name: '账号 规则账号', exact: true }).getByRole('button', { name: '添加请求', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '导入完整 cURL', exact: true })
    await dialog.getByRole('textbox', { name: '请求名称', exact: true }).fill('已签到优先')
    await dialog.getByRole('textbox', { name: '完整 cURL', exact: true }).fill(`curl 'http://127.0.0.1:18081/rules/already-done?width=${width}'`)
    await dialog.getByRole('button', { name: '解析预览', exact: true }).click()
    await dialog.getByRole('button', { name: '设置结果规则', exact: true }).click()
    const inverse = dialog.getByRole('switch', { name: '反向失败判断', exact: true })
    await expect(inverse).toHaveAttribute('aria-checked', 'false')
    await trial(dialog, { code: 99 }, 'unknown')
    await dialog.locator('.failure-option .el-switch').click()
    await expect(inverse).toHaveAttribute('aria-checked', 'true')
    await trial(dialog, { code: 0 }, 'success')
    await trial(dialog, { code: 1234 }, 'failed')
    await trial(dialog, { code: '0' }, 'unknown')
    await trial(dialog, {}, 'unknown')
    await dialog.getByRole('textbox', { name: '成功文本包含', exact: true }).fill('done')
    await dialog.getByRole('button', { name: '保存请求', exact: true }).click()
    await expect(dialog).toContainText('无法开启反向失败判断')
    await expect(inverse).toHaveAttribute('aria-checked', 'true')
    expect((await (await request.get('/api/platforms')).json()).find(p => p.id === platform.id).accounts[0].requests).toHaveLength(0)
    await dialog.getByRole('textbox', { name: '成功文本包含', exact: true }).fill('')
    await dialog.getByRole('textbox', { name: '成功字段路径', exact: true }).fill('success')
    await dialog.getByRole('textbox', { name: '成功等于值', exact: true }).fill('true')
    await dialog.getByRole('button', { name: '展开已完成规则', exact: true }).click()
    await dialog.getByRole('textbox', { name: '已完成字段路径', exact: true }).fill('message')
    await dialog.getByRole('textbox', { name: '已完成等于值', exact: true }).fill('"今日已签到"')
    await dialog.getByRole('button', { name: '展开业务失败规则', exact: true }).click()
    await dialog.getByRole('textbox', { name: '业务失败字段路径', exact: true }).fill('success')
    await dialog.getByRole('textbox', { name: '业务失败等于值', exact: true }).fill('false')
    await trial(dialog, { message: '今日已签到', success: false }, 'already_done')
    await trial(dialog, { message: '条件不满足', success: false }, 'failed')
    await dialog.getByRole('button', { name: '展开凭证过期规则', exact: true }).click()
    await dialog.getByRole('textbox', { name: '凭证过期字段路径', exact: true }).fill('expired')
    await dialog.getByRole('textbox', { name: '凭证过期等于值', exact: true }).fill('true')
    await trial(dialog, { message: '今日已签到', success: false, expired: true }, 'expired')
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width + 1)
    await page.screenshot({ path: info.outputPath(`result-rules-${width}.png`), fullPage: true })
    expect((await (await request.get('http://127.0.0.1:18081/received')).json()).length).toBe(receivedBefore)
    const creation = page.waitForResponse(response => new URL(response.url()).pathname === `/api/accounts/${account.id}/requests` && response.request().method() === 'POST')
    await dialog.getByRole('button', { name: '保存请求', exact: true }).click()
    const item = await (await creation).json()
    await expect(dialog).not.toBeVisible()
    expect((await saved(request, platform.id, item.id)).rules.failureOnSuccessMismatch).toBe(true)
    await run(request, item.id, `rules-done-${width}`, 'already_done')
    expect((await saved(request, platform.id, item.id)).todayState).toBe('completed')
    const failed = await post(request, `/api/accounts/${account.id}/requests`, {
      name: '不枚举错误码', enabled: true, curl: `curl 'http://127.0.0.1:18081/rules/nonzero?width=${width}'`,
      rules: { success: { path: 'code', value: 0 }, failureOnSuccessMismatch: true },
    })
    await run(request, failed.id, `rules-failed-${width}`, 'failed')
    expect(await saved(request, platform.id, failed.id)).toMatchObject({ todayState: 'none', authPaused: 0 })
    const unknown = await post(request, `/api/accounts/${account.id}/requests`, {
      name: '缺字段待确认', enabled: true, curl: `curl 'http://127.0.0.1:18081/rules/missing?width=${width}'`,
      rules: { success: { path: 'code', value: 0 }, failureOnSuccessMismatch: true },
    })
    await run(request, unknown.id, `rules-unknown-${width}`, 'unknown')
    expect((await saved(request, platform.id, unknown.id)).todayState).toBe('pending')
  })
}
