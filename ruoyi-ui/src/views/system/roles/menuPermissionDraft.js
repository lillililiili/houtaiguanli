// 角色菜单权限草稿：菜单方框与权限等级必须一致（#3）。
// 选了等级（非“无”）即勾选菜单；把等级改回“无”或取消勾选时两者一起取消。
export function applyMenuLevel(row, level) {
  row.level = level
  row.menu_enabled = level !== 'NONE'
}

export function applyMenuEnabled(row, enabled) {
  row.menu_enabled = enabled
  if (enabled && row.level === 'NONE') row.level = 'READ'
  if (!enabled) row.level = 'NONE'
}

// 找出“勾了方框但等级为无”或“选了等级但没勾方框”的菜单；锁定项由服务端维护，不参与检查。
export function inconsistentMenus(rows, isLocked = () => false) {
  return rows.filter(row => !isLocked(row) && Boolean(row.menu_enabled) !== (row.level !== 'NONE'))
}
