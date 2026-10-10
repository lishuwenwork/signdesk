export function needsAttention(request) {
  return !request.safeHost || !!request.authPaused
}

export function isRequestDisabled(request, accountEnabled, platformEnabled) {
  return !request.enabled || !accountEnabled || !platformEnabled
}

export function canExecuteRequest(request, accountEnabled, platformEnabled) {
  return !isRequestDisabled(request, accountEnabled, platformEnabled) && !request.authPaused
}

export function requestCounts(requests) {
  return { total: requests.length, attention: requests.filter(needsAttention).length }
}

export function filterAccounts(platform, keyword = '', status = 'all') {
  if (!platform) return []
  const query = keyword.trim().toLowerCase()
  return platform.accounts.flatMap((account) => {
    const matchesAccount = account.alias.toLowerCase().includes(query)
    const requests = account.requests.filter((request) => {
      const matchesText = matchesAccount || request.name.toLowerCase().includes(query)
        || request.safeHost.toLowerCase().includes(query)
      const matchesStatus = status === 'attention' ? needsAttention(request)
        : status === 'disabled' ? isRequestDisabled(request, account.enabled, platform.enabled) : true
      return matchesText && matchesStatus
    })
    const emptyAccountMatches = !account.requests.length && matchesAccount && status === 'all'
    return requests.length || emptyAccountMatches ? [{ account, requests }] : []
  })
}

export function reconcileAccountState(previous, accounts) {
  const ids = new Set(accounts.map((account) => account.id))
  return {
    initialized: !!previous?.initialized || accounts.length > 0,
    expandedIds: previous?.initialized
      ? previous.expandedIds.filter((id) => ids.has(id)) : accounts.slice(0, 1).map((account) => account.id),
    filteredCollapsedIds: (previous?.filteredCollapsedIds || []).filter((id) => ids.has(id)),
  }
}

export function isAccountExpanded(state, accountId, filtering) {
  return filtering ? !state.filteredCollapsedIds.includes(accountId) : state.expandedIds.includes(accountId)
}

export function setAccountExpansion(state, accountIds, expanded, filtering = false) {
  const key = filtering ? 'filteredCollapsedIds' : 'expandedIds'
  const ids = new Set(state[key])
  for (const id of accountIds) {
    if (filtering ? !expanded : expanded) ids.add(id)
    else ids.delete(id)
  }
  return { ...state, [key]: [...ids] }
}
