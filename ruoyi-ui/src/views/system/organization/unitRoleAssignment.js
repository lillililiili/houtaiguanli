import { permissionName, permissionLevels, permissionPayload, permissionRank } from '@/views/unit/permissionDraft'

export const existingRoleKey = code => `existing:${code}`
export const newRoleKey = key => `new:${key}`

export function assignmentPayload(assignments) {
  return Object.entries(assignments).filter(([, value]) => Boolean(value)).map(([user_id, value]) => value.startsWith('new:')
    ? { user_id, new_role_key: value.slice(4) } : { user_id, role_code: value.slice(9) })
}

export function rolePlanPayload(plan) {
  const new_roles = plan.new_roles.map(role => ({ client_key: role.client_key, name: role.name.trim(), description: role.description.trim(), ...permissionPayload(role.permissions, role.actions) }))
  const member_assignments = assignmentPayload(plan.assignments)
  const retire_role_codes = [...plan.retire_role_codes]
  return { ...(new_roles.length ? { new_roles } : {}), ...(member_assignments.length ? { member_assignments } : {}), ...(retire_role_codes.length ? { retire_role_codes } : {}) }
}

export function assignmentRole(value, roles, newRoles) {
  return value?.startsWith('new:') ? newRoles.find(role => role.client_key === value.slice(4))
    : value?.startsWith('existing:') ? roles.find(role => role.role_code === value.slice(9)) : null
}

export function roleWithinCeiling(role, ceiling) {
  if (!ceiling) return role
  const permissions = (role?.permissions || []).map(row => {
    const upper = (ceiling.permissions || []).find(item => item.permission_code === row.permission_code)
    const level = permissionRank(row.level) <= permissionRank(upper?.level) ? row.level : upper?.level || 'NONE'
    return { ...row, level, menu_enabled: level !== 'NONE' && Boolean(row.menu_enabled && upper?.menu_enabled) }
  })
  const allowed = new Set((ceiling.actions || []).filter(row => row.level === 'OP').map(row => row.permission_code))
  const actions = (role?.actions || []).map(row => ({ ...row, level: (row.permission_code === 'disposal:direct' ? row.level === 'OP' : ['READ', 'OP', 'AUTH'].includes(row.level)) && allowed.has(row.permission_code) ? 'OP' : 'NONE' }))
  return { ...role, permissions, actions }
}

// Explain the exact changed grants; do not silently populate replacement roles from old ones.
export function rolePermissionDifferences(before, after, catalog = {}) {
  if (!before || !after) return []
  const label = level => permissionLevels.find(item => item.value === level)?.label || '无权限'
  const difference = []
  for (const kind of ['permissions', 'actions']) {
    const oldRows = new Map((before[kind] || []).map(row => [row.permission_code, row]))
    const newRows = new Map((after[kind] || []).map(row => [row.permission_code, row]))
    for (const code of new Set([...oldRows.keys(), ...newRows.keys()])) {
      const oldRow = oldRows.get(code), newRow = newRows.get(code)
      const oldLevel = oldRow?.level || 'NONE', newLevel = newRow?.level || 'NONE'
      const oldMenu = Boolean(oldRow?.menu_enabled), newMenu = Boolean(newRow?.menu_enabled)
      const permission = (catalog.permissions || []).find(row => row.permission_code === code)
      const action = (catalog.action_modules || []).flatMap(module => module.actions || []).find(row => row.permission_code === code)
      const name = kind === 'permissions' ? permissionName(permission || { permission_code: code }) : action?.name || code
      if (oldLevel !== newLevel) difference.push(`${name}：${label(oldLevel)} → ${label(newLevel)}`)
      if (kind === 'permissions' && oldMenu !== newMenu) difference.push(`${name}菜单：${oldMenu ? '显示' : '隐藏'} → ${newMenu ? '显示' : '隐藏'}`)
    }
  }
  return difference
}

export function rolePlanError(config, plan, nextCeiling) {
  const roles = config.unit_roles || [], members = config.members || []
  if (plan.new_roles.some(role => !role.name.trim())) return '请填写所有新角色的名称。'
  const names = [...roles, ...plan.new_roles].map(role => role.name.trim())
  if (new Set(names).size !== names.length) return '同一单位的角色名称不能重复。'
  if (plan.retire_role_codes.length && nextCeiling) {
    if ('administrator_user_id' in nextCeiling && (nextCeiling.administrator_user_id || null) !== (config.administrator_user_id || null)) return '清理副本时请保持单位管理员不变；管理员替换请另行保存。'
    const managementChanged = (nextCeiling.management_permissions || []).some(row => row.level !== ((config.management_permissions || []).find(item => item.module_key === row.module_key)?.level || 'NONE'))
    if (rolePermissionDifferences(config, nextCeiling, config.catalog).length || managementChanged) return '清理副本时请保持单位授权不变；如需调整前台或后台权限，请先单独保存。'
  }
  for (const member of members) {
    const current = roles.find(role => role.role_code === member.role_code)
    const value = plan.assignments[member.user_id]
    const retiring = plan.retire_role_codes.includes(member.role_code)
    const target = assignmentRole(value, roles, plan.new_roles)
    if (retiring && current?.enabled === false) return `${member.name || member.account}正在使用停用副本，请先单独处理其角色状态和分配。`
    const administrator = member.user_id === nextCeiling?.administrator_user_id
    if (((!current && !administrator) || retiring) && !target) return `请为${member.name || member.account}选择本单位角色。`
    if (!administrator && member.user_id === config.administrator_user_id && current?.enabled === false && !target) return '请为离任管理员选择启用的本单位人员角色。'
    if (value && (!target || target.enabled === false || plan.retire_role_codes.includes(target.role_code))) return `${member.name || member.account}选择的角色不可用，请重新选择。`
    if (retiring && rolePermissionDifferences(roleWithinCeiling(current, config), roleWithinCeiling(target, nextCeiling || config), config.catalog).length) return `${member.name || member.account}的新角色权限与待清理副本不一致，请按差异调整后再保存。`
  }
  return ''
}
