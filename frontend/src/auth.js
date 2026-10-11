import { reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from './api'

export const auth = reactive({ checked: false, username: null, loggingOut: false })
let generation = 0, checking
export const sessionVersion = () => generation

export function clearSession() {
  generation++
  auth.checked = true
  auth.username = null
  ElMessageBox.close()
  ElMessage.closeAll()
}

export async function ensureSession() {
  if (auth.checked) return Boolean(auth.username)
  if (!checking) {
    const current = generation
    const pending = api('/auth/session').then((state) => {
      if (current !== generation) return
      auth.username = state.authenticated ? state.username : null
      auth.checked = true
    }).finally(() => { if (checking === pending) checking = undefined })
    checking = pending
  }
  await checking
  return Boolean(auth.username)
}

export async function login(username, password) {
  const state = await api('/auth/login', 'POST', { username, password })
  generation++
  auth.username = state.username
  auth.checked = true
}

export async function logout() {
  if (auth.loggingOut) return
  auth.loggingOut = true
  try {
    await api('/auth/logout', 'POST', {})
    clearSession()
  } finally { auth.loggingOut = false }
}
