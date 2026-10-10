import { test, expect } from '@playwright/test'

const created = new Set()
async function api(request, method, url, data) {
  const response = await request[method](url, data === undefined ? {} : { data })
  expect(response.ok()).toBe(true)
  return response.json()
}
async function platform(request, name, enabled = true, note = '') {
  const result = await api(request, 'post', '/api/platforms', { name, note, enabled, version: 1 })
  created.add(result.id)
  return { ...result, name }
}
async function openPlatform(page, item) {
  await page.goto('/#/platforms')
  await page.locator('.platform-choice').filter({ hasText: item.name }).click()
  await expect(page.getByRole('heading', { name: item.name, level: 2, exact: true })).toBeVisible()
}
const summary = (page) => page.getByLabel('当前平台定时计划', { exact: true })
const planDialog = (page) => page.getByRole('dialog', { name: '编辑平台计划', exact: true })
const planCard = (page, id) => page.locator(`.plan-card[data-plan-id="${id}"]`)
async function requestMenu(page, row, action) {
  await row.getByRole('button', { name: /的更多操作$/ }).click()
  return page.getByRole('menuitem', { name: action, exact: true })
}
async function accountMenu(page, group, action) {
  await group.getByRole('button', { name: /的账号设置$/ }).click()
  return page.getByRole('menuitem', { name: action, exact: true })
}
async function pauseSettings(request) {
  const old = await api(request, 'get', '/api/settings')
  await api(request, 'put', '/api/settings', { ...old, paused: true })
  return async () => {
    const latest = await api(request, 'get', '/api/settings')
    await api(request, 'put', '/api/settings', { ...old, version: latest.version })
  }
}

test.beforeEach(async ({ baseURL }) => { expect(baseURL).toBe('http://127.0.0.1:18080') })
test.afterEach(async ({ playwright }) => {
  const cleanup = await playwright.request.newContext({ baseURL: 'http://127.0.0.1:18080' })
  try {
    for (const id of created) {
      expect((await cleanup.delete(`/api/platforms/${id}`, { data: {} })).ok()).toBe(true)
      created.delete(id)
    }
  } finally { await cleanup.dispose() }
})

test('platform counts stay in the chooser, state tags are consistent and notes are separate from plans', async ({ page, request }, info) => {
  const note = '这是一条备注，每日 09:00 不是计划设置。\n<img src=x onerror="window.__noteExecuted=true">'
  const item = await platform(request, '状态与统计平台', false, note)
  for (const [alias, enabled] of [['启用账号', true], ['禁用账号', false]]) {
    const account = await api(request, 'post', `/api/platforms/${item.id}/accounts`, { alias, enabled })
    await api(request, 'post', `/api/accounts/${account.id}/requests`, {
      name: `${alias}请求`, enabled: true, curl: "curl 'http://127.0.0.1:18081/check'",
    })
  }
  await openPlatform(page, item)
  const choice = page.locator('.platform-choice').filter({ hasText: item.name })
  await expect(choice.locator('.platform-choice-copy small')).toHaveText('2 个账号 · 2 个请求')
  await expect(choice.locator('.status-dot')).toHaveClass(/off/)
  await expect(page.locator('.catalog-overview')).not.toContainText('2 个账号 · 2 个请求')
  await expect(page.getByRole('switch', { name: '平台启用状态', exact: true })).not.toBeChecked()
  await expect(page.getByLabel('平台备注', { exact: true })).toContainText('备注')
  await expect(page.getByLabel('平台备注', { exact: true })).toContainText(note)
  await expect(page.locator('.catalog-note img')).toHaveCount(0)
  await expect(summary(page).locator('strong')).toHaveText('定时计划')
  await expect(summary(page).locator('.el-tag')).toHaveText('禁用')
  await expect(summary(page)).toContainText('执行策略：')
  await expect(summary(page)).not.toContainText(note)
  await expect(summary(page)).toContainText('平台已禁用，不会自动触发')
  await expect(page.locator('.catalog-overview')).not.toContainText('下一计划时刻')
  const active = page.getByRole('region', { name: '账号 启用账号', exact: true })
  await expect(active.locator('.state-text')).toHaveText('已禁用')
  await expect(active.locator('.state-caption')).toHaveText('平台未启用')
  await page.getByRole('button', { name: '全部收起', exact: true }).click()
  await expect(active.locator('.account-toggle .status-dot')).not.toHaveClass(/off/)
  await expect(page.getByRole('region', { name: '账号 禁用账号', exact: true }).locator('.account-toggle .status-dot')).toHaveClass(/off/)
  await page.screenshot({ path: info.outputPath('platform-status-plan.png'), fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('combobox', { name: '选择平台', exact: true })).toBeVisible()
  // Counts now live in the chooser on desktop and the account section on mobile.
  await expect(page.locator('.accounts-heading .section-count')).toHaveText('2 个账号 · 2 个请求')
  await page.locator('.platform-picker .el-select__wrapper').click()
  await page.getByRole('option', { name: item.name, exact: true }).click()
  await expect(page.getByRole('button', { name: '设置计划', exact: true })).toBeVisible()
  await expect(page.getByLabel('平台备注', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => window.__noteExecuted)).toBeUndefined()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
  await page.screenshot({ path: info.outputPath('platform-status-plan-mobile.png'), fullPage: true, animations: 'disabled' })
})

test('direct platform toggle preserves configuration and isolates duplicate or late writes', async ({ page, request }) => {
  const restore = await pauseSettings(request)
  const first = await platform(request, '直接开关平台', true, '切换状态不能覆盖这条备注')
  const second = await platform(request, '另一开关平台')
  let release, started
  const gate = new Promise((resolve) => { release = resolve })
  const captured = new Promise((resolve) => { started = resolve })
  const writes = []
  try {
    const oldPlan = await api(request, 'get', `/api/platforms/${first.id}/schedule`)
    await api(request, 'put', `/api/platforms/${first.id}/schedule`, { ...oldPlan, enabled: true })
    const plan = await api(request, 'get', `/api/platforms/${first.id}/schedule`)
    await openPlatform(page, first)
    const original = (await api(request, 'get', '/api/platforms')).find((p) => p.id === first.id)
    await expect(summary(page).locator('.el-tag')).toHaveText('启用')
    await page.route(`**/api/platforms/${first.id}`, async (route) => {
      if (route.request().method() !== 'PUT') return route.continue()
      writes.push(route.request().postDataJSON())
      started()
      await gate
      const response = await route.fetch()
      await route.fulfill({ response })
    })
    const completed = page.waitForResponse((r) => r.request().method() === 'PUT'
      && new URL(r.url()).pathname === `/api/platforms/${first.id}`)
    await page.locator('.platform-enable').click()
    await captured
    await expect(page.getByRole('switch', { name: '平台启用状态', exact: true })).toBeDisabled()
    await expect(page.getByRole('switch', { name: '平台启用状态', exact: true })).toBeChecked()
    await page.locator('.platform-enable').dispatchEvent('click')
    expect(writes).toEqual([{ name: first.name, note: original.note, enabled: false, version: original.version }])
    await page.locator('.platform-choice').filter({ hasText: second.name }).click()
    await expect(page.getByRole('switch', { name: '平台启用状态', exact: true })).toBeEnabled()
    release()
    expect((await completed).status()).toBe(200)
    await expect(page.locator('.platform-choice').filter({ hasText: first.name }).locator('.status-dot')).toHaveClass(/off/)
    await expect(page.getByRole('heading', { name: second.name, level: 2, exact: true })).toBeVisible()
    await expect(page.getByRole('switch', { name: '平台启用状态', exact: true })).toBeChecked()
    const disabled = (await api(request, 'get', '/api/platforms')).find((p) => p.id === first.id)
    expect(disabled).toMatchObject({ enabled: 0, name: first.name, note: original.note, version: original.version + 1 })
    expect((await api(request, 'get', '/api/platforms')).find((p) => p.id === second.id).enabled).toBe(1)
    expect(await api(request, 'get', `/api/platforms/${first.id}/schedule`)).toEqual(plan)
    expect((await api(request, 'get', '/api/settings')).paused).toBe(true)
    await page.locator('.platform-choice').filter({ hasText: first.name }).click()
    await expect(summary(page)).toContainText('平台已禁用，不会自动触发')
    await expect(summary(page).locator('.el-tag')).toHaveText('启用')
    await expect(page.getByRole('switch', { name: '平台启用状态', exact: true })).toBeEnabled()
    await page.locator('.platform-enable').click()
    await expect(page.getByRole('switch', { name: '平台启用状态', exact: true })).toBeChecked()
    await expect(page.getByRole('switch', { name: '平台启用状态', exact: true })).toBeEnabled()
    expect(writes).toHaveLength(2)
    expect(writes[1]).toMatchObject({ enabled: true, version: original.version + 1 })
    expect(await api(request, 'get', `/api/platforms/${first.id}/schedule`)).toEqual(plan)
    expect((await api(request, 'get', '/api/settings')).paused).toBe(true)
  } finally {
    release()
    await page.unrouteAll({ behavior: 'ignoreErrors' })
    await restore()
  }
})

for (const status of [500, 409]) {
  test(`failed platform toggle at ${status} retains server state without retrying or overwriting`, async ({ page, request }) => {
    const item = await platform(request, `开关失败平台${status}`, true, '原始备注')
    const tree = await api(request, 'get', '/api/platforms')
    const original = tree.find((p) => p.id === item.id)
    // Keep the displayed version stale so the 409 exercises the real optimistic lock.
    await page.route('**/api/platforms', (route) => route.fulfill({ json: tree }))
    await openPlatform(page, item)
    if (status === 409) await api(request, 'put', `/api/platforms/${item.id}`, {
      name: original.name, note: '另一个页面的新备注', enabled: true, version: original.version,
    })
    let writes = 0
    await page.route(`**/api/platforms/${item.id}`, async (route) => {
      if (route.request().method() !== 'PUT') return route.continue()
      writes++
      if (status === 500) return route.fulfill({ status, json: { message: '合成平台状态保存失败' } })
      const response = await route.fetch()
      await route.fulfill({ response })
    })
    const failed = page.waitForResponse((r) => r.request().method() === 'PUT'
      && new URL(r.url()).pathname === `/api/platforms/${item.id}`)
    await page.locator('.platform-enable').click()
    expect((await failed).status()).toBe(status)
    await expect(page.locator('.el-message--error')).toBeVisible()
    await expect(page.getByRole('switch', { name: '平台启用状态', exact: true })).toBeEnabled()
    await expect(page.getByRole('switch', { name: '平台启用状态', exact: true })).toBeChecked()
    await expect(page.locator('.platform-choice').filter({ hasText: item.name }).locator('.status-dot')).not.toHaveClass(/off/)
    expect(writes).toBe(1)
    const stored = (await api(request, 'get', '/api/platforms')).find((p) => p.id === item.id)
    expect(stored).toMatchObject({ enabled: 1, note: status === 409 ? '另一个页面的新备注' : original.note,
      version: original.version + (status === 409 ? 1 : 0) })
  })
}

for (const width of [1440, 390]) {
  test(`account toggle works outside folded content without changing requests at ${width}px`, async ({ page, request }, info) => {
    const item = await platform(request, `账号开关平台${width}`)
    const alias = '外部开关账号'
    const account = await api(request, 'post', `/api/platforms/${item.id}/accounts`, { alias, enabled: false })
    const savedRequest = await api(request, 'post', `/api/accounts/${account.id}/requests`, {
      name: '保留启用的请求', enabled: true, curl: "curl 'http://127.0.0.1:18081/check'",
    })
    const tree = await api(request, 'get', '/api/platforms')
    const original = tree.find((p) => p.id === item.id).accounts.find((a) => a.id === account.id)
    const plan = await api(request, 'get', `/api/platforms/${item.id}/schedule`)
    await openPlatform(page, item)
    await page.setViewportSize({ width, height: 1000 })
    await page.getByRole('button', { name: '全部收起', exact: true }).click()
    const group = page.getByRole('region', { name: `账号 ${alias}`, exact: true })
    const control = group.locator('.account-enable-action')
    const state = group.locator('.account-toggle .status-dot')
    const mobile = width === 390
    await expect(group.locator('.account-body')).toBeHidden()
    await expect(state).toHaveClass(/off/)
    await expect(group.locator('.el-switch')).toHaveCount(0)
    if (mobile) {
      await expect(control).toBeHidden()
      await expect(await accountMenu(page, group, '启用账号')).toBeEnabled()
      await page.keyboard.press('Escape')
    } else {
      await expect(control).toHaveAccessibleName(`启用账号 ${alias}`)
      await expect(control).toHaveText('启用')
    }
    let release, started
    const gate = new Promise((resolve) => { release = resolve })
    const captured = new Promise((resolve) => { started = resolve })
    const writes = []
    await page.route(`**/api/accounts/${account.id}`, async (route) => {
      if (route.request().method() !== 'PUT') return route.continue()
      writes.push(route.request().postDataJSON())
      started()
      await gate
      const response = await route.fetch()
      await route.fulfill({ response })
    })
    try {
      await state.click()
      expect(writes).toHaveLength(0)
      // The account status dot is inside the folding button, never the enable action.
      await expect(group.locator('.account-body')).toBeVisible()
      await group.locator('.account-toggle').click()
      await expect(group.locator('.account-body')).toBeHidden()
      let busyAction
      if (mobile) {
        const enable = await accountMenu(page, group, '启用账号')
        await enable.focus()
        await enable.press('Enter')
      } else {
        await control.focus()
        await control.press('Enter')
      }
      await captured
      if (mobile) {
        busyAction = await accountMenu(page, group, '启用账号')
        await expect(busyAction).toBeDisabled()
      } else {
        busyAction = control
        await expect(control).toBeDisabled()
        await expect(control).toHaveAttribute('aria-busy', 'true')
        await expect(control.locator('.el-icon.is-loading')).toBeVisible()
        await expect(control).toHaveText('启用')
      }
      await busyAction.dispatchEvent('click')
      expect(writes).toEqual([{ alias, enabled: true, version: original.version }])
      await expect(group.locator('.account-body')).toBeHidden()
      if (mobile) await page.keyboard.press('Escape')
      release()
      await expect(state).not.toHaveClass(/off/)
      await expect(group.locator('.account-body')).toBeHidden()
      if (mobile) {
        const disable = await accountMenu(page, group, '禁用账号')
        await expect(disable).toBeEnabled()
        await disable.focus()
        await disable.press('Enter')
      } else {
        await expect(control).toHaveText('禁用')
        await expect(control).toBeEnabled()
        await expect(control).toHaveAccessibleName(`禁用账号 ${alias}`)
        await control.focus()
        await control.press('Space')
      }
      await expect(state).toHaveClass(/off/)
      if (mobile) {
        await expect(await accountMenu(page, group, '启用账号')).toBeEnabled()
        await page.keyboard.press('Escape')
      } else {
        await expect(control).toHaveText('启用')
        await expect(control).toBeEnabled()
      }
      await expect(group.locator('.account-body')).toBeHidden()
      expect(writes).toHaveLength(2)
      expect(writes[1]).toEqual({ alias, enabled: false, version: original.version + 1 })
      const stored = (await api(request, 'get', '/api/platforms')).find((p) => p.id === item.id).accounts.find((a) => a.id === account.id)
      expect(stored).toMatchObject({ alias, enabled: 0, version: original.version + 2 })
      expect(stored.requests).toEqual(original.requests)
      expect(await api(request, 'get', `/api/platforms/${item.id}/schedule`)).toEqual(plan)
      await group.locator('.account-toggle').click()
      const row = group.locator(`[data-request-id="${savedRequest.id}"]`)
      await expect(row.locator('.state-text')).toHaveText('已禁用')
      await expect(await requestMenu(page, row, '禁用请求')).toBeEnabled()
      await expect(page.getByRole('menuitem', { name: '执行', exact: true })).toBeDisabled()
      await page.keyboard.press('Escape')
      await page.getByRole('heading', { name: '平台与账号', level: 1, exact: true }).click()
      await expect(page.getByRole('menuitem', { name: '执行', exact: true })).toBeHidden()
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width + 1)
      await page.screenshot({ path: info.outputPath(`account-enable-action-${width}.png`), fullPage: true, animations: 'disabled' })
    } finally {
      release()
      await page.unrouteAll({ behavior: 'ignoreErrors' })
    }
  })
}

for (const status of [500, 409]) {
  test(`failed account toggle at ${status} preserves state and does not overwrite a newer alias`, async ({ page, request }) => {
    const item = await platform(request, `账号开关失败${status}`)
    const alias = '原始账号别名'
    const account = await api(request, 'post', `/api/platforms/${item.id}/accounts`, { alias, enabled: true })
    const tree = await api(request, 'get', '/api/platforms')
    const original = tree.find((p) => p.id === item.id).accounts.find((a) => a.id === account.id)
    await page.route('**/api/platforms', (route) => route.fulfill({ json: tree }))
    await openPlatform(page, item)
    await page.getByRole('button', { name: '全部收起', exact: true }).click()
    if (status === 409) await api(request, 'put', `/api/accounts/${account.id}`, {
      alias: '另一个页面的新别名', enabled: true, version: original.version,
    })
    let writes = 0
    await page.route(`**/api/accounts/${account.id}`, async (route) => {
      if (route.request().method() !== 'PUT') return route.continue()
      writes++
      if (status === 500) return route.fulfill({ status, json: { message: '合成账号状态保存失败' } })
      const response = await route.fetch()
      await route.fulfill({ response })
    })
    const group = page.getByRole('region', { name: `账号 ${alias}`, exact: true })
    const failed = page.waitForResponse((r) => r.request().method() === 'PUT'
      && new URL(r.url()).pathname === `/api/accounts/${account.id}`)
    await group.locator('.account-enable-action').click()
    expect((await failed).status()).toBe(status)
    await expect(page.locator('.el-message--error')).toBeVisible()
    const control = group.locator('.account-enable-action')
    await expect(control).toBeEnabled()
    await expect(control).toHaveText('禁用')
    await expect(group.locator('.account-toggle .status-dot')).not.toHaveClass(/off/)
    await expect(group.locator('.account-body')).toBeHidden()
    expect(writes).toBe(1)
    const stored = (await api(request, 'get', '/api/platforms')).find((p) => p.id === item.id).accounts.find((a) => a.id === account.id)
    expect(stored).toMatchObject({ enabled: 1, alias: status === 409 ? '另一个页面的新别名' : alias,
      version: original.version + (status === 409 ? 1 : 0) })
  })
}

for (const width of [1440, 390]) {
  test(`expanded accounts separate read-only today status from explicit menu enable actions at ${width}px`, async ({ page, request }, info) => {
    const item = await platform(request, `状态标签平台${width}`)
    for (const [alias, enabled] of [['主账号', true], ['备用账号', false]]) {
      const account = await api(request, 'post', `/api/platforms/${item.id}/accounts`, { alias, enabled })
      for (const [index, name] of ['每日签到', '查询积分', '查询状态'].entries()) {
        await api(request, 'post', `/api/accounts/${account.id}/requests`, {
          name, enabled: index !== 1, curl: "curl 'http://127.0.0.1:18081/check'",
        })
      }
    }
    await openPlatform(page, item)
    await page.setViewportSize({ width, height: 1000 })
    await page.getByRole('button', { name: '全部展开', exact: true }).click()
    await expect(page.locator('.account-body:visible')).toHaveCount(2)
    await expect(page.locator('.account-toggle > .status-dot')).toHaveCount(2)
    await expect(page.locator('.request-state')).toHaveCount(6)
    await expect(page.locator('.account-card .el-switch')).toHaveCount(0)
    await expect(page.locator('button button')).toHaveCount(0)
    await expect(page.getByRole('switch', { name: '平台启用状态', exact: true })).toBeChecked()
    await expect(page.locator('.request-state button, button.request-state')).toHaveCount(0)
    for (const row of await page.locator('.request-row').all()) {
      await expect(row.locator('.request-state')).toHaveCount(1)
      await expect(row.getByRole('button', { name: '更新 cURL', exact: true })).toBeVisible()
      await expect(await requestMenu(page, row, '编辑名称 / 规则')).toBeVisible()
      await expect(page.getByRole('menuitem', { name: '删除请求', exact: true })).toBeVisible()
      await expect(page.getByRole('menuitem', { name: /^(启用|禁用)请求$/ })).toBeEnabled()
      await page.keyboard.press('Escape')
    }
    const main = page.getByRole('region', { name: '账号 主账号', exact: true })
    const row = main.getByRole('group', { name: '请求 每日签到', exact: true })
    const original = (await api(request, 'get', '/api/platforms')).find((p) => p.id === item.id).accounts.find((a) => a.alias === '主账号').requests.find((r) => r.name === '每日签到')
    let writes = 0
    page.on('request', (r) => { if (r.method() === 'PUT' && /\/api\/requests\//.test(new URL(r.url()).pathname)) writes++ })
    await row.locator('.request-state').click()
    expect(writes).toBe(0)
    await expect(row.locator('.state-text')).toHaveText('今日未执行')
    const disable = await requestMenu(page, row, '禁用请求')
    await disable.focus()
    await disable.press('Enter')
    await expect(row.locator('.state-text')).toHaveText('已禁用')
    const enable = await requestMenu(page, row, '启用请求')
    await expect(enable).toBeEnabled()
    await enable.focus()
    await enable.press('Enter')
    await expect(row.locator('.state-text')).toHaveText('今日未执行')
    await expect(await requestMenu(page, row, '禁用请求')).toBeEnabled()
    await page.keyboard.press('Escape')
    expect(writes).toBe(2)
    const stored = (await api(request, 'get', '/api/platforms')).find((p) => p.id === item.id).accounts.find((a) => a.alias === '主账号').requests.find((r) => r.id === original.id)
    expect(stored).toMatchObject({ enabled: 1, version: original.version + 2, currentRevision: original.currentRevision, rules: original.rules })
    await expect(main.locator('.account-toggle')).toHaveAttribute('aria-expanded', 'true')
    if (width === 390) {
      for (const control of await page.locator('.account-actions .icon-button, .row-actions .icon-button').all()) {
        const bounds = await control.boundingBox()
        expect(bounds.width).toBeGreaterThanOrEqual(36)
        expect(bounds.height).toBeGreaterThanOrEqual(36)
      }
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width + 1)
    await page.getByRole('heading', { name: '平台与账号', level: 1, exact: true }).click()
    await page.screenshot({ path: info.outputPath(`expanded-enable-actions-${width}.png`), fullPage: true, animations: 'disabled' })
  })
}

for (const status of [500, 409]) {
  test(`request enable action handles loading and ${status} without duplicate or stale writes`, async ({ page, request }) => {
    const item = await platform(request, `请求标签失败${status}`)
    const account = await api(request, 'post', `/api/platforms/${item.id}/accounts`, { alias: '标签测试账号', enabled: true })
    const saved = await api(request, 'post', `/api/accounts/${account.id}/requests`, {
      name: '标签测试请求', enabled: true, curl: "curl 'http://127.0.0.1:18081/check'",
    })
    const tree = await api(request, 'get', '/api/platforms')
    const original = tree.find((p) => p.id === item.id).accounts.find((a) => a.id === account.id).requests.find((r) => r.id === saved.id)
    await page.route('**/api/platforms', (route) => route.fulfill({ json: tree }))
    await openPlatform(page, item)
    if (status === 409) await api(request, 'put', `/api/requests/${saved.id}`, {
      name: '另一页面的新请求名称', enabled: true, rules: original.rules, version: original.version,
    })
    let release, started, writes = 0
    const gate = new Promise((resolve) => { release = resolve })
    const captured = new Promise((resolve) => { started = resolve })
    await page.route(`**/api/requests/${saved.id}`, async (route) => {
      if (route.request().method() !== 'PUT') return route.continue()
      writes++
      started()
      await gate
      if (status === 500) return route.fulfill({ status, json: { message: '合成请求状态保存失败' } })
      const response = await route.fetch()
      await route.fulfill({ response })
    })
    const row = page.locator(`[data-request-id="${saved.id}"]`)
    try {
      const failed = page.waitForResponse((r) => r.request().method() === 'PUT' && new URL(r.url()).pathname === `/api/requests/${saved.id}`)
      await (await requestMenu(page, row, '禁用请求')).click()
      await captured
      const busyAction = await requestMenu(page, row, '禁用请求')
      await expect(busyAction).toBeDisabled()
      await expect(page.getByRole('menuitem', { name: '编辑名称 / 规则', exact: true })).toBeDisabled()
      await expect(page.getByRole('menuitem', { name: '执行', exact: true })).toBeDisabled()
      await expect(row.getByRole('button', { name: '更新 cURL', exact: true })).toBeDisabled()
      await expect(row.locator('.state-text')).toHaveText('今日未执行')
      await busyAction.dispatchEvent('click')
      expect(writes).toBe(1)
      await page.keyboard.press('Escape')
      release()
      expect((await failed).status()).toBe(status)
      await expect(page.locator('.el-message--error')).toBeVisible()
      await expect(await requestMenu(page, row, '禁用请求')).toBeEnabled()
      await expect(row.getByRole('button', { name: '更新 cURL', exact: true })).toBeEnabled()
      await expect(row.locator('.state-text')).toHaveText('今日未执行')
      await page.keyboard.press('Escape')
      expect(writes).toBe(1)
      const stored = (await api(request, 'get', '/api/platforms')).find((p) => p.id === item.id).accounts.find((a) => a.id === account.id).requests.find((r) => r.id === saved.id)
      expect(stored).toMatchObject({ enabled: 1, name: status === 409 ? '另一页面的新请求名称' : original.name,
        version: original.version + (status === 409 ? 1 : 0), currentRevision: original.currentRevision, rules: original.rules })
    } finally {
      release()
      await page.unrouteAll({ behavior: 'ignoreErrors' })
    }
  })
}

test('added time tags appear below the time picker in both schedule entry points', async ({ page, request }, info) => {
  const item = await platform(request, '时刻标签布局平台')
  const old = await api(request, 'get', `/api/platforms/${item.id}/schedule`)
  await api(request, 'put', `/api/platforms/${item.id}/schedule`, { ...old, times: ['09:00', '20:30'] })
  await openPlatform(page, item)
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 1000 })
    if (width === 1440) await page.getByRole('button', { name: '设置计划', exact: true }).click()
    else {
      await page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '定时计划', exact: true }).click()
      await page.locator('.plan-card[data-plan-id]').filter({ hasText: item.name }).getByRole('button', { name: '编辑计划', exact: true }).click()
    }
    const dialog = planDialog(page), tags = dialog.getByLabel('已添加时刻', { exact: true })
    await expect(tags.locator('.el-tag')).toHaveText(['09:00', '20:30'])
    const entryBounds = await dialog.locator('.schedule-time-entry').boundingBox()
    const tagsBounds = await tags.boundingBox()
    expect(tagsBounds.y).toBeGreaterThan(entryBounds.y + entryBounds.height)
    await tags.locator('.el-tag').filter({ hasText: '20:30' }).locator('.el-tag__close').click()
    await expect(tags.locator('.el-tag')).toHaveText(['09:00'])
    const input = dialog.getByPlaceholder('选择时间', { exact: true })
    await input.fill('20:30')
    await input.press('Enter')
    await dialog.getByRole('button', { name: '添加时刻', exact: true }).click()
    await expect(tags.locator('.el-tag')).toHaveText(['09:00', '20:30'])
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width + 1)
    await page.screenshot({ path: info.outputPath(`schedule-times-below-${width}.png`), fullPage: true, animations: 'disabled' })
    await dialog.getByRole('button', { name: '取消', exact: true }).click()
    expect((await api(request, 'get', `/api/platforms/${item.id}/schedule`)).times).toEqual(['09:00', '20:30'])
  }
})

test('inline schedule editing stays on the platform page, retains pause and synchronizes both views', async ({ page, request }, info) => {
  const restore = await pauseSettings(request)
  const item = await platform(request, '原页计划平台')
  const other = await platform(request, '未修改计划平台')
  try {
    await openPlatform(page, item)
    await expect(summary(page).locator('.el-tag')).toHaveText('禁用')
    await expect(summary(page)).toContainText('全局定时已暂停')
    await expect(page.getByRole('switch', { name: '平台启用状态', exact: true })).toBeChecked()
    let writes = 0
    page.on('request', (r) => { if (r.method() === 'PUT' && new URL(r.url()).pathname.endsWith('/schedule')) writes++ })
    await page.getByRole('button', { name: '设置计划', exact: true }).click()
    await expect(planDialog(page)).toContainText(item.name)
    await expect(planDialog(page).getByRole('button', { name: '保存计划', exact: true })).toBeEnabled()
    await planDialog(page).getByRole('button', { name: '取消', exact: true }).click()
    expect(writes).toBe(0)
    await expect(page).toHaveURL(/#\/platforms$/)

    await page.getByRole('button', { name: '设置计划', exact: true }).click()
    await expect(planDialog(page).getByRole('button', { name: '保存计划', exact: true })).toBeEnabled()
    await planDialog(page).locator('.el-switch').first().click()
    await planDialog(page).getByRole('spinbutton', { name: '同平台请求间隔（秒）', exact: true }).fill('7')
    await planDialog(page).getByRole('button', { name: '保存计划', exact: true }).click()
    await expect(planDialog(page)).toBeHidden()
    await expect(page).toHaveURL(/#\/platforms$/)
    await expect(summary(page).locator('.el-tag')).toHaveText('启用')
    await expect(summary(page)).toContainText('间隔 7 秒')
    await expect(summary(page)).not.toContainText('下一计划时刻')
    expect(writes).toBe(1)
    const saved = await api(request, 'get', `/api/platforms/${item.id}/schedule`)
    expect(saved).toMatchObject({ enabled: true, revision: 2, intervalSeconds: 7, skipCompletedDaily: true })
    expect((await api(request, 'get', `/api/platforms/${other.id}/schedule`)).revision).toBe(1)
    expect((await api(request, 'get', '/api/settings')).paused).toBe(true)
    await page.screenshot({ path: info.outputPath('platform-inline-plan.png'), fullPage: true, animations: 'disabled' })
    await page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '定时计划', exact: true }).click()
    const row = planCard(page, item.id)
    await expect(row).toContainText('间隔 7 秒')
    await row.getByRole('button', { name: '编辑计划', exact: true }).click()
    await expect(planDialog(page).getByRole('spinbutton', { name: '同平台请求间隔（秒）', exact: true })).toHaveValue('7')
    await planDialog(page).getByRole('button', { name: '取消', exact: true }).click()
  } finally { await restore() }
})

test('schedule draft survives polling and conflicts without silently replacing revision', async ({ page, request }) => {
  const item = await platform(request, '计划冲突平台')
  await openPlatform(page, item)
  await page.getByRole('button', { name: '设置计划', exact: true }).click()
  const interval = planDialog(page).getByRole('spinbutton', { name: '同平台请求间隔（秒）', exact: true })
  await expect(interval).toHaveValue('2')
  await interval.fill('5')
  const old = await api(request, 'get', `/api/platforms/${item.id}/schedule`)
  await api(request, 'put', `/api/platforms/${item.id}/schedule`, { ...old, intervalSeconds: 10 })
  await expect(summary(page)).toContainText('间隔 10 秒')
  await expect(interval).toHaveValue('5')
  const conflict = page.waitForResponse((r) => r.request().method() === 'PUT' && new URL(r.url()).pathname === `/api/platforms/${item.id}/schedule`)
  await planDialog(page).getByRole('button', { name: '保存计划', exact: true }).click()
  expect((await conflict).status()).toBe(409)
  await expect(interval).toHaveValue('5')
  await expect(planDialog(page)).toBeVisible()
  expect((await api(request, 'get', `/api/platforms/${item.id}/schedule`)).intervalSeconds).toBe(10)
  await planDialog(page).getByRole('button', { name: '取消', exact: true }).click()
})

test('failed or missing plans never block the catalogue or produce a fake editable default', async ({ page, request }) => {
  const item = await platform(request, '计划失败平台')
  await api(request, 'post', `/api/platforms/${item.id}/accounts`, { alias: '保留账号', enabled: true })
  await page.route('**/api/schedules', (route) => route.fulfill({ status: 500, json: { message: '合成计划读取失败' } }))
  await openPlatform(page, item)
  await expect(summary(page)).toContainText('计划读取失败')
  await expect(page.getByRole('region', { name: '账号 保留账号', exact: true })).toBeVisible()
  await page.unroute('**/api/schedules')
  await page.route('**/api/schedules', (route) => route.fulfill({ json: [] }))
  await summary(page).getByRole('button', { name: '重试读取', exact: true }).click()
  await expect(summary(page)).toContainText('未找到计划')
  await page.route(`**/api/platforms/${item.id}/schedule`, (route) => route.fulfill({ status: 404, json: { message: '不存在' } }))
  await page.getByRole('button', { name: '设置计划', exact: true }).click()
  await expect(planDialog(page)).toContainText('该平台的计划不存在，无法编辑。')
  await expect(planDialog(page).getByRole('button', { name: '保存计划', exact: true })).toBeDisabled()
})

test('a late schedule load cannot refill a newly opened platform editor', async ({ page, request }) => {
  const first = await platform(request, '延迟计划平台'), second = await platform(request, '新计划平台')
  let release, started, finished
  const gate = new Promise((resolve) => { release = resolve })
  const captured = new Promise((resolve) => { started = resolve })
  const done = new Promise((resolve) => { finished = resolve })
  await page.route(`**/api/platforms/${first.id}/schedule`, async (route) => {
    const response = await route.fetch()
    started()
    await gate
    try { await route.fulfill({ response }) } finally { finished() }
  })
  try {
    await openPlatform(page, first)
    await page.getByRole('button', { name: '设置计划', exact: true }).click()
    await captured
    await planDialog(page).getByRole('button', { name: '取消', exact: true }).click()
    await page.locator('.platform-choice').filter({ hasText: second.name }).click()
    await page.getByRole('button', { name: '设置计划', exact: true }).click()
    await expect(planDialog(page)).toContainText(second.name)
    await expect(planDialog(page).getByRole('button', { name: '保存计划', exact: true })).toBeEnabled()
    release()
    await done
    await expect(planDialog(page)).toContainText(second.name)
    await expect(planDialog(page).getByRole('spinbutton', { name: '同平台请求间隔（秒）', exact: true })).toHaveValue('2')
  } finally {
    release()
    await page.unrouteAll({ behavior: 'ignoreErrors' })
  }
})

test('empty-time validation does not write and a late save cannot close a different editor', async ({ page, request }) => {
  const first = await platform(request, '延迟保存平台'), second = await platform(request, '独立编辑平台')
  await openPlatform(page, first)
  await page.getByRole('button', { name: '设置计划', exact: true }).click()
  await expect(planDialog(page).getByRole('button', { name: '保存计划', exact: true })).toBeEnabled()
  await planDialog(page).locator('.el-tag__close').click()
  let writes = 0
  const countWrite = (r) => { if (r.method() === 'PUT' && new URL(r.url()).pathname.endsWith('/schedule')) writes++ }
  page.on('request', countWrite)
  await planDialog(page).getByRole('button', { name: '保存计划', exact: true }).click()
  await expect(page.getByText('请至少设置一个时间点', { exact: true })).toBeVisible()
  expect(writes).toBe(0)
  await planDialog(page).getByRole('button', { name: '取消', exact: true }).click()
  await page.getByRole('button', { name: '设置计划', exact: true }).click()
  await expect(planDialog(page).getByRole('button', { name: '保存计划', exact: true })).toBeEnabled()

  let release, started, finished
  const gate = new Promise((resolve) => { release = resolve })
  const captured = new Promise((resolve) => { started = resolve })
  const done = new Promise((resolve) => { finished = resolve })
  await page.route(`**/api/platforms/${first.id}/schedule`, async (route) => {
    if (route.request().method() !== 'PUT') return route.continue()
    const response = await route.fetch()
    started()
    await gate
    try { await route.fulfill({ response }) } finally { finished() }
  })
  try {
    await planDialog(page).getByRole('spinbutton', { name: '同平台请求间隔（秒）', exact: true }).fill('3')
    await planDialog(page).getByRole('button', { name: '保存计划', exact: true }).click()
    await captured
    await expect(planDialog(page).getByRole('button', { name: '取消', exact: true })).toBeDisabled()
    // Changing the hash models browser navigation while the server has accepted the old save.
    await page.evaluate(() => { location.hash = '/schedules' })
    const row = planCard(page, second.id)
    await row.getByRole('button', { name: '编辑计划', exact: true }).click()
    await expect(planDialog(page)).toContainText(second.name)
    await expect(planDialog(page).getByRole('button', { name: '保存计划', exact: true })).toBeEnabled()
    release()
    await done
    await expect(planDialog(page)).toBeVisible()
    await expect(planDialog(page)).toContainText(second.name)
    await expect(planDialog(page).getByRole('spinbutton', { name: '同平台请求间隔（秒）', exact: true })).toHaveValue('2')
    expect((await api(request, 'get', `/api/platforms/${first.id}/schedule`)).intervalSeconds).toBe(3)
    expect((await api(request, 'get', `/api/platforms/${second.id}/schedule`)).revision).toBe(1)
  } finally {
    release()
    await page.unrouteAll({ behavior: 'ignoreErrors' })
  }
})
