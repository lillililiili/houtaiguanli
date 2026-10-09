export function isBackendUser(user) {
  // 滚动更新期间旧后端尚未返回 user_type 时，只兼容原来受保护的超级管理员。
  return user?.user_type === 'BACKEND' || (!user?.user_type && user?.role_code === 'ROLE-ADMIN')
}

export function userTypeLabel(type) {
  return type === 'BACKEND' ? '后台用户' : '前台用户'
}
