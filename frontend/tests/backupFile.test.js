import { test } from 'node:test'
import assert from 'node:assert/strict'
import { BACKUP_FORMAT, MAX_BACKUP_FILE_BYTES, readBackupFile } from '../src/backupFile.mjs'

function backup(includesRequests) {
  return {
    format: BACKUP_FORMAT,
    payload: {
      includesRequests,
      settings: { paused: true, proxy: { mode: 'direct', host: '', port: 0 } },
      platforms: [{ id: '100', name: '本地测试', note: '用户手填的敏感备注' }],
      accounts: [{ id: '101', platformId: '100', alias: '测试账号' }],
      requests: [{ id: '102', accountId: '101', name: '测试请求',
        ...(includesRequests ? { rawCurl: "curl 'http://127.0.0.1:18081/check' -H 'Cookie: unit-fixture=only'" } : {}) }],
      templates: [], schedules: [],
      completed: includesRequests ? [{ requestId: '102', businessDate: '2026-10-09' }] : [],
      pending: [],
    },
  }
}

function file(text) {
  return new Blob([text], { type: 'application/json' })
}

test('both plaintext modes retain the selected typed payload and need no password', async () => {
  for (const includesRequests of [false, true]) {
    const source = backup(includesRequests)
    const parsed = await readBackupFile(file(JSON.stringify(source)))
    assert.deepEqual(parsed, source)
    assert.equal('password' in parsed, false)
    assert.equal('rawCurl' in parsed.payload.requests[0], includesRequests)
    assert.equal(parsed.payload.completed.length, includesRequests ? 1 : 0)
    assert.equal(parsed.payload.platforms[0].note, '用户手填的敏感备注')
  }
})

test('legacy encrypted and old config formats are rejected, not decoded or converted', async () => {
  for (const format of ['signdesk-backup-v1', 'signdesk-config', '', 'signdesk-plain-v2']) {
    await assert.rejects(readBackupFile(file(JSON.stringify({ format, ciphertext: 'fixture-only' }))), /不支持此备份格式/)
  }
  for (const text of ['null', '[]', '{}', '"signdesk-plain-v1"']) {
    await assert.rejects(readBackupFile(file(text)), /不支持此备份格式/)
  }
})

test('invalid JSON has a safe fixed message without echoing backup content', async () => {
  await assert.rejects(readBackupFile(file('{"sensitive-fixture":')), { message: '请选择合法 JSON 备份文件' })
})

test('actual size is bounded before reading and exactly 12 MiB is accepted', async () => {
  assert.equal(MAX_BACKUP_FILE_BYTES, 12_582_912)
  let reads = 0
  await assert.rejects(readBackupFile({ size: MAX_BACKUP_FILE_BYTES + 1, text: async () => { reads++; return '{}' } }), /文件超过 12 MiB/)
  assert.equal(reads, 0)
  const source = backup(false)
  const text = JSON.stringify(source)
  const padded = text + ' '.repeat(MAX_BACKUP_FILE_BYTES - new TextEncoder().encode(text).byteLength)
  assert.equal(file(padded).size, MAX_BACKUP_FILE_BYTES)
  assert.deepEqual(await readBackupFile(file(padded)), source)
})

test('UTF-8 bytes are not interchangeable with JavaScript character length', async () => {
  const text = JSON.stringify({ ...backup(false), note: '中'.repeat(Math.ceil(MAX_BACKUP_FILE_BYTES / 3)) })
  assert.ok(text.length < MAX_BACKUP_FILE_BYTES)
  assert.ok(new TextEncoder().encode(text).byteLength > MAX_BACKUP_FILE_BYTES)
  await assert.rejects(readBackupFile(file(text)), /文件超过 12 MiB/)
  // Even an inaccurate size supplied by an adapter cannot bypass the UTF-8 check.
  await assert.rejects(readBackupFile({ size: text.length, text: async () => text }), /UTF-8 字节/)
})
