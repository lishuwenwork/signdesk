import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRuleDraft, collectRules } from '../src/resultRules.js'

test('missing rules use defaults but an explicitly disabled success rule stays disabled', () => {
  assert.deepEqual(collectRules(createRuleDraft()), {
    success: { path: 'code', value: 0, contains: null },
    alreadyDone: null, expired: null, failed: null, failureOnSuccessMismatch: false,
  })
  const source = { success: null, alreadyDone: { path: 'done', value: true, contains: null }, expired: null, failed: null }
  assert.deepEqual(collectRules(createRuleDraft(source)), { ...source, failureOnSuccessMismatch: false })
})

test('template drafts keep JSON scalar types and never mutate their source', () => {
  const source = {
    success: { path: 'code', value: '0', contains: null },
    alreadyDone: { path: 'count', value: 0, contains: 'done' },
    expired: { path: 'auth', value: null, contains: null },
    failed: { path: null, value: null, contains: 'error' },
  }
  const first = createRuleDraft(source), second = createRuleDraft(source)
  assert.deepEqual(collectRules(first), { ...source, failed: { ...source.failed, path: '' }, failureOnSuccessMismatch: false })
  first.success.valueText = '99'
  assert.equal(source.success.value, '0')
  assert.equal(collectRules(second).success.value, '0')
  assert.equal(collectRules(first).success.value, 99)
})

test('empty categories are null and invalid or missing JSON values prevent saving', () => {
  const draft = createRuleDraft({})
  assert.deepEqual(collectRules(draft), { success: null, alreadyDone: null, expired: null, failed: null, failureOnSuccessMismatch: false })
  draft.success.path = 'code'
  assert.throws(() => collectRules(draft), /需填写 JSON/)
  draft.success.valueText = 'not-json'
  assert.throws(() => collectRules(draft), /合法 JSON/)
  draft.success.valueText = 'false'
  assert.equal(collectRules(draft).success.value, false)
})

test('inverse failure is explicitly optional and preserved in independent template drafts', () => {
  assert.equal(collectRules(createRuleDraft()).failureOnSuccessMismatch, false)
  for (const value of [0, '0', true, false]) {
    const source = { success: { path: 'code', value }, failureOnSuccessMismatch: true }
    const first = createRuleDraft(source), second = createRuleDraft(source)
    assert.equal(collectRules(first).failureOnSuccessMismatch, true)
    assert.equal(collectRules(first).success.value, value)
    first.failureOnSuccessMismatch = false
    assert.equal(collectRules(second).failureOnSuccessMismatch, true)
    assert.equal(source.failureOnSuccessMismatch, true)
  }
  assert.equal(collectRules(createRuleDraft({ failureOnSuccessMismatch: null })).failureOnSuccessMismatch, false)
})

test('inverse failure rejects missing, null, textual or combined success rules without changing the choice', () => {
  const invalid = [
    { path: '', valueText: '0', contains: '' },
    { path: 'code', valueText: 'null', contains: '' },
    { path: '', valueText: '', contains: 'success' },
    { path: 'code', valueText: '0', contains: 'success' },
    { path: 'bad[*]', valueText: '0', contains: '' },
    { path: 'code', valueText: 'not-json', contains: '' },
    { path: 'code', valueText: '{}', contains: '' },
    { path: 'code', valueText: '[]', contains: '' },
    { path: 'code', valueText: '1e400', contains: '' },
  ]
  for (const success of invalid) {
    const draft = createRuleDraft()
    draft.success = success
    draft.failureOnSuccessMismatch = true
    assert.throws(() => collectRules(draft))
    assert.equal(draft.failureOnSuccessMismatch, true)
  }
  const legacy = createRuleDraft({ success: { path: 'code', value: null } })
  assert.equal(collectRules(legacy).success.value, null)
  legacy.success.contains = 'done'
  assert.equal(collectRules(legacy).success.contains, 'done')
})
