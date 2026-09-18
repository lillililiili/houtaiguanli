import { describe, expect, it } from 'vitest'
import { accessibleItems, canAccessMenu, firstAccessiblePath } from '@/config/navigation'

describe('后台菜单权限', () => {
  it('规则管理沿用原预案权限和地址，不新增执行授权', () => {
    const user = { menu_keys: ['responsePlans'], permission_codes: ['responsePlans.read'] }
    expect(accessibleItems(user).map(item => [item.title, item.path])).toEqual([['规则管理', '/system/response-plans']])
    expect(canAccessMenu({ ...user, permission_codes: [] }, 'responsePlans')).toBe(false)
  })
  it('按菜单键和读取权限的交集生成路由', () => {
    const user = { menu_keys: ['devices', 'roles'], permission_codes: ['devices.read', 'roles.read'] }
    expect(accessibleItems(user).map(item => item.key)).toEqual(['devices', 'roles'])
    expect(firstAccessiblePath(user)).toBe('/operations/devices')
    expect(canAccessMenu(user, 'roles')).toBe(true)
  })

  it('单位资料入口并入用户管理，通知对象配置仍独立', () => {
    const user = { menu_keys: ['organizations', 'notificationSettings'], permission_codes: ['organizations.read', 'notificationSettings.read'] }
    expect(accessibleItems(user).map(item => item.path)).toEqual(['/system/users', '/system/notification-settings'])
    expect(canAccessMenu({ ...user, permission_codes: ['organizations.read'] }, 'notificationSettings')).toBe(false)
    expect(canAccessMenu({ ...user, menu_keys: [] }, 'organizations')).toBe(false)
  })

  it('没有任何管理菜单时进入明确空态', () => {
    expect(firstAccessiblePath({ menu_keys: [], permission_codes: [] })).toBe('/no-permission')
  })

  it('只有菜单键或只有权限码均不能访问', () => {
    expect(accessibleItems({ menu_keys: ['users'], permission_codes: [] })).toHaveLength(0)
    expect(accessibleItems({ menu_keys: [], permission_codes: ['users.read'] })).toHaveLength(0)
  })

  it('报表管理同时要求 stats 菜单和 statistics.read 权限', () => {
    const allowed = { menu_keys: ['stats'], permission_codes: ['statistics.read'] }
    expect(accessibleItems(allowed).map(item => item.path)).toEqual(['/operations/reports'])
    expect(canAccessMenu(allowed, 'stats')).toBe(true)
    expect(canAccessMenu({ menu_keys: ['stats'], permission_codes: [] }, 'stats')).toBe(false)
  })

  it('地图管理同时要求 maps 菜单和 maps.read 权限', () => {
    const allowed = { menu_keys: ['maps'], permission_codes: ['maps.read'] }
    expect(accessibleItems(allowed).map(item => item.path)).toEqual(['/operations/maps'])
    expect(canAccessMenu({ menu_keys: ['maps'], permission_codes: [] }, 'maps')).toBe(false)
  })
})
