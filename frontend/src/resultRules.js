export const ruleKinds = [
  { key: 'success', name: '成功' },
  { key: 'alreadyDone', name: '已完成' },
  { key: 'expired', name: '凭证过期' },
  { key: 'failed', name: '业务失败' },
]

export function createRuleDraft(source) {
  const rules = source ?? { success: { path: 'code', value: 0 } }
  return Object.fromEntries(ruleKinds.map(({ key }) => {
    const match = rules[key]
    return [key, {
      path: match?.path ?? '',
      valueText: match ? JSON.stringify(match.value ?? null) : '',
      contains: match?.contains ?? '',
    }]
  }))
}

export function collectRules(draft) {
  const out = {}
  for (const { key } of ruleKinds) {
    const r = draft[key]
    if (!r.path.trim() && !r.contains.trim()) {
      out[key] = null
      continue
    }
    let value = null
    if (r.path.trim()) {
      if (!r.valueText.trim()) throw new Error('字段规则需填写 JSON 等于值，例如 0、"0"、true 或 null')
      try {
        value = JSON.parse(r.valueText)
      } catch {
        throw new Error('等于值需使用合法 JSON，例如数字 0 或字符串 "0"')
      }
    }
    out[key] = { path: r.path.trim(), value, contains: r.contains.trim() || null }
  }
  return out
}
