// Copy provenance is supplied by the server; equal names alone never imply a copy.
export function roleCopySource(role) {
  const origin = role?.copy_origin
  return typeof origin?.source_role_code === 'string' && origin.source_role_code.trim()
    && typeof origin.source_role_name === 'string' && origin.source_role_name.trim()
    ? `创建来源：启用单位管理时，从当时的“${origin.source_role_name}”复制` : ''
}

export function roleDisplayName(role) {
  const name = role?.name || ''
  return roleCopySource(role) ? `${name}（单位副本）` : name
}

export function userRoleDisplayName(user, roles) {
  if (user.unit_administrator) return '单位管理员（继承单位授权）'
  const role = roles.find(item => item.role_code === user.role_code)
  return role ? roleDisplayName(role) : user.role_name || ''
}
