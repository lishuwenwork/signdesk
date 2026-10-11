import test from 'node:test'
import assert from 'node:assert/strict'
import { safeReturnPath, isSessionFailure } from '../src/authState.js'
import { createApiClient } from '../src/apiClient.js'

test('return targets preserve only known internal business routes and their queries', () => {
  for (const path of ['/', '/settings?tab=backup', '/platforms?platformId=123&action=update', '/runs', '/schedules']) assert.equal(safeReturnPath(path), path)
  for (const value of [null, [], undefined, 'https://example.invalid', '//example.invalid', '/\\example.invalid', '/login', '/unknown', '/settings#fragment', '/foo/../settings', '/%73ettings', '/settings\n', ' /settings']) assert.equal(safeReturnPath(value), '/')
})

test('only a management API 401 signals a lost session', () => {
  assert.equal(isSessionFailure('/settings', 401), true)
  for (const path of ['/auth/login', '/auth/session', '/auth/logout']) assert.equal(isSessionFailure(path, 401), false)
  for (const status of [400, 403, 429, 500]) assert.equal(isSessionFailure('/settings', status), false)
})

test('API requests send same-origin cookies and JSON without enabling caching', async () => {
  let observed
  const api = createApiClient({ fetcher: async (...args) => { observed = args; return new Response('{"saved":true}') } })
  assert.deepEqual(await api('/settings', 'PUT', { paused: true }), { saved: true })
  assert.deepEqual(observed, ['/api/settings', { method: 'PUT', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '{"paused":true}', cache: 'no-store' }])
})

test('an unauthorized management response clears the session once and suppresses stale results', async () => {
  let generation = 0, calls = 0
  const api = createApiClient({ fetcher: async () => new Response('{"message":"登录过期"}', { status: 401 }), getSessionVersion: () => generation, onUnauthorized: () => { calls++; generation++ } })
  await assert.rejects(api('/settings'), (error) => error.silent && error.status === 401)
  assert.equal(calls, 1)
})

test('incorrect login reports the server error without clearing the session or redirecting', async () => {
  let calls = 0
  const api = createApiClient({ fetcher: async () => new Response('{"message":"用户名或密码错误"}', { status: 401 }), onUnauthorized: () => { calls++ } })
  await assert.rejects(api('/auth/login', 'POST', { username: 'fixture', password: 'incorrect' }), (e) => e.status === 401 && !e.silent && e.message === '用户名或密码错误')
  assert.equal(calls, 0)
})

test('network and server failures are not mistaken for an expired session', async () => {
  let calls = 0
  const options = { onUnauthorized: () => { calls++ } }
  await assert.rejects(createApiClient({ ...options, fetcher: async () => { throw new Error('offline') } })('/settings'), /offline/)
  await assert.rejects(createApiClient({ ...options, fetcher: async () => new Response('{"message":"fixture error"}', { status: 500 }) })('/settings'), (e) => e.status === 500)
  assert.equal(calls, 0)
})

test('responses from before logout cannot expose data or invalidate a newer login', async () => {
  let generation = 0, calls = 0, complete
  const api = createApiClient({ fetcher: () => new Promise((resolve) => { complete = resolve }), getSessionVersion: () => generation, onUnauthorized: () => { calls++ } })
  const pending = api('/requests/1/revision')
  generation++
  complete(new Response('{"rawCurl":"fixture-private"}', { status: 401 }))
  await assert.rejects(pending, (e) => e.silent === true)
  assert.equal(calls, 0)
})

test('logout during response body parsing also prevents stale sensitive results', async () => {
  let generation = 0, finish
  const api = createApiClient({ fetcher: async () => ({ status: 200, ok: true, json: () => new Promise((resolve) => { finish = resolve }) }), getSessionVersion: () => generation })
  const pending = api('/requests/1/revision')
  await Promise.resolve()
  generation++
  finish({ rawCurl: 'fixture-private' })
  await assert.rejects(pending, (e) => e.silent === true)
})
