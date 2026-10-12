import { test } from 'node:test'
import assert from 'node:assert/strict'
import { filterTemplateTargets, applicationTargets } from '../src/templateApplication.js'

const rows = [
  { id: '1', name: '签到', accountAlias: '账号 A', version: 2, enabled: true, templateId: null },
  { id: '2', name: '领取奖励', accountAlias: '账号 B', version: 7, enabled: false, templateId: '10' },
]

test('template target filters include disabled requests and do not change the selection source', () => {
  assert.equal(filterTemplateTargets(rows, '').length, 2)
  assert.deepEqual(filterTemplateTargets(rows, '账号 a').map(row => row.id), ['1'])
  assert.deepEqual(filterTemplateTargets(rows, '领取').map(row => row.id), ['2'])
  assert.equal(filterTemplateTargets(rows, '不存在').length, 0)
  assert.deepEqual(applicationTargets(rows, ['2', '1']), [{ id: '1', version: 2 }, { id: '2', version: 7 }])
  assert.equal(rows[1].enabled, false)
})

test('application targets carry immutable observed versions and reject empty duplicate or stale selections', () => {
  const targets = applicationTargets(rows, ['2'])
  assert.deepEqual(targets, [{ id: '2', version: 7 }])
  targets[0].version = 100
  assert.equal(rows[1].version, 7)
  for (const ids of [[], ['1', '1'], ['missing'], Array.from({ length: 10001 }, (_, index) => String(index))])
    assert.throws(() => applicationTargets(rows, ids))
})
