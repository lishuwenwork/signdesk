/**
 * @typedef {{ path?: string|null, value: string|number|boolean|null, contains?: string|null }} RuleMatch
 * @typedef {{ success?: RuleMatch|null, alreadyDone?: RuleMatch|null, expired?: RuleMatch|null, failed?: RuleMatch|null, failureOnSuccessMismatch?: boolean|null }} ResultRules
 * @typedef {{ path: string, valueText: string, contains: string }} MatchDraft
 * @typedef {{ success: MatchDraft, alreadyDone: MatchDraft, expired: MatchDraft, failed: MatchDraft, failureOnSuccessMismatch: boolean }} RuleDraft
 */
export const ruleKinds = [
  { key: 'success', name: '成功' },
  { key: 'alreadyDone', name: '已完成' },
  { key: 'expired', name: '凭证过期' },
  { key: 'failed', name: '业务失败' },
]

/** @param {ResultRules|null} [source] @returns {RuleDraft} */
export function createRuleDraft(source) {
  const rules = source ?? { success: { path: 'code', value: 0 } }
  return {
    ...Object.fromEntries(ruleKinds.map(({ key }) => {
      const match = rules[key]
      return [key, {
        path: match?.path ?? '',
        valueText: match ? JSON.stringify(match.value ?? null) : '',
        contains: match?.contains ?? '',
      }]
    })),
    failureOnSuccessMismatch: rules.failureOnSuccessMismatch === true,
  }
}

function parseValue(text) {
  if (!text.trim()) throw new Error('字段规则需填写 JSON 等于值，例如 0、"0"、true 或 null')
  let value
  try {
    value = JSON.parse(text)
  } catch {
    throw new Error('等于值需使用合法 JSON，例如数字 0 或字符串 "0"')
  }
  if (value !== null && !['string', 'number', 'boolean'].includes(typeof value))
    throw new Error('规则等于值需为字符串、数字、布尔或 null')
  if (typeof value === 'number' && !Number.isFinite(value)) throw new Error('规则数字需为有限值')
  return value
}

/** @param {RuleDraft} draft @returns {string} */
export function failureOnMismatchHint(draft) {
  const rule = draft.success
  if (!rule?.path.trim()) return '需要填写成功规则的 JSON 字段路径，纯文本规则不能反向判断。'
  if (!/^(?:\$\.)?[A-Za-z0-9_-]+(?:\[\d+\])?(?:\.[A-Za-z0-9_-]+(?:\[\d+\])?)*$/.test(rule.path.trim()))
    return '成功字段路径需为 code、data.code、data.items[0].code 等格式。'
  if (rule.contains.trim()) return '成功规则不能附加文本包含，避免把缺少文本误判成失败。'
  try {
    if (parseValue(rule.valueText) === null) return '成功等于值需为数字、字符串或布尔，不能是 null。'
  } catch (error) { return error.message }
  return ''
}

/** @param {RuleDraft} draft @returns {ResultRules} */
export function collectRules(draft) {
  const out = {}
  for (const { key } of ruleKinds) {
    const r = draft[key]
    if (!r.path.trim() && !r.contains.trim()) {
      out[key] = null
      continue
    }
    const value = r.path.trim() ? parseValue(r.valueText) : null
    out[key] = { path: r.path.trim(), value, contains: r.contains.trim() || null }
  }
  out.failureOnSuccessMismatch = draft.failureOnSuccessMismatch === true
  if (out.failureOnSuccessMismatch) {
    const hint = failureOnMismatchHint(draft)
    if (hint) throw new Error(`无法开启反向失败判断：${hint}`)
  }
  return out
}
