import { test } from 'node:test'
import assert from 'node:assert/strict'
import { requestPresentation, attentionRequests, platformLocation } from '../src/requestPresentation.js'
import { canExecuteRequest, filterAccounts, needsAttention } from '../src/platformViewState.js'
const base = { id:'11', name:'签到', enabled:1, authPaused:0, safeHost:'example.test', todayState:'none', lastRun:null }

test('today completion comes from independent markers, never inferred from recent success', () => {
  assert.equal(requestPresentation({...base,lastRun:{status:'success'}}).label,'今日未完成')
  assert.equal(requestPresentation({...base,todayState:'completed',lastRun:null}).label,'今日已完成')
  assert.equal(requestPresentation({...base,todayState:'completed',lastRun:{status:'failed'}}).label,'今日已完成')
})
test('credential pause, own disablement and day markers stay independent', () => {
  assert.equal(requestPresentation({...base,todayState:'completed',authPaused:1}).label,'凭证已暂停')
  assert.match(requestPresentation({...base,todayState:'completed',enabled:0}).caption,/已禁用/)
  assert.equal(requestPresentation({...base,todayState:'pending',enabled:0}).label,'今日待确认')
  assert.match(requestPresentation(base,false).caption,/账号未启用/)
  assert.match(requestPresentation(base,true,false).caption,/平台未启用/)
})
test('unknown day marker requests need attention even after log cleanup', () => {
  const request={...base,todayState:'pending'}
  assert.equal(needsAttention(request),true)
  const platforms=[{id:'1',enabled:1,accounts:[{id:'2',alias:'账号',enabled:1,requests:[request]}]}]
  assert.equal(attentionRequests(platforms).length,1)
  assert.equal(filterAccounts(platforms[0],'','attention')[0].requests[0].id,'11')
  assert.equal(filterAccounts(platforms[0],'','completed').length,0)
})
test('active last execution prevents duplicate send but terminal metadata does not forge day state', () => {
  for(const status of ['queued','running']) assert.equal(canExecuteRequest({...base,lastRun:{status}},true,true),false)
  assert.equal(canExecuteRequest({...base,lastRun:{status:'unknown'}},true,true),true)
  assert.equal(canExecuteRequest({...base,authPaused:1},true,true),false)
  assert.equal(requestPresentation({...base,lastRun:{status:'unknown'}}).label,'今日未完成')
})
test('unconfigured placeholders cannot execute and do not expose a synthetic revision', () => {
  assert.equal(canExecuteRequest({...base,safeHost:''},true,true),false)
  assert.equal(requestPresentation({...base,safeHost:'',authPaused:1}).label,'未配置 cURL')
})
test('cross-page locations preserve string IDs and explicit actions', () => {
  assert.deepEqual(platformLocation('9223372036854775806','9223372036854775807','update'),{path:'/platforms',query:{platformId:'9223372036854775806',requestId:'9223372036854775807',action:'update'}})
  assert.deepEqual(platformLocation('1'),{path:'/platforms',query:{platformId:'1'}})
})
