const businessPaths = new Set(['/', '/platforms', '/schedules', '/runs', '/settings'])

export function safeReturnPath(value) {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || /[\\\u0000- ]/.test(value)) return '/'
  try {
    const url = new URL(value, 'http://signdesk.local')
    if (url.origin !== 'http://signdesk.local' || url.hash || !businessPaths.has(url.pathname)) return '/'
    if (value.split('?')[0] !== url.pathname) return '/'
    return url.pathname + url.search
  } catch { return '/' }
}

export function isSessionFailure(path, status) {
  return status === 401 && !['/auth/login', '/auth/session', '/auth/logout'].includes(path)
}
