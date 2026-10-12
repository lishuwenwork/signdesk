/** @typedef {{ id: string, name: string, accountAlias: string, version: number, enabled: boolean, templateId: string|null, templateName: string|null }} TemplateTarget */

/** @param {TemplateTarget[]} rows @param {string} keyword @returns {TemplateTarget[]} */
export function filterTemplateTargets(rows, keyword) {
  const term = keyword.trim().toLowerCase()
  return term ? rows.filter(row => `${row.accountAlias}\n${row.name}`.toLowerCase().includes(term)) : rows
}

/** @param {TemplateTarget[]} rows @param {string[]} ids @returns {{ id: string, version: number }[]} */
export function applicationTargets(rows, ids) {
  const selected = new Set(ids)
  if (!selected.size || selected.size > 10000 || selected.size !== ids.length)
    throw new Error('请选择 1～10000 个不同的请求')
  const targets = rows.filter(row => selected.has(row.id)).map(({ id, version }) => ({ id, version }))
  if (targets.length !== selected.size) throw new Error('请求范围已变化，请重新加载后选择')
  return targets
}
