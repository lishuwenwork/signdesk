import { test, expect } from '@playwright/test'

const createdPlatforms = new Set()
const fixtureCurl = "curl 'http://127.0.0.1:18081/check' -H 'Cookie: layout-fixture=one'"
const accountGroup = (page, alias) => page.getByRole('region', { name: `账号 ${alias}`, exact: true })
const requestRow = (page, id) => page.locator(`[data-request-id="${id}"]`)

async function send(request, method, path, data) {
  const response = await request[method](path, data === undefined ? {} : { data })
  expect(response.ok(), `${method.toUpperCase()} ${path}`).toBe(true)
  return response.json()
}
async function createPlatform(request, name) {
  const platform = await send(request, 'post', '/api/platforms', { name, enabled: true, version: 1 })
  createdPlatforms.add(platform.id)
  return { ...platform, name }
}
async function createAccount(request, platform, alias, enabled = true) {
  return { ...await send(request, 'post', `/api/platforms/${platform.id}/accounts`, { alias, enabled }), alias }
}
async function createRequest(request, account, name, enabled = true) {
  return send(request, 'post', `/api/accounts/${account.id}/requests`, { name, enabled, curl: fixtureCurl })
}
async function savedRequest(request, platformId, id) {
  const tree = await send(request, 'get', '/api/platforms')
  return tree.find((platform) => platform.id === platformId)?.accounts.flatMap((account) => account.requests)
    .find((item) => item.id === id)
}
async function selectPlatform(page, name) {
  await expect(page.locator('.platform-workspace')).toBeVisible()
  const picker = page.getByRole('combobox', { name: '选择平台', exact: true })
  if (await picker.isVisible()) {
    await picker.press('ArrowDown')
    await page.getByRole('option', { name, exact: true }).click()
  } else {
    await page.getByRole('complementary', { name: '平台列表', exact: true })
      .getByRole('button').filter({ has: page.locator('strong', { hasText: name }) }).click()
  }
  await expect(page.getByRole('heading', { name, level: 2, exact: true })).toBeVisible()
}
async function selectStatus(page, name) {
  await page.locator('.catalog-status-filter .el-select__wrapper').click()
  await page.getByRole('option', { name, exact: true }).click()
}
async function noOverflow(page, width) {
  expect(await page.evaluate(() => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth)))
    .toBeLessThanOrEqual(width + 1)
}
function nextCatalogueResponse(page) {
  return page.waitForResponse((response) => new URL(response.url()).pathname === '/api/platforms'
    && response.request().method() === 'GET')
}

test.beforeEach(async ({ baseURL }) => {
  expect(baseURL).toBe('http://127.0.0.1:18080')
})
test.afterEach(async ({ playwright }) => {
  const cleanup = await playwright.request.newContext({ baseURL: 'http://127.0.0.1:18080' })
  try {
    for (const id of createdPlatforms) {
      const response = await cleanup.delete(`/api/platforms/${id}`, { data: {} })
      expect(response.ok()).toBe(true)
      createdPlatforms.delete(id)
    }
  } finally {
    await cleanup.dispose()
  }
})

for (const width of [1440, 1024, 390]) {
  test(`account groups stay readable, searchable and independently folded at ${width}px`, async ({ page, request }, info) => {
    const platform = await createPlatform(request, `布局平台-${width}`)
    const other = await createPlatform(request, `切换平台-${width}`)
    const main = await createAccount(request, platform, '主账号')
    const spare = await createAccount(request, platform, '用于验证折叠布局的长账号名称'.repeat(2))
    const empty = await createAccount(request, platform, '空账号')
    const first = await createRequest(request, main, '每日签到')
    await createRequest(request, main, '较长请求名称'.repeat(8))
    await createRequest(request, spare, '积分查询')
    await createRequest(request, spare, '领取奖励')
    await createAccount(request, other, '另一个账号')
    let revisionReads = 0
    const errors = []
    page.on('request', (r) => { if (/\/api\/requests\/[^/]+\/revision$/.test(new URL(r.url()).pathname)) revisionReads++ })
    page.on('pageerror', (error) => errors.push(error.message))
    await page.setViewportSize({ width, height: width === 390 ? 844 : 1000 })
    await page.goto('/#/platforms')
    await selectPlatform(page, platform.name)
    const mainGroup = accountGroup(page, main.alias), spareGroup = accountGroup(page, spare.alias)
    const mainToggle = mainGroup.locator('.account-toggle'), spareToggle = spareGroup.locator('.account-toggle')
    await expect(mainToggle).toHaveAttribute('aria-expanded', 'true')
    await expect(spareToggle).toHaveAttribute('aria-expanded', 'false')
    await expect(accountGroup(page, empty.alias).locator('.account-toggle')).toHaveAttribute('aria-expanded', 'false')

    await spareToggle.focus()
    await spareToggle.press('Enter')
    await expect(spareToggle).toHaveAttribute('aria-expanded', 'true')
    await expect(mainToggle).toHaveAttribute('aria-expanded', 'true')
    await spareToggle.click()
    await spareGroup.getByRole('button', { name: '＋ 添加请求', exact: true }).click()
    await expect(page.getByRole('dialog')).toContainText(`${platform.name} / ${spare.alias}`)
    await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click()
    await expect(spareToggle).toHaveAttribute('aria-expanded', 'false')

    await page.getByRole('button', { name: '全部收起', exact: true }).click()
    const poll = await nextCatalogueResponse(page)
    expect(poll.ok()).toBe(true)
    await expect(mainToggle).toHaveAttribute('aria-expanded', 'false')
    await expect(spareToggle).toHaveAttribute('aria-expanded', 'false')
    const search = page.getByRole('textbox', { name: '搜索当前平台请求', exact: true })
    await search.fill('备用不存在')
    await expect(page.getByText('没有符合条件的账号或请求', { exact: true })).toBeVisible()
    await search.fill(spare.alias)
    await expect(spareToggle).toHaveAttribute('aria-expanded', 'true')
    await expect(spareGroup.locator('.request-row')).toHaveCount(2)
    await expect(mainGroup).toHaveCount(0)
    await spareToggle.click()
    await expect(spareToggle).toHaveAttribute('aria-expanded', 'false')
    await search.fill('积分')
    await expect(spareToggle).toHaveAttribute('aria-expanded', 'true')
    await expect(spareGroup.locator('.request-row')).toHaveCount(1)
    await search.fill('')
    await expect(spareToggle).toHaveAttribute('aria-expanded', 'false')
    await expect(mainToggle).toHaveAttribute('aria-expanded', 'false')

    await page.getByRole('button', { name: '全部展开', exact: true }).click()
    await spareToggle.click()
    await selectPlatform(page, other.name)
    await selectPlatform(page, platform.name)
    await expect(mainToggle).toHaveAttribute('aria-expanded', 'true')
    await expect(spareToggle).toHaveAttribute('aria-expanded', 'false')
    const row = requestRow(page, first.id)
    for (const name of ['更新 cURL', '编辑', '删除']) {
      await expect(row.getByRole('button', { name, exact: true })).toBeVisible()
    }
    if (width === 390) {
      expect(await row.getByRole('button', { name: '更新 cURL', exact: true }).evaluate((button) => button.getBoundingClientRect().height))
        .toBeGreaterThanOrEqual(44)
    }
    await noOverflow(page, width)
    await page.screenshot({ path: info.outputPath(`platform-accounts-${width}.png`), fullPage: true, animations: 'disabled' })
    if (width === 1440) {
      for (const boundary of [1101, 1100, 761, 760]) {
        await page.setViewportSize({ width: boundary, height: 1000 })
        await expect(row.getByRole('button', { name: '更新 cURL', exact: true })).toBeVisible()
        await noOverflow(page, boundary)
      }
    }
    expect(revisionReads).toBe(0)
    expect(errors).toEqual([])
  })
}

test('direct maintenance actions preserve versions, reveal creations and confirm deletion', async ({ page, request }) => {
  test.setTimeout(90000)
  const platform = await createPlatform(request, '请求维护平台')
  const account = await createAccount(request, platform, '维护账号')
  const item = await createRequest(request, account, '原请求', false)
  const before = await savedRequest(request, platform.id, item.id)
  const receivedBefore = (await send(request, 'get', 'http://127.0.0.1:18081/received')).length
  let revisionReads = 0, deletes = 0
  page.on('request', (r) => {
    if (/\/api\/requests\/[^/]+\/revision$/.test(new URL(r.url()).pathname)) revisionReads++
    if (r.method() === 'DELETE') deletes++
  })
  await page.goto('/#/platforms')
  await selectPlatform(page, platform.name)
  const row = requestRow(page, item.id)
  await row.getByRole('button', { name: '原请求的更多操作', exact: true }).click()
  await expect(page.getByRole('menuitem', { name: '执行', exact: true })).toBeDisabled()
  await expect(page.getByRole('menuitem', { name: '重新执行（忽略当日标记）', exact: true })).toBeDisabled()
  await page.keyboard.press('Escape')

  await row.getByRole('button', { name: '编辑', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '编辑名称与结果规则', exact: true })).toBeVisible()
  await page.getByRole('textbox', { name: '请求名称', exact: true }).fill('改名请求')
  await page.getByRole('textbox', { name: '成功等于值', exact: true }).fill('1')
  await page.getByRole('button', { name: '保存请求', exact: true }).click()
  await expect(row).toContainText('改名请求')
  const edited = await savedRequest(request, platform.id, item.id)
  expect(edited.currentRevision).toBe(before.currentRevision)
  expect(edited.rules.success.value).toBe(1)
  expect(edited.enabled).toBe(0)

  await row.getByRole('button', { name: '更新 cURL', exact: true }).click()
  const curlInput = page.getByRole('textbox', { name: '完整 cURL', exact: true })
  await expect(curlInput).toHaveValue('')
  await curlInput.fill("curl 'http://127.0.0.1:18081/check?updated=1' -H 'Cookie: layout-fixture=updated'")
  await page.getByRole('button', { name: '解析预览', exact: true }).click()
  await page.getByRole('button', { name: '保存新版本', exact: true }).click()
  await expect(row).toContainText('版本 2')
  const updated = await savedRequest(request, platform.id, item.id)
  expect(updated).toMatchObject({ name: '改名请求', enabled: 0, currentRevision: 2, authPaused: 0 })
  expect(updated.rules).toEqual(edited.rules)

  const enabledAction = row.locator('.request-enable-action')
  await enabledAction.click()
  await expect.poll(async () => (await savedRequest(request, platform.id, item.id)).enabled).toBe(1)
  await expect(row.locator('.request-state')).toHaveText('启用')
  await expect(enabledAction).toHaveText('禁用')
  await expect(enabledAction).toHaveAccessibleName('禁用请求 改名请求')
  await expect(enabledAction).toBeEnabled()
  await page.getByRole('textbox', { name: '搜索当前平台请求', exact: true }).fill('改名请求')
  await accountGroup(page, account.alias).getByRole('button', { name: '＋ 添加请求', exact: true }).click()
  await page.getByRole('textbox', { name: '请求名称', exact: true }).fill('筛选中新建的请求')
  await page.getByRole('textbox', { name: '完整 cURL', exact: true }).fill(fixtureCurl)
  await page.getByRole('button', { name: '解析预览', exact: true }).click()
  await page.getByRole('button', { name: '设置结果规则', exact: true }).click()
  const createdResponse = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/accounts/${account.id}/requests`
    && response.request().method() === 'POST')
  await page.getByRole('button', { name: '保存请求', exact: true }).click()
  const response = await createdResponse
  expect(response.ok()).toBe(true)
  const created = await response.json(), createdRow = requestRow(page, created.id)
  await expect(createdRow).toBeVisible()
  await expect(page.getByRole('textbox', { name: '搜索当前平台请求', exact: true })).toHaveValue('')
  await expect(page.getByText('已清除筛选，以显示刚刚添加的内容。', { exact: true })).toBeVisible()
  await expect(accountGroup(page, account.alias).locator('.account-toggle')).toHaveAttribute('aria-expanded', 'true')

  await createdRow.getByRole('button', { name: '删除', exact: true }).click()
  const confirmation = page.locator('.el-message-box')
  await expect(confirmation).toContainText('维护账号')
  await expect(confirmation).toContainText('筛选中新建的请求')
  await confirmation.getByRole('button', { name: '取消', exact: true }).click()
  expect(deletes).toBe(0)
  await expect(createdRow).toBeVisible()
  await createdRow.getByRole('button', { name: '删除', exact: true }).click()
  await confirmation.getByRole('button', { name: '确认', exact: true }).click()
  await expect(createdRow).toHaveCount(0)
  expect(deletes).toBe(1)
  expect(await savedRequest(request, platform.id, item.id)).toMatchObject({ id: item.id })

  await page.getByRole('textbox', { name: '搜索当前平台请求', exact: true }).fill('改名请求')
  await row.getByRole('button', { name: '编辑', exact: true }).click()
  await page.getByRole('textbox', { name: '请求名称', exact: true }).fill('不再匹配原搜索的请求')
  await page.getByRole('button', { name: '保存请求', exact: true }).click()
  await expect(row).toHaveCount(0)
  await expect(page.getByText('已保存，该请求不再符合当前筛选。清除筛选后可以查看。', { exact: true })).toBeVisible()
  await expect(page.getByRole('textbox', { name: '搜索当前平台请求', exact: true })).toHaveValue('改名请求')
  await page.locator('.catalog-result-bar').getByRole('button', { name: '清除筛选', exact: true }).click()
  await expect(row).toContainText('不再匹配原搜索的请求')

  await page.getByRole('button', { name: '＋ 添加账号', exact: true }).click()
  await page.getByRole('textbox', { name: '账号别名', exact: true }).fill('刚添加的账号')
  await page.getByRole('button', { name: '保存账号', exact: true }).click()
  await expect(accountGroup(page, '刚添加的账号').locator('.account-toggle')).toHaveAttribute('aria-expanded', 'true')
  expect(revisionReads).toBe(0)
  expect((await send(request, 'get', 'http://127.0.0.1:18081/received')).length).toBe(receivedBefore)
})

test('synthetic list states expose credential issues and inherited disablement without fetching secrets', async ({ page }) => {
  const longHost = `${'long-host-segment.'.repeat(10)}example.test`
  const tree = [{ id: '9000000000000000001', name: '合成状态平台', note: '', enabled: 1, accounts: [
    { id: '9000000000000000002', alias: '主账号', enabled: 1, requests: [
      { id: '9000000000000000011', name: '已配置', enabled: 1, safeHost: longHost, method: 'POST', currentRevision: 3,
        version: 1, authPaused: 0, rules: { success: { contains: 'synthetic-private-rule' } } },
      { id: '9000000000000000012', name: '占位请求', enabled: 0, safeHost: '', method: 'GET', currentRevision: 1, version: 1, authPaused: 1 },
      { id: '9000000000000000013', name: '凭证暂停请求', enabled: 1, safeHost: 'example.test', method: 'GET', currentRevision: 1, version: 1, authPaused: 1 },
    ] },
    { id: '9000000000000000003', alias: '停用账号', enabled: 0, requests: [
      { id: '9000000000000000014', name: '继承停用请求', enabled: 1, safeHost: 'example.test', method: 'GET', currentRevision: 1, version: 1, authPaused: 0 },
    ] },
  ] }]
  let revisionReads = 0
  page.on('request', (r) => { if (/\/api\/requests\/[^/]+\/revision$/.test(new URL(r.url()).pathname)) revisionReads++ })
  await page.route('**/api/platforms', (route) => route.fulfill({ json: tree }))
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/#/platforms')
  await expect(accountGroup(page, '主账号').locator('.account-summary')).toContainText('2 个需处理')
  await selectStatus(page, '需处理')
  await expect(page.locator('.request-row')).toHaveCount(2)
  const placeholder = requestRow(page, '9000000000000000012')
  await expect(placeholder).toContainText('未配置 cURL，需更新')
  await expect(placeholder).not.toContainText('版本 1')
  await placeholder.getByRole('button', { name: '占位请求的更多操作', exact: true }).click()
  await expect(page.getByRole('menuitem', { name: '查看完整请求', exact: true })).toBeDisabled()
  await page.keyboard.press('Escape')
  await placeholder.getByRole('button', { name: '更新 cURL', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '完整 cURL', exact: true })).toHaveValue('')
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await selectStatus(page, '禁用')
  await expect(page.locator('.request-row')).toHaveCount(2)
  const inherited = requestRow(page, '9000000000000000014')
  await expect(inherited.locator('.request-state')).toHaveText('启用')
  await expect(inherited.getByRole('button', { name: '禁用请求 继承停用请求', exact: true })).toBeVisible()
  await inherited.getByRole('button', { name: '继承停用请求的更多操作', exact: true }).click()
  await expect(page.getByRole('menuitem', { name: '执行', exact: true })).toBeDisabled()
  await expect(page.getByRole('menuitem', { name: '重新执行（忽略当日标记）', exact: true })).toBeDisabled()
  await page.keyboard.press('Escape')
  await selectStatus(page, '全部状态')
  await page.getByRole('textbox', { name: '搜索当前平台请求', exact: true }).fill('synthetic-private-rule')
  await expect(page.getByText('没有符合条件的账号或请求', { exact: true })).toBeVisible()
  await page.getByRole('textbox', { name: '搜索当前平台请求', exact: true }).fill('LONG-HOST')
  await expect(page.locator('.request-row')).toHaveCount(1)
  await noOverflow(page, 390)
  expect(revisionReads).toBe(0)
})

test('polls do not replace edit drafts or overwrite a newer saved catalogue', async ({ page, request }) => {
  test.setTimeout(90000)
  const platform = await createPlatform(request, '并发维护平台')
  const account = await createAccount(request, platform, '并发账号')
  const item = await createRequest(request, account, '初始名称')
  await page.goto('/#/platforms')
  await selectPlatform(page, platform.name)
  const row = requestRow(page, item.id)
  await row.getByRole('button', { name: '编辑', exact: true }).click()
  const nameInput = page.getByRole('textbox', { name: '请求名称', exact: true })
  await nameInput.fill('尚未保存的草稿')
  const original = await savedRequest(request, platform.id, item.id)
  await send(request, 'put', `/api/requests/${item.id}`, { ...original, name: '其他页面的修改' })
  await expect(row).toContainText('其他页面的修改')
  await expect(nameInput).toHaveValue('尚未保存的草稿')
  const conflict = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/requests/${item.id}`
    && response.request().method() === 'PUT')
  await page.getByRole('button', { name: '保存请求', exact: true }).click()
  expect((await conflict).status()).toBe(409)
  await expect(nameInput).toHaveValue('尚未保存的草稿')
  await expect(page.getByRole('dialog')).toBeVisible()
  expect((await savedRequest(request, platform.id, item.id)).name).toBe('其他页面的修改')
  await page.getByRole('button', { name: '取消', exact: true }).click()

  let release, captured, holdNext = true
  const gate = new Promise((resolve) => { release = resolve })
  const snapshotCaptured = new Promise((resolve) => { captured = resolve })
  await page.route('**/api/platforms', async (route) => {
    if (!holdNext) return route.continue()
    holdNext = false
    const old = await route.fetch()
    captured()
    await gate
    await route.fulfill({ response: old })
  })
  try {
    await snapshotCaptured
    await row.getByRole('button', { name: '编辑', exact: true }).click()
    await nameInput.fill('刷新后应保留的新名称')
    await page.getByRole('button', { name: '保存请求', exact: true }).click()
    await expect(row).toContainText('刷新后应保留的新名称')
    const staleResponse = nextCatalogueResponse(page)
    release()
    await (await staleResponse).finished()
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))))
    await expect(row).toContainText('刷新后应保留的新名称')
  } finally {
    release()
    await page.unrouteAll({ behavior: 'ignoreErrors' })
  }
})
