// 日状态只使用独立日标记；最近执行仅作为历史摘要，不反推今日完成。
export function requestPresentation(request, accountEnabled = true, platformEnabled = true) {
  const disabled = !request.enabled || !accountEnabled || !platformEnabled
  if (!request.safeHost) return { label: '未配置 cURL', icon: 'key', tone: 'warning', caption: disabled ? '已禁用 · 更新完整请求后启用' : '请更新完整请求' }
  if (request.authPaused) return { label: '凭证已暂停', icon: 'key', tone: 'warning', caption: disabled ? '已禁用 · 需更新 cURL' : '更新 cURL 后解除暂停' }
  if (['queued', 'running'].includes(request.lastRun?.status)) return { label: request.lastRun.status === 'running' ? '正在执行' : '排队中', icon: 'clock', tone: 'active', caption: disabled ? '已禁用 · 活动任务由服务端处理' : '同一请求不会重复入队' }
  if (request.todayState === 'pending') return { label: '今日待确认', icon: 'alert', tone: 'warning', caption: disabled ? '已禁用 · 仍需核对平台结果' : '先核对结果，不自动重发' }
  if (request.todayState === 'completed') return { label: '今日已完成', icon: 'check', tone: 'good', caption: disabled ? '已禁用 · 日标记保留' : '日标记不随记录清理' }
  if (disabled) return { label: '已禁用', icon: 'pause', tone: 'muted', caption: !platformEnabled ? '平台未启用' : !accountEnabled ? '账号未启用' : '请求未启用' }
  return { label: '今日未完成', icon: 'clock', tone: 'muted', caption: '无今日完成或待确认标记' }
}
export function attentionRequests(platforms) {
  return platforms.flatMap(platform => platform.accounts.flatMap(account => account.requests
    .filter(request => request.authPaused || request.todayState === 'pending')
    .map(request => ({ platform, account, request }))))
}
export function platformLocation(platformId, requestId, action) {
  const query = { platformId }
  if (requestId) query.requestId = requestId
  if (action) query.action = action
  return { path: '/platforms', query }
}
