export function cloneSettings(settings) {
  return {
    paused: Boolean(settings.paused), concurrency: settings.concurrency,
    timeoutSeconds: settings.timeoutSeconds, retentionDays: settings.retentionDays,
    version: settings.version,
    proxy: { mode: settings.proxy?.mode ?? 'system', host: settings.proxy?.host ?? '', port: settings.proxy?.port ?? 0 },
  }
}

export function settingsDirty(draft, saved) {
  return Boolean(draft && saved && JSON.stringify(cloneSettings(draft)) !== JSON.stringify(cloneSettings(saved)))
}

export function validateSettings(settings) {
  for (const [key, min, max, name] of [
    ['concurrency', 1, 8, '同时执行的平台数'],
    ['timeoutSeconds', 1, 120, '请求超时'],
    ['retentionDays', 1, 365, '日志保留天数'],
  ]) {
    if (!Number.isInteger(settings[key]) || settings[key] < min || settings[key] > max)
      return `${name}需为 ${min}～${max} 的整数`
  }
  const { mode, host, port } = settings.proxy ?? {}
  if (!['system', 'direct', 'http', 'socks'].includes(mode)) return '请选择有效的代理模式'
  if (['http', 'socks'].includes(mode)) {
    if (!Number.isInteger(port) || port < 1 || port > 65535) return '代理端口需为 1～65535 的整数'
    const address = typeof host === 'string' ? host.trim() : ''
    if (!validProxyHost(address)) return '代理地址仅填写主机名或 IP，不包含协议、端口、路径或账号密码'
  }
  return ''
}

function validProxyHost(host) {
  if (!host || host.length > 253 || /[^\x21-\x7e]|[\/@?#\\]/.test(host)) return false
  if (host.includes(':') || host.startsWith('[')) {
    try {
      const bracketed = host.startsWith('[') ? host : `[${host}]`
      const parsed = new URL(`http://${bracketed}:80`)
      return parsed.hostname.startsWith('[') && parsed.pathname === '/'
    } catch { return false }
  }
  return /^[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?\.?$/.test(host)
    && host.replace(/\.$/, '').split('.').every((label) => /^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?$/.test(label))
}
