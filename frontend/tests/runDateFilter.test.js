import { test } from 'node:test'
import assert from 'node:assert/strict'
import { todayRunDateRange, runDateRangeFromQuery } from '../src/runDateFilter.js'

test('today uses Beijing midnight rather than the browser or UTC date', () => {
  assert.deepEqual(todayRunDateRange(new Date('2026-10-13T15:59:59.999Z')), ['2026-10-13', '2026-10-13'])
  assert.deepEqual(todayRunDateRange(new Date('2026-10-13T16:00:00Z')), ['2026-10-14', '2026-10-14'])
  assert.deepEqual(todayRunDateRange(new Date('2026-12-31T16:00:00Z')), ['2027-01-01', '2027-01-01'])
})

test('an entry without date parameters defaults to today and preserves other filters', () => {
  const now = new Date('2026-10-14T01:00:00Z')
  assert.deepEqual(runDateRangeFromQuery({}, now), ['2026-10-14', '2026-10-14'])
  assert.deepEqual(runDateRangeFromQuery({ platformId: '1', status: 'failed' }, now), ['2026-10-14', '2026-10-14'])
})

test('custom and explicitly cleared date ranges survive refreshing the page', () => {
  assert.deepEqual(runDateRangeFromQuery({ startDate: '2026-10-01', endDate: '2026-10-14' }), ['2026-10-01', '2026-10-14'])
  assert.deepEqual(runDateRangeFromQuery({ startDate: '', endDate: '' }), [])
  assert.deepEqual(runDateRangeFromQuery({ startDate: null, endDate: null }), [])
})

test('a deep link with only one date selects that whole day', () => {
  assert.deepEqual(runDateRangeFromQuery({ startDate: '2026-10-01' }), ['2026-10-01', '2026-10-01'])
  assert.deepEqual(runDateRangeFromQuery({ endDate: '2026-10-14' }), ['2026-10-14', '2026-10-14'])
})
