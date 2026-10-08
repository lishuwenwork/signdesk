import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRuleDraft, collectRules } from '../src/resultRules.js'

test('missing rules use defaults but an explicitly disabled success rule stays disabled', () => {
  assert.deepEqual(collectRules(createRuleDraft()), {
    success: { path: 'code', value: 0, contains: null },
    alreadyDone: null, expired: null, failed: null,
  })
  const source = { success: null, alreadyDone: { path: 'done', value: true, contains: null }, expired: null, failed: null }
  assert.deepEqual(collectRules(createRuleDraft(source)), source)
})

test('template drafts keep JSON scalar types and never mutate their source', () => {
  const source = {
    success: { path: 'code', value: '0', contains: null },
    alreadyDone: { path: 'count', value: 0, contains: 'done' },
    expired: { path: 'auth', value: null, contains: null },
    failed: { path: null, value: null, contains: 'error' },
  }
  const first = createRuleDraft(source), second = createRuleDraft(source)
  assert.deepEqual(collectRules(first), { ...source, failed: { ...source.failed, path: '' } })
  first.success.valueText = '99'
  assert.equal(source.success.value, '0')
  assert.equal(collectRules(second).success.value, '0')
  assert.equal(collectRules(first).success.value, 99)
})

test('empty categories are null and invalid or missing JSON values prevent saving', () => {
  const draft = createRuleDraft({})
  assert.deepEqual(collectRules(draft), { success: null, alreadyDone: null, expired: null, failed: null })
  draft.success.path = 'code'
  assert.throws(() => collectRules(draft), /需填写 JSON/)
  draft.success.valueText = 'not-json'
  assert.throws(() => collectRules(draft), /合法 JSON/)
  draft.success.valueText = 'false'
  assert.equal(collectRules(draft).success.value, false)
})
