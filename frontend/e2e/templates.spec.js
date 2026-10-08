import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'

const createdPlatforms = new Set()
test.afterEach(async ({ playwright }) => {
  if (!createdPlatforms.size) return
  const cleanup = await playwright.request.newContext({ baseURL: 'http://127.0.0.1:18080' })
  try {
    for (const id of createdPlatforms) {
      await cleanup.delete(`/api/platforms/${id}`, { data: {} })
      createdPlatforms.delete(id)
    }
  } finally {
    await cleanup.dispose()
  }
})

async function createPlatform(request, name, alias) {
  const response = await request.post('/api/platforms', { data: { name, enabled: true, version: 1 } })
  expect(response.ok()).toBe(true)
  const platform = await response.json()
  createdPlatforms.add(platform.id)
  const accountResponse = await request.post(`/api/platforms/${platform.id}/accounts`, {
    data: { alias, enabled: true, version: 1 },
  })
  expect(accountResponse.ok()).toBe(true)
  return { ...platform, account: await accountResponse.json() }
}

async function chooseTemplate(page, name) {
  await page.getByRole('combobox', { name: '接口模板（可选）' }).press('ArrowDown')
  await page.getByRole('option', { name, exact: true }).click()
}

test('platform rule templates fill requests without copying credentials or linking saved rules', async ({ page, request }, info) => {
  test.setTimeout(90000)
  const platform = await createPlatform(request, '多接口模板平台', '模板账号 A')
  const other = await createPlatform(request, '另一个模板平台', '模板账号 B')
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  try {
    await page.goto('/#/platforms')
    await page.locator('.platform-choice').filter({ hasText: '多接口模板平台' }).click()
    await page.getByRole('button', { name: '接口模板', exact: true }).click()
    let dialog = page.getByRole('dialog')
    await dialog.getByRole('button', { name: '＋ 新增模板' }).click()
    await dialog.getByRole('textbox', { name: '模板名称', exact: true }).fill('签到接口')
    await dialog.getByRole('button', { name: '试算规则' }).click()
    await expect(dialog).toContainText('success')
    await dialog.getByRole('button', { name: '保存模板', exact: true }).click()
    await expect(dialog.getByRole('cell', { name: '签到接口', exact: true })).toBeVisible()
    await dialog.getByRole('button', { name: '＋ 新增模板' }).click()
    await dialog.getByRole('textbox', { name: '模板名称', exact: true }).fill('领奖接口')
    await dialog.getByRole('textbox', { name: '成功字段路径', exact: true }).fill('')
    await dialog.getByRole('textbox', { name: '成功等于值', exact: true }).fill('')
    await dialog.getByRole('textbox', { name: '已完成字段路径', exact: true }).fill('code')
    await dialog.getByRole('textbox', { name: '已完成等于值', exact: true }).fill('0')
    await dialog.getByRole('button', { name: '保存模板', exact: true }).click()
    await expect(dialog.getByRole('cell', { name: '领奖接口', exact: true })).toBeVisible()
    await page.screenshot({ path: info.outputPath('templates-desktop.png'), fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
    await page.screenshot({ path: info.outputPath('templates-mobile.png'), fullPage: true })
    await page.setViewportSize({ width: 1440, height: 1000 })
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()

    await page.getByRole('button', { name: '＋ 添加请求' }).click()
    const curl = `curl 'http://127.0.0.1:18081/check?template=%2f&x=1&x=2' -H 'Cookie: template-account=A' --data-raw '{"from":"own-account"}'`
    await page.getByRole('textbox', { name: '完整 cURL', exact: true }).fill(curl)
    await expect(page.getByRole('combobox', { name: '接口模板（可选）' })).toBeEnabled()
    await chooseTemplate(page, '签到接口')
    await expect(page.getByRole('textbox', { name: '请求名称', exact: true })).toHaveValue('签到接口')
    await expect(page.getByRole('textbox', { name: '完整 cURL', exact: true })).toHaveValue(curl)
    await page.getByRole('button', { name: '解析预览' }).click()
    await page.getByRole('button', { name: '设置结果规则' }).click()
    await page.getByRole('textbox', { name: '成功等于值', exact: true }).fill('"0"')
    await page.getByRole('button', { name: '上一步', exact: true }).click()
    await page.getByRole('button', { name: '返回修改', exact: true }).click()
    await chooseTemplate(page, '领奖接口')
    await page.locator('.el-message-box').getByRole('button', { name: '取消', exact: true }).click()
    await expect(page.getByRole('textbox', { name: '请求名称', exact: true })).toHaveValue('签到接口')
    await expect(page.getByRole('textbox', { name: '完整 cURL', exact: true })).toHaveValue(curl)
    await chooseTemplate(page, '领奖接口')
    await page.locator('.el-message-box').getByRole('button', { name: '确认', exact: true }).click()
    await expect(page.getByRole('textbox', { name: '请求名称', exact: true })).toHaveValue('领奖接口')
    await expect(page.getByRole('textbox', { name: '完整 cURL', exact: true })).toHaveValue(curl)
    await page.getByRole('button', { name: '解析预览' }).click()
    await page.getByRole('button', { name: '设置结果规则' }).click()
    await expect(page.getByRole('textbox', { name: '成功字段路径', exact: true })).toHaveValue('')
    await expect(page.getByRole('textbox', { name: '已完成等于值', exact: true })).toHaveValue('0')
    await page.getByRole('button', { name: '保存请求', exact: true }).click()
    await expect(page.locator('.request-row')).toContainText('领奖接口')
    const tree = await (await request.get('/api/platforms')).json()
    const saved = tree.find((p) => p.id === platform.id).accounts[0].requests[0]
    expect(saved.rules.success).toBeNull()
    expect(saved.rules.alreadyDone.value).toBe(0)
    const run = await (await request.post('/api/runs', {
      data: { scope: 'request', id: saved.id, force: false, key: 'template-browser-fixture' },
    })).json()
    await expect.poll(async () => (await (await request.get(`/api/batches/${run.batchIds[0]}`)).json()).items[0].status).toBe('already_done')
    const received = await (await request.get('http://127.0.0.1:18081/received')).json()
    expect(received.at(-1)).toMatchObject({
      url: '/check?template=%2f&x=1&x=2', cookie: 'template-account=A', body: '{"from":"own-account"}',
    })

    await page.getByRole('button', { name: '更多 ▾' }).click()
    await page.getByText('保存为接口模板', { exact: true }).click()
    dialog = page.getByRole('dialog')
    await expect(dialog.getByRole('textbox', { name: '成功字段路径', exact: true })).toHaveValue('')
    await dialog.getByRole('textbox', { name: '模板名称', exact: true }).fill('领奖副本')
    await dialog.getByRole('button', { name: '保存模板', exact: true }).click()
    await expect(dialog.getByRole('cell', { name: '领奖副本', exact: true })).toBeVisible()
    const rewardRow = dialog.getByRole('row').filter({ has: page.getByText('领奖接口', { exact: true }) })
    await rewardRow.getByRole('button', { name: '编辑', exact: true }).click()
    await dialog.getByRole('textbox', { name: '已完成等于值', exact: true }).fill('99')
    await dialog.getByRole('button', { name: '保存模板', exact: true }).click()
    const checkinRow = dialog.getByRole('row').filter({ has: page.getByText('签到接口', { exact: true }) })
    await checkinRow.getByRole('button', { name: '删除', exact: true }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '确认', exact: true }).click()
    await expect(dialog.getByRole('cell', { name: '签到接口', exact: true })).toHaveCount(0)
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()
    const updatedTree = await (await request.get('/api/platforms')).json()
    expect(updatedTree.find((p) => p.id === platform.id).accounts[0].requests[0].rules).toEqual(saved.rules)

    await page.getByRole('button', { name: '＋ 添加请求' }).click()
    await chooseTemplate(page, '领奖副本')
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await page.getByRole('button', { name: '＋ 添加请求' }).click()
    await expect(page.getByRole('textbox', { name: '请求名称', exact: true })).toHaveValue('每日签到')
    await expect(page.getByRole('textbox', { name: '完整 cURL', exact: true })).toHaveValue('')
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await page.locator('.platform-choice').filter({ hasText: '另一个模板平台' }).click()
    await page.getByRole('button', { name: '＋ 添加请求' }).click()
    await expect(page.getByRole('combobox', { name: '接口模板（可选）' })).toBeDisabled()
    await page.getByRole('button', { name: '取消', exact: true }).click()

    await page.getByRole('link', { name: '设置与备份', exact: true }).click()
    const downloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: '导出配置', exact: true }).click()
    const filename = info.outputPath('templates-config.json')
    await (await downloadPromise).saveAs(filename)
    const text = await readFile(filename, 'utf8')
    const backup = JSON.parse(text)
    expect(backup.payload.templates.filter((t) => t.platformId === platform.id)).toHaveLength(2)
    expect(text).not.toContain('template-account')
    expect(text).not.toContain('own-account')
    await page.getByLabel('选择备份文件').setInputFiles(filename)
    await page.getByRole('button', { name: '预览导入', exact: true }).click()
    await expect(page.locator('.inline-info').filter({ hasText: '份接口模板' })).toContainText('2 份接口模板')
    expect(errors).toEqual([])
  } finally {
    await request.delete(`/api/platforms/${platform.id}`, { data: {} })
    createdPlatforms.delete(platform.id)
    await request.delete(`/api/platforms/${other.id}`, { data: {} })
    createdPlatforms.delete(other.id)
  }
})

test('a late template response cannot populate another platform and fetch errors allow manual input', async ({ page, request }) => {
  const first = await createPlatform(request, '延迟响应平台', '延迟账号')
  const second = await createPlatform(request, '当前响应平台', '当前账号')
  await request.post(`/api/platforms/${second.id}/templates`, { data: { name: '当前平台模板' } })
  let release, finished
  const gate = new Promise((resolve) => { release = resolve })
  const done = new Promise((resolve) => { finished = resolve })
  await page.route(`**/api/platforms/${first.id}/templates`, async (route) => {
    await gate
    await route.fulfill({ json: [{ id: '123', name: '旧平台模板', rules: { success: { path: 'old', value: 0 } } }] })
    finished()
  })
  try {
    await page.goto('/#/platforms')
    await page.locator('.platform-choice').filter({ hasText: '延迟响应平台' }).click()
    await page.getByRole('button', { name: '＋ 添加请求' }).click()
    await expect(page.getByRole('combobox', { name: '接口模板（可选）' })).toBeDisabled()
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await page.locator('.platform-choice').filter({ hasText: '当前响应平台' }).click()
    await page.getByRole('button', { name: '＋ 添加请求' }).click()
    await expect(page.getByRole('combobox', { name: '接口模板（可选）' })).toBeEnabled()
    release()
    await done
    await chooseTemplate(page, '当前平台模板')
    await expect(page.getByRole('textbox', { name: '请求名称', exact: true })).toHaveValue('当前平台模板')
    await page.getByRole('combobox', { name: '接口模板（可选）' }).press('ArrowDown')
    await expect(page.getByRole('option', { name: '当前平台模板', exact: true })).toBeVisible()
    await expect(page.getByRole('option', { name: '旧平台模板', exact: true })).toHaveCount(0)
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await page.route(`**/api/platforms/${second.id}/templates`, (route) => route.abort())
    await page.getByRole('button', { name: '＋ 添加请求' }).click()
    await expect(page.getByRole('dialog')).toContainText('仍可手动填写')
    await page.getByRole('textbox', { name: '完整 cURL', exact: true }).fill("curl 'http://127.0.0.1:18081/check'")
    await page.getByRole('button', { name: '解析预览' }).click()
    await page.getByRole('button', { name: '设置结果规则' }).click()
    await expect(page.getByRole('textbox', { name: '成功等于值', exact: true })).toHaveValue('0')
    await page.getByRole('button', { name: '保存请求', exact: true }).click()
    await expect(page.locator('.request-row')).toContainText('每日签到')
  } finally {
    release()
    await page.unrouteAll({ behavior: 'ignoreErrors' })
    await request.delete(`/api/platforms/${first.id}`, { data: {} })
    createdPlatforms.delete(first.id)
    await request.delete(`/api/platforms/${second.id}`, { data: {} })
    createdPlatforms.delete(second.id)
  }
})
