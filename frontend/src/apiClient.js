import { isSessionFailure } from './authState.js'

export function createApiClient({ fetcher = (...args) => fetch(...args), onUnauthorized = () => {}, getSessionVersion = () => 0 } = {}) {
  return async function api(path, method = 'GET', body) {
    const version = getSessionVersion()
    const response = await fetcher(`/api${path}`, {
      method,
      credentials: 'same-origin',
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: 'no-store',
    })
    if (version === getSessionVersion() && isSessionFailure(path, response.status)) onUnauthorized()
    if (version !== getSessionVersion()) {
      throw Object.assign(new Error('登录状态已变化，请重新操作'), { status: response.status, silent: true })
    }
    let data
    try { data = await response.json() }
    catch { throw new Error('服务响应无法读取，请检查后端是否运行') }
    // A logout can also happen while the body is being read.
    if (version !== getSessionVersion()) throw Object.assign(new Error('登录状态已变化，请重新操作'), { silent: true })
    if (!response.ok) {
      throw Object.assign(new Error(data.message || `服务返回 ${response.status}`), {
        status: response.status, silent: isSessionFailure(path, response.status),
      })
    }
    return data
  }
}
