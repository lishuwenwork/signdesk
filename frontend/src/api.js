import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, onUnmounted } from 'vue'

export async function api(path, method = 'GET', body) {
  const response = await fetch(`/api${path}`, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  })
  let data
  try {
    data = await response.json()
  } catch {
    throw new Error('服务响应无法读取，请检查后端是否运行')
  }
  if (!response.ok) {
    const error = new Error(data.message || `服务返回 ${response.status}`)
    error.status = response.status
    throw error
  }
  return data
}
export function report(error) {
  ElMessage.error(error.message || '操作失败')
}
export function periodic(action, delay = 5000) {
  let timer,
    stopped = false,
    busy = false
  const run = async () => {
    if (busy || stopped) return
    busy = true
    try {
      await action()
    } catch (error) {
      /* show connection errors once in the app shell */
    } finally {
      busy = false
      if (!stopped) timer = setTimeout(run, delay)
    }
  }
  onMounted(run)
  onUnmounted(() => {
    stopped = true
    clearTimeout(timer)
  })
}
export async function confirm(message) {
  try {
    await ElMessageBox.confirm(message, '确认操作', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      type: 'warning',
    })
    return true
  } catch {
    return false
  }
}
export async function execute(scope, id, force = false) {
  if (force && !(await confirm('请先核对平台实际状态。重新执行会忽略今日完成和待确认标记，可能重复操作。')))
    return
  try {
    const key = crypto.randomUUID
      ? crypto.randomUUID()
      : Array.from(crypto.getRandomValues(new Uint8Array(16)), (n) => n.toString(16).padStart(2, '0')).join(
          '',
        )
    const data = await api('/runs', 'POST', { scope, id, force, key })
    ElMessage.success(`已创建 ${data.batchIds.length} 个执行批次，关闭页面后服务器仍会继续执行`)
    return data
  } catch (error) {
    report(error)
  }
}
export const labels = {
  queued: '排队中',
  running: '执行中',
  success: '成功',
  already_done: '已完成',
  expired: '凭证过期',
  failed: '失败',
  unknown: '待确认',
  cancelled: '已取消',
  skipped: '已跳过',
}
export const tones = {
  success: 'success',
  already_done: 'success',
  failed: 'danger',
  expired: 'danger',
  unknown: 'warning',
  running: 'primary',
  queued: 'info',
  cancelled: 'info',
  skipped: 'info',
}
export function dateTime(value) {
  return value
    ? new Intl.DateTimeFormat('zh-CN', {
        timeZone: 'Asia/Shanghai',
        dateStyle: 'short',
        timeStyle: 'medium',
        hour12: false,
      }).format(new Date(value))
    : '—'
}
