import { test, expect } from '@playwright/test'
const created = new Set()
const send = async (request, method, path, data) => {
  const response = await request[method](path, data === undefined ? {} : { data })
  expect(response.ok(), `${method} ${path}`).toBe(true)
  return response.json()
}
async function seed(request, name, requests) {
  const platform=await send(request,'post','/api/platforms',{name,note:'只使用本地 fixture 的隔离验收配置',enabled:true})
  created.add(platform.id)
  const account=await send(request,'post',`/api/platforms/${platform.id}/accounts`,{alias:'工作账号',enabled:true})
  const items=[]
  for(const input of requests) items.push({...await send(request,'post',`/api/accounts/${account.id}/requests`,{name:input.name,enabled:true,curl:`curl 'http://127.0.0.1:18081${input.path || '/check'}'`,...(input.rules?{rules:input.rules}:{})}),name:input.name})
  return {platform,account,items}
}
async function run(request,id) {
  const result=await send(request,'post','/api/runs',{scope:'request',id,force:false,key:`workspace-${id}`})
  await expect.poll(async()=> (await send(request,'get',`/api/batches/${result.batchIds[0]}`)).status).toBe('completed')
  const batch=await send(request,'get',`/api/batches/${result.batchIds[0]}`)
  return {id:batch.items[0].id,batchId:batch.id}
}
const closeDrawer=async page=> {await page.locator('.el-drawer:visible .el-drawer__close-btn').last().click();await expect(page.getByTestId('response-body')).toHaveCount(0)}
async function overflow(page,width) {expect(await page.evaluate(()=>Math.max(document.documentElement.scrollWidth,document.body.scrollWidth))).toBeLessThanOrEqual(width+1)}
test.afterEach(async({playwright})=> {
  const request=await playwright.request.newContext({baseURL:'http://127.0.0.1:18080'})
  try {for(const id of created) {const result=await request.delete(`/api/platforms/${id}`,{data:{}});expect(result.ok()).toBe(true);created.delete(id)}}finally{await request.dispose()}
})

test('overview and platform rows use real independent markers and cross-page request locations',async({page,request})=>{
  const data=await seed(request,'工作区状态验收',[{name:'完成请求'},{name:'待确认请求',path:'/responses/error'},{name:'凭证暂停请求',rules:{success:null,expired:{path:'code',value:0}}}])
  const [success,unknown,expired]=await Promise.all(data.items.map(item=>run(request,item.id)))
  const tree=await send(request,'get','/api/platforms')
  const requests=tree.find(p=>p.id===data.platform.id).accounts[0].requests
  expect(requests.map(r=>r.todayState)).toEqual(['completed','pending','none'])
  expect(requests[2].authPaused).toBe(1)
  for(const item of requests) {expect(Object.keys(item.lastRun).sort()).toEqual(['createdAt','durationMs','finishedAt','httpStatus','id','status']);expect(item.lastRun).not.toHaveProperty('response')}
  await page.goto('/#/')
  const attention=page.locator('.attention-item').filter({hasText:'工作区状态验收'})
  await expect(attention).toHaveCount(2)
  await attention.filter({hasText:'凭证暂停请求'}).getByRole('button',{name:'更新 cURL'}).click()
  await expect(page).toHaveURL(new RegExp(`platformId=${data.platform.id}.*requestId=${data.items[2].id}.*action=update`))
  await expect(page.getByRole('dialog',{name:'更新 cURL',exact:true})).toBeVisible()
  await expect(page.getByRole('textbox',{name:'完整 cURL',exact:true})).toHaveValue('')
  await page.getByRole('dialog').getByRole('button',{name:'取消',exact:true}).click()
  await page.getByRole('button',{name:'全部展开',exact:true}).click()
  const successRow=page.locator(`[data-request-id="${data.items[0].id}"]`)
  await expect(successRow).toContainText('今日已完成')
  await expect(page.locator(`[data-request-id="${data.items[1].id}"]`)).toContainText('今日待确认')
  await successRow.locator('.last-run').click()
  await expect(page.getByRole('dialog',{name:'执行记录详情'})).toContainText('成功')
  await page.locator('.el-drawer:visible').getByRole('button',{name:'响应体',exact:true}).click()
  await expect(page.getByTestId('response-body')).toHaveText('{"code":0}')
  await closeDrawer(page)
  await page.locator('.filter-tabs').getByRole('button',{name:/今日完成/}).click()
  await expect(page.locator('.request-row')).toHaveCount(1)
  await page.locator('.filter-tabs').getByRole('button',{name:/需处理/}).click()
  await expect(page.locator('.request-row')).toHaveCount(2)
  expect([success.id,unknown.id,expired.id].every(Boolean)).toBe(true)
})

test('request detail drawer loads secrets only on demand and clears on close or navigation',async({page,request})=>{
  const data=await seed(request,'请求抽屉验收',[{name:'完整请求'}])
  const item=data.items[0]
  let reads=0
  page.on('request',r=>{if(new URL(r.url()).pathname===`/api/requests/${item.id}/revision`) reads++})
  await page.goto(`/#/platforms?platformId=${data.platform.id}&requestId=${item.id}`)
  await expect(page.locator(`[data-request-id="${item.id}"]`)).toBeVisible()
  expect(reads).toBe(0)
  await page.getByRole('button',{name:'完整请求',exact:true}).click()
  await expect(page.locator('.el-drawer:visible')).toContainText("curl 'http://127.0.0.1:18081/check'")
  expect(reads).toBe(1)
  await page.locator('.el-drawer:visible').getByRole('button',{name:'结果规则',exact:true}).click()
  await expect(page.locator('.el-drawer:visible')).toContainText('JSON 判断保留值类型')
  await page.keyboard.press('Escape')
  await expect(page.locator('.el-drawer:visible')).toHaveCount(0)
  await expect(page.locator('.preview-code').filter({hasText:'18081/check'})).toHaveCount(0)
  await page.getByRole('button',{name:'完整请求',exact:true}).click()
  await expect(page.locator('.el-drawer:visible')).toContainText('18081/check')
  await closeDrawer(page)
  await page.getByRole('link',{name:'执行记录',exact:true}).click()
  await expect(page.locator('.preview-code')).toHaveCount(0)
})

test('all five pages keep the approved desktop and mobile visual system with live data',async({page,request},info)=>{
  const errors=[];page.on('pageerror',error=>errors.push(error.message))
  const data=await seed(request,'纸面工作区',[{name:'每日签到'},{name:'查询积分',path:'/responses/json'}])
  await run(request,data.items[0].id)
  const schedule=await send(request,'get',`/api/platforms/${data.platform.id}/schedule`)
  await send(request,'put',`/api/platforms/${data.platform.id}/schedule`,{...schedule,enabled:true,times:['09:30','18:00']})
  for(const width of [1440,390]) {
    await page.setViewportSize({width,height:width===390?844:1000})
    for(const [path,title] of [['/','今日概览'],[`/platforms?platformId=${data.platform.id}`,'平台与账号'],['/schedules','定时计划'],['/runs','执行记录'],['/settings','设置与备份']]) {
      await page.goto(`/#${path}`)
      await expect(page.getByRole('heading',{name:title,level:1,exact:true})).toBeVisible()
      await expect(page.getByRole('navigation',{name:'主导航'}).getByRole('link')).toHaveCount(5)
      await overflow(page,width)
      if(width===390) {await expect(page.locator('.brand')).toBeHidden();expect((await page.locator('.sidebar').boundingBox()).y).toBeGreaterThan(700)}
      else {await expect(page.locator('.brand')).toBeVisible();expect((await page.locator('.sidebar').boundingBox()).width).toBe(84)}
      await expect(page.locator('.app-layout')).not.toContainText(/PROTOTYPE|原型说明|模拟队列|恢复示例|接口适配/)
      await page.screenshot({path:info.outputPath(`${title}-${width}.png`),fullPage:true,animations:'disabled'})
    }
    for(const tab of ['proxy','backup','about']) {
      await page.goto(`/#/settings?tab=${tab}`)
      await expect(page.locator(`[id="settings-tab-${tab}"]`)).toHaveAttribute('aria-selected','true')
      await overflow(page,width)
      await page.screenshot({path:info.outputPath(`settings-${tab}-${width}.png`),fullPage:true,animations:'disabled'})
    }
  }
  expect(errors).toEqual([])
})

test('persistent queue exposes real batch details and cancellation does not send remaining requests',async({page,request},info)=>{
  const data=await seed(request,'持久队列验收',[{name:'队列第一项'},{name:'队列第二项'}])
  const schedule=await send(request,'get',`/api/platforms/${data.platform.id}/schedule`)
  await send(request,'put',`/api/platforms/${data.platform.id}/schedule`,{...schedule,enabled:false,intervalSeconds:10})
  const before=(await send(request,'get','http://127.0.0.1:18081/received')).length
  const accepted=await send(request,'post','/api/runs',{scope:'platform',id:data.platform.id,force:false,key:`queue-${data.platform.id}`})
  await expect.poll(async()=> (await send(request,'get','http://127.0.0.1:18081/received')).length).toBe(before+1)
  await page.goto('/#/')
  await page.locator('.queue-card-heading').getByRole('button',{name:'持久队列验收',exact:true}).click()
  const drawer=page.getByRole('dialog',{name:'执行批次详情',exact:true})
  await expect(drawer).toContainText('队列第一项')
  await expect(drawer).toContainText('队列第二项')
  await expect(drawer.locator('.batch-item')).toHaveCount(2)
  await page.screenshot({path:info.outputPath('batch-detail-desktop.png'),fullPage:true,animations:'disabled'})
  await drawer.getByRole('button',{name:'取消剩余',exact:true}).click()
  await page.locator('.el-message-box').getByRole('button',{name:'确认',exact:true}).click()
  await expect.poll(async()=> (await send(request,'get',`/api/batches/${accepted.batchIds[0]}`)).items[1].status).toBe('cancelled')
  await expect.poll(async()=> ['queued','running'].includes((await send(request,'get',`/api/batches/${accepted.batchIds[0]}`)).status)).toBe(false)
  expect((await send(request,'get','http://127.0.0.1:18081/received')).length).toBe(before+1)
  await closeDrawer(page)
})

test('creation refreshes never steal the platform or filter selected after saving',async({page,request})=>{
  const first=await seed(request,'保存后定位甲',[{name:'甲请求'}]),second=await seed(request,'保存后定位乙',[{name:'乙请求'}])
  await page.goto(`/#/platforms?platformId=${first.platform.id}`)
  const group=page.getByRole('region',{name:'账号 工作账号',exact:true})
  await group.getByRole('button',{name:'添加请求',exact:true}).click()
  await page.getByRole('textbox',{name:'请求名称',exact:true}).fill('延迟刷新新请求')
  await page.getByRole('textbox',{name:'完整 cURL',exact:true}).fill("curl 'http://127.0.0.1:18081/check'")
  await page.getByRole('button',{name:'解析预览',exact:true}).click()
  await page.getByRole('button',{name:'设置结果规则',exact:true}).click()
  let release,started,hold=true
  const gate=new Promise(resolve=>{release=resolve}),captured=new Promise(resolve=>{started=resolve})
  await page.route('**/api/platforms',async route=>{
    if(!hold) return route.continue()
    hold=false
    const response=await route.fetch()
    started()
    await gate
    await route.fulfill({response})
  })
  try {
    await page.getByRole('button',{name:'保存请求',exact:true}).click()
    await captured
    await expect(page.getByRole('dialog')).toBeHidden()
    await page.locator('.platform-choice').filter({hasText:'保存后定位乙'}).click()
    await page.getByRole('textbox',{name:'搜索当前平台请求'}).fill('保留乙的筛选')
    release()
    await expect(page.getByRole('heading',{name:'保存后定位乙',level:2,exact:true})).toBeVisible()
    await expect(page.getByRole('textbox',{name:'搜索当前平台请求'})).toHaveValue('保留乙的筛选')
    await page.unrouteAll({behavior:'wait'})
    await expect.poll(async()=> (await send(request,'get','/api/platforms')).find(p=>p.id===first.platform.id).accounts[0].requests.length).toBe(2)
  }finally{release();await page.unrouteAll({behavior:'ignoreErrors'})}
})

test('switching request IDs within the same platform clears a late cURL preview',async({page,request})=>{
  const data=await seed(request,'同平台请求切换',[{name:'请求甲'},{name:'请求乙'}])
  await page.goto(`/#/platforms?platformId=${data.platform.id}&requestId=${data.items[0].id}&action=update`)
  await expect(page.getByRole('dialog',{name:'更新 cURL',exact:true})).toContainText('请求甲')
  await page.getByRole('textbox',{name:'完整 cURL',exact:true}).fill("curl 'http://127.0.0.1:18081/check' -H 'Cookie: preview-credential-A'")
  let release,started
  const gate=new Promise(resolve=>{release=resolve}),captured=new Promise(resolve=>{started=resolve})
  await page.route('**/api/requests/preview',async route=>{const response=await route.fetch();started();await gate;await route.fulfill({response})})
  try {
    await page.getByRole('button',{name:'解析预览',exact:true}).click()
    await captured
    await page.evaluate(hash=>{location.hash=hash},`#/platforms?platformId=${data.platform.id}&requestId=${data.items[1].id}&action=update`)
    await expect(page.getByRole('dialog',{name:'更新 cURL',exact:true})).toContainText('请求乙')
    await expect(page.getByRole('textbox',{name:'完整 cURL',exact:true})).toHaveValue('')
    release()
    await page.unrouteAll({behavior:'wait'})
    await expect(page.getByRole('textbox',{name:'完整 cURL',exact:true})).toHaveValue('')
    await expect(page.getByRole('dialog')).not.toContainText('preview-credential-A')
    await page.getByRole('dialog').getByRole('button',{name:'取消',exact:true}).click()
  }finally{release();await page.unrouteAll({behavior:'ignoreErrors'})}
})

test('account save freezes fields and cannot close a new editor through a late write',async({page,request})=>{
  const data=await seed(request,'账号保存草稿保护',[{name:'请求'}])
  await page.goto(`/#/platforms?platformId=${data.platform.id}`)
  await page.getByRole('button',{name:'添加账号',exact:true}).click()
  await page.getByRole('textbox',{name:'账号别名',exact:true}).fill('待保存账号')
  let release,started
  const gate=new Promise(resolve=>{release=resolve}),captured=new Promise(resolve=>{started=resolve})
  await page.route(`**/api/platforms/${data.platform.id}/accounts`,async route=>{started();await gate;const response=await route.fetch();await route.fulfill({response})})
  try {
    await page.getByRole('button',{name:'保存账号',exact:true}).click()
    await captured
    await expect(page.getByRole('textbox',{name:'账号别名',exact:true})).toBeDisabled()
    await expect(page.getByRole('dialog').getByRole('button',{name:'取消',exact:true})).toBeDisabled()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog',{name:'添加账号',exact:true})).toBeVisible()
    release()
    await expect(page.getByRole('dialog')).toBeHidden()
    await page.getByRole('button',{name:'添加账号',exact:true}).click()
    await page.getByRole('textbox',{name:'账号别名',exact:true}).fill('另一份新草稿')
    await expect(page.getByRole('textbox',{name:'账号别名',exact:true})).toHaveValue('另一份新草稿')
    await page.getByRole('dialog').getByRole('button',{name:'取消',exact:true}).click()
  }finally{release();await page.unrouteAll({behavior:'ignoreErrors'})}
})

test('record pagination and filters reflect server totals while searching only current page',async({page,request})=>{
  const data=await seed(request,'分页真实记录',[{name:'分页请求'}])
  for(let i=0;i<12;i++) {
    const accepted=await send(request,'post','/api/runs',{scope:'request',id:data.items[0].id,force:true,key:`page-${data.items[0].id}-${i}`})
    await expect.poll(async()=> (await send(request,'get',`/api/batches/${accepted.batchIds[0]}`)).status).toBe('completed')
  }
  await page.goto(`/#/runs?platformId=${data.platform.id}&status=success`)
  await expect(page.locator('.pagination-copy')).toContainText('共 12 条记录')
  await page.locator('.el-pagination__sizes .el-select__wrapper').click()
  await page.getByRole('option',{name:/^10/}).click()
  await expect(page.locator('.log-table tbody tr')).toHaveCount(10)
  await page.locator('.btn-next').click()
  await expect(page.locator('.log-table tbody tr')).toHaveCount(2)
  await page.getByRole('textbox',{name:'搜索当前页记录'}).fill('不存在的名称')
  await expect(page.locator('.pagination-copy')).toContainText('当前页匹配 0 条')
  await expect(page.locator('.log-table tbody tr')).toHaveCount(0)
  await page.getByRole('textbox',{name:'搜索当前页记录'}).fill('分页请求')
  await expect(page.locator('.log-table tbody tr')).toHaveCount(2)
})
