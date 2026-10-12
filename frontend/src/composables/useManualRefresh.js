import { onScopeDispose, ref } from 'vue'

/**
 * @typedef {Object} ManualRefreshState
 * @property {import('vue').Ref<boolean>} refreshing
 * @property {() => Promise<void>} refresh
 */

/**
 * 仅延长手动刷新的按钮反馈，不延迟 action 内的数据更新。
 * @param {() => Promise<unknown>} action
 * @returns {ManualRefreshState}
 */
export function useManualRefresh(action) {
  /** @type {import('vue').Ref<boolean>} */
  const refreshing = ref(false)
  let disposed = false
  /** @type {ReturnType<typeof setTimeout> | undefined} */
  let timer
  /** @type {(() => void) | undefined} */
  let finishFeedback

  /** @returns {Promise<void>} */
  async function refresh() {
    if (disposed || refreshing.value) return
    refreshing.value = true
    /** @type {Promise<void>} */
    const feedback = new Promise(resolve => {
      finishFeedback = resolve
      timer = setTimeout(resolve, 400)
    })
    try {
      await action()
    } finally {
      await feedback
      timer = undefined
      finishFeedback = undefined
      if (!disposed) refreshing.value = false
    }
  }

  onScopeDispose(() => {
    disposed = true
    clearTimeout(timer)
    finishFeedback?.()
    refreshing.value = false
  })
  return { refreshing, refresh }
}
