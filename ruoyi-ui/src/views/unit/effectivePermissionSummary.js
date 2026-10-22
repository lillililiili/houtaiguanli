import { isBusinessMenu } from '@/config/businessMenus'

export function effectivePermissionSummary(user, catalog = {}) {
  const codes = new Set(user?.permission_codes || [])
  const menus = new Set(user?.menu_keys || [])
  const permissions = (catalog.permissions || []).map(row => ({
    ...row,
    level: codes.has(`${row.permission_code}.auth`) ? 'AUTH' : codes.has(`${row.permission_code}.op`) ? 'OP' : codes.has(`${row.permission_code}.read`) ? 'READ' : 'NONE',
    menu_enabled: menus.has(row.route_key)
  })).filter(row => row.level !== 'NONE' && (!isBusinessMenu(row) || row.menu_enabled))
  const actions = (catalog.action_modules || []).flatMap(module => module.actions || [])
    .filter(action => codes.has(action.permission_code)).map(action => ({ permission_code: action.permission_code, level: 'OP' }))
  return { permissions, actions }
}
