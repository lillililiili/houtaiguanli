import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { authApi } from '@/api/auth'
import { accessibleItems, canAccessMenu, firstAccessiblePath } from '@/config/navigation'
import { canEnterManagement, isBackendUser } from '@/utils/userType'
import { makePermissionDraft, permissionPayload, setPermissionLevel } from '@/views/unit/permissionDraft'
import { readToken } from '@/services/apiClient'

vi.mock('@/api/auth', () => ({ authApi: { me: vi.fn(), login: vi.fn() } }))
const manager = org => ({
  user_id: `manager-${org}`, user_type: 'FRONTEND', org_id: org,
  menu_keys: ['users', 'roles', 'devices', 'alarms'],
  permission_codes: ['users.read', 'roles.read', 'devices.read', 'alarms.read'],
  management_context: { mode: 'UNIT', org_id: org, menu_keys: ['unit-users', 'unit-roles'], permission_codes: ['unit-users.manage', 'unit-roles.manage'] }
})
beforeEach(() => { vi.clearAllMocks(); sessionStorage.clear(); setActivePinia(createPinia()); authApi.login.mockResolvedValue({ session_id: 'unit-session' }) })

describe('单位管理员工作区隔离', () => {
  it.each(['unit-east', 'unit-west'])('单位 %s 的前台管理员可登录后台但不变为后台身份', async org => {
    const user = manager(org); authApi.me.mockResolvedValue(user)
    const auth = useAuthStore()
    await auth.login({ account: user.user_id, password: 'test-only' })
    expect(auth.authenticated).toBe(true)
    expect(isBackendUser(auth.user)).toBe(false)
    expect(firstAccessiblePath(auth.user)).toBe('/unit/users')
    expect(accessibleItems(auth.user).map(item => item.path)).toEqual(['/unit/users', '/unit/roles'])
    expect(canAccessMenu(auth.user, 'users')).toBe(false)
    expect(canAccessMenu(auth.user, 'devices')).toBe(false)
  })
  it.each(['NONE', 'PLATFORM'])('前台身份不能凭管理模式 %s 获得后台准入', async mode => {
    authApi.me.mockResolvedValue({ ...manager('org-a'), management_context: { ...manager('org-a').management_context, mode } })
    await expect(useAuthStore().login({ account: 'member', password: 'test' })).rejects.toMatchObject({ code: 'BACKEND_ACCESS_DENIED' })
    expect(readToken()).toBe('')
  })
  it('缺单位或缺管理权限时拒绝准入或不展示受限菜单', () => {
    const user = manager('org-a')
    expect(canEnterManagement({ ...user, management_context: { ...user.management_context, org_id: null } })).toBe(false)
    expect(accessibleItems({ ...user, management_context: { ...user.management_context, permission_codes: ['unit-roles.manage'] } }).map(item => item.key)).toEqual(['unit-roles'])
  })
  it('业务权限不产生单位管理菜单，平台账号不进入单位专用路由', () => {
    const user = manager('org-a')
    expect(accessibleItems({ ...user, management_context: { ...user.management_context, menu_keys: [] } })).toEqual([])
    expect(canAccessMenu({ ...user, user_type: 'BACKEND' }, 'unit-users')).toBe(false)
  })
  it('仅后台独立授权开放五类运维菜单，业务同名权限不能放开写动作或平台页面', async () => {
    const user = manager('org-a')
    user.permission_codes.push('devices.op', 'statistics.op')
    user.management_context.menu_keys.push('devices', 'monitoring', 'maintenance', 'commissioning', 'statistics', 'maps')
    user.management_context.permission_codes.push('devices.read', 'monitoring.read', 'maintenance.read', 'commissioning.read', 'statistics.read', 'maps.read')
    authApi.me.mockResolvedValue(user)
    const auth = useAuthStore(); await auth.login({ account: 'manager', password: 'test' })
    expect(accessibleItems(auth.user).map(item => item.key)).toEqual(['unit-users', 'unit-roles', 'devices', 'monitor', 'maintenance', 'commission', 'stats'])
    expect(auth.hasPermission('devices.read')).toBe(true)
    expect(auth.hasPermission('devices.op')).toBe(false)
    expect(auth.hasPermission('statistics.op')).toBe(false)
    expect(auth.hasMenu('monitor')).toBe(true)
    expect(canAccessMenu(auth.user, 'maps')).toBe(false)
    user.management_context.permission_codes.push('devices.op')
    expect(auth.hasPermission('devices.op')).toBe(true)
    expect(auth.user.permission_codes).toContain('statistics.op')
  })
})

describe('单位权限编辑契约', () => {
  it('权限草稿只使用服务端目录，并保留权限与菜单一致性', () => {
    const catalog = [{ permission_code: 'alarms', route_key: 'alarms' }, { permission_code: 'devices', route_key: 'devices' }]
    const draft = makePermissionDraft(catalog, [{ permission_code: 'roles', level: 'AUTH', menu_enabled: true }])
    expect(draft.every(item => item.level === 'NONE')).toBe(true)
    const changed = setPermissionLevel(setPermissionLevel(draft, 'alarms', 'READ'), 'devices', 'OP')
    expect(permissionPayload(changed).permissions).toEqual([
      { permission_code: 'alarms', level: 'READ', menu_enabled: true },
      { permission_code: 'devices', level: 'OP', menu_enabled: false }
    ])
    expect(setPermissionLevel(changed, 'alarms', 'NONE')[0].menu_enabled).toBe(false)
    expect(draft[0].level).toBe('NONE')
  })
})
