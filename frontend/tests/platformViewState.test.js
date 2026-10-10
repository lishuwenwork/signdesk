import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  needsAttention, isRequestDisabled, canExecuteRequest, requestCounts, filterAccounts,
  reconcileAccountState, isAccountExpanded, setAccountExpansion,
} from '../src/platformViewState.js'

function catalogue() {
  return {
    id: '9000000000000000001', enabled: true, accounts: [
      { id: '9000000000000000002', alias: '主账号', enabled: true, requests: [
        { id: '9000000000000000011', name: '每日签到', enabled: true, safeHost: 'Sign.Example', authPaused: false,
          method: 'POST', rules: { success: { contains: 'private-rule-value' } } },
        { id: '9000000000000000012', name: '待配置接口', enabled: false, safeHost: '', authPaused: true },
      ] },
      { id: '9000000000000000003', alias: '备用账号', enabled: false, requests: [
        { id: '9000000000000000013', name: '积分查询', enabled: true, safeHost: 'api.example', authPaused: false },
        { id: '9000000000000000014', name: '领取奖励', enabled: true, safeHost: 'api.example', authPaused: true },
      ] },
      { id: '9000000000000000004', alias: '空账号', enabled: true, requests: [] },
    ],
  }
}
const names = (groups) => groups.flatMap((group) => group.requests.map((request) => request.name))

function freeze(value) {
  if (value && typeof value === 'object') {
    Object.freeze(value)
    Object.values(value).forEach(freeze)
  }
  return value
}

test('attention is a credential/configuration state, independent of own or inherited disablement', () => {
  const platform = catalogue(), [main, spare] = platform.accounts
  assert.deepEqual(requestCounts(main.requests), { total: 2, attention: 1 })
  assert.equal(needsAttention({ safeHost: '', authPaused: false }), true)
  assert.equal(needsAttention(spare.requests[1]), true)
  assert.equal(isRequestDisabled(spare.requests[0], spare.enabled, platform.enabled), true)
  assert.equal(isRequestDisabled(main.requests[0], main.enabled, false), true)
  assert.equal(canExecuteRequest(main.requests[0], true, true), true)
  assert.equal(canExecuteRequest(main.requests[0], false, true), false)
  assert.equal(canExecuteRequest(main.requests[0], true, false), false)
  assert.equal(canExecuteRequest(main.requests[1], true, true), false)
  assert.equal(canExecuteRequest(spare.requests[1], true, true), false)
  assert.equal(needsAttention({ safeHost: 'example.test', authPaused: 0 }), false)
  assert.equal(needsAttention({ safeHost: 'example.test', authPaused: 1 }), true)
  assert.equal(canExecuteRequest({ enabled: 1, authPaused: 0, safeHost: 'example.test' }, 1, 1), true)
  assert.equal(canExecuteRequest({ enabled: 1, authPaused: 0, safeHost: '' }, 1, 1), false)
  for (const status of ['queued', 'running']) assert.equal(canExecuteRequest({ enabled: 1, authPaused: 0, safeHost: 'example.test', lastRun: { status } }, 1, 1), false)
  assert.equal(canExecuteRequest({ enabled: 0, authPaused: 0 }, 1, 1), false)
  assert.equal(canExecuteRequest({ enabled: 1, authPaused: 1 }, 1, 1), false)
})

test('all, attention and disabled filters retain their overlap and inherited parent gates', () => {
  const platform = catalogue()
  assert.equal(filterAccounts(platform).length, 3)
  assert.deepEqual(names(filterAccounts(platform, '', 'attention')), ['待配置接口', '领取奖励'])
  assert.deepEqual(names(filterAccounts(platform, '', 'disabled')), ['待配置接口', '积分查询', '领取奖励'])
  platform.enabled = false
  assert.equal(names(filterAccounts(platform, '', 'disabled')).length, 4)
})

test('search is trimmed, case-insensitive and limited to alias, request name and host', () => {
  const platform = freeze(catalogue())
  assert.deepEqual(names(filterAccounts(platform, '  sIgN.eXaMpLe  ')), ['每日签到'])
  assert.deepEqual(names(filterAccounts(platform, '签到')), ['每日签到'])
  assert.deepEqual(names(filterAccounts(platform, '备用账号')), ['积分查询', '领取奖励'])
  assert.deepEqual(names(filterAccounts(platform, '备用', 'attention')), ['领取奖励'])
  for (const query of ['private-rule-value', 'POST', platform.accounts[0].requests[0].id]) {
    assert.deepEqual(filterAccounts(platform, query), [])
  }
})

test('empty accounts appear only in all status with a matching alias or empty search', () => {
  const platform = catalogue()
  assert.equal(filterAccounts(platform, '空账号')[0].account.alias, '空账号')
  assert.deepEqual(filterAccounts(platform, '空账号')[0].requests, [])
  assert.deepEqual(filterAccounts(platform, '空账号', 'attention'), [])
  assert.deepEqual(filterAccounts(platform, '空账号', 'disabled'), [])
  assert.deepEqual(filterAccounts(platform, 'nothing-matches'), [])
  assert.deepEqual(filterAccounts(undefined), [])
})

test('matching never mutates the API tree and keeps account totals separate from matches', () => {
  const source = freeze(catalogue()), before = structuredClone(source)
  const [group] = filterAccounts(source, '签到')
  assert.equal(group.account.requests.length, 2)
  assert.equal(group.requests.length, 1)
  assert.deepEqual(requestCounts(group.account.requests), { total: 2, attention: 1 })
  assert.deepEqual(source, before)
})

test('first accounts initialize once; manual collapse-all survives polling and new accounts', () => {
  const accounts = catalogue().accounts
  let state = reconcileAccountState(null, [])
  assert.equal(state.initialized, false)
  state = reconcileAccountState(state, accounts)
  assert.deepEqual(state.expandedIds, [accounts[0].id])
  state = setAccountExpansion(state, accounts.map((account) => account.id), false)
  assert.deepEqual(state.expandedIds, [])
  const refreshed = reconcileAccountState(freeze(state), [...accounts, { id: '9000000000000000005' }])
  assert.equal(refreshed.initialized, true)
  assert.deepEqual(refreshed.expandedIds, [])
})

test('filtering has independent temporary collapse state and restores normal browsing on exit', () => {
  const accounts = catalogue().accounts, first = accounts[0].id, second = accounts[1].id
  let state = reconcileAccountState(null, accounts)
  assert.equal(isAccountExpanded(state, second, false), false)
  assert.equal(isAccountExpanded(state, second, true), true)
  state = setAccountExpansion(state, [first, second], false, true)
  assert.equal(isAccountExpanded(state, first, true), false)
  assert.equal(isAccountExpanded(state, second, true), false)
  assert.equal(isAccountExpanded(state, first, false), true)
  assert.equal(isAccountExpanded(state, second, false), false)
  state = reconcileAccountState(state, structuredClone(accounts))
  assert.equal(isAccountExpanded(state, second, true), false)
  state = { ...state, filteredCollapsedIds: [] }
  assert.equal(isAccountExpanded(state, second, true), true)
  assert.deepEqual(state.expandedIds, [first])
})

test('expand-all and collapse-all affect only visible IDs and do not mutate earlier state', () => {
  const accounts = catalogue().accounts
  const initial = freeze(reconcileAccountState(null, accounts))
  const expanded = setAccountExpansion(initial, accounts.map((account) => account.id), true)
  const collapsed = setAccountExpansion(expanded, [accounts[0].id], false)
  assert.deepEqual(initial.expandedIds, [accounts[0].id])
  assert.deepEqual(collapsed.expandedIds, [accounts[1].id, accounts[2].id])
  assert.equal(isAccountExpanded(collapsed, accounts[0].id, true), true)
})

test('reconcile drops removed IDs without opening another account or conflating snowflake strings', () => {
  const accounts = [{ id: '9223372036854775806' }, { id: '9223372036854775807' }]
  let state = reconcileAccountState(null, accounts)
  state = setAccountExpansion(state, [accounts[0].id], false, true)
  state = reconcileAccountState(state, [accounts[1]])
  assert.deepEqual(state.expandedIds, [])
  assert.deepEqual(state.filteredCollapsedIds, [])
  state = setAccountExpansion(state, [accounts[1].id], true)
  assert.deepEqual(state.expandedIds, ['9223372036854775807'])
  assert.deepEqual(reconcileAccountState(state, []).expandedIds, [])
})
