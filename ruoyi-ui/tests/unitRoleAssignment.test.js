import { describe, it, expect } from 'vitest'
import { effectivePermissionSummary } from '@/views/unit/effectivePermissionSummary'
import { rolePlanError, roleWithinCeiling, rolePermissionDifferences, rolePlanPayload } from '@/views/system/organization/unitRoleAssignment'

const grant = level => ({ permission_code: 'alarms', route_key: 'alarms', level, menu_enabled: level !== 'NONE' })
const old = { role_code: 'old-copy', name: '旧副本', enabled: true, permissions: [grant('READ')], actions: [{ permission_code: 'alarm:verify', level: 'AUTH' }, { permission_code: 'disposal:direct', level: 'READ' }] }
const ceiling = { permissions: [grant('OP')], actions: [{ permission_code: 'alarm:verify', level: 'OP' }, { permission_code: 'disposal:direct', level: 'OP' }], management_permissions: [] }
const member = { user_id: 'member-a', name: '甲', role_code: old.role_code }
const formal = { role_code: 'formal-role', name: '正式角色', enabled: true, permissions: [grant('READ')], actions: [{ permission_code: 'alarm:verify', level: 'OP' }] }
const config = { ...ceiling, members: [member], unit_roles: [old, formal], catalog: { permissions: [grant('READ')], action_modules: [] } }
const plan = () => ({ new_roles: [], assignments: { [member.user_id]: 'existing:formal-role' }, retire_role_codes: [old.role_code] })

describe('显式角色分配与等价清理', () => {
  it('旧ordinary动作READ/AUTH按有效授权比较，但DIRECT仍只认OP', () => {
    expect(rolePermissionDifferences(roleWithinCeiling(old, ceiling), roleWithinCeiling(formal, ceiling))).toEqual([])
    expect(rolePlanError(config, plan(), ceiling)).toBe('')
    const escalated = { ...formal, actions: [...formal.actions, { permission_code: 'disposal:direct', level: 'OP' }] }
    expect(rolePermissionDifferences(roleWithinCeiling(old, ceiling), roleWithinCeiling(escalated, ceiling))).toContain('disposal:direct：无权限 → 操作')
  })
  it('不允许通过同时收紧单位上限掩盖旧副本权限损失', () => {
    expect(rolePlanError(config, plan(), { ...ceiling, permissions: [grant('NONE')] })).toContain('保持单位授权不变')
  })
  it.each(['another-admin', null])('清理副本不能同时替换或撤任单位管理员：%s', administrator_user_id => {
    const managed = { ...config, administrator_user_id: 'current-admin' }
    expect(rolePlanError(managed, plan(), { ...ceiling, administrator_user_id })).toContain('保持单位管理员不变')
    expect(rolePlanError(managed, plan(), { ...ceiling, administrator_user_id: 'current-admin' })).toBe('')
  })
  it('重复名包括待清理副本，停用源角色和遗漏成员均阻断', () => {
    const draft = plan(); draft.new_roles = [{ client_key: 'new-role', name: old.name, description: '', permissions: [], actions: [] }]
    expect(rolePlanError(config, draft, ceiling)).toContain('不能重复')
    expect(rolePlanError({ ...config, unit_roles: [{ ...old, enabled: false }, formal] }, plan(), ceiling)).toContain('停用副本')
    expect(rolePlanError({ ...config, members: [...config.members, { ...member, user_id: 'member-b', name: '乙', status: 'DISABLED' }] }, plan(), ceiling)).toContain('请为乙')
  })
  it('已有本单位绑定可保持不变，原全局绑定必须手动选择，输出无隐式创建', () => {
    const empty = { new_roles: [], assignments: {}, retire_role_codes: [] }
    expect(rolePlanError(config, empty, ceiling)).toBe('')
    expect(rolePlanPayload(empty)).toEqual({})
    expect(rolePlanError({ ...config, members: [{ ...member, role_code: 'GLOBAL' }] }, empty, ceiling)).toContain('请为甲')
  })
})

describe('我的实际权限只取用户级结果', () => {
  it('后台授权及单位上限不赋予前台菜单，缺菜单开关的模块不伪装可进入', () => {
    const catalog = { permissions: [grant('AUTH'), { permission_code: 'dashboard', route_key: 'bigscreen', level: 'AUTH' }], action_modules: [{ actions: [{ permission_code: 'alarm:verify' }, { permission_code: 'disposal:direct' }] }] }
    const user = { permission_codes: ['alarms.read', 'dashboard.op', 'alarm:verify'], menu_keys: ['bigscreen'], management_context: { permission_codes: ['devices.op'] } }
    expect(effectivePermissionSummary(user, catalog)).toMatchObject({ permissions: [{ permission_code: 'dashboard', level: 'OP' }], actions: [{ permission_code: 'alarm:verify', level: 'OP' }] })
    expect(effectivePermissionSummary({ permission_codes: [], menu_keys: [] }, catalog)).toEqual({ permissions: [], actions: [] })
  })
})
