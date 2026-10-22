import { describe, expect, it } from 'vitest'
import { roleCopySource, roleDisplayName, userRoleDisplayName } from '@/utils/rolePresentation'

describe('角色来源展示契约', () => {
  it.each([undefined, null, {}, { source_role_code: '', source_role_name: '值班员' }, { source_role_code: 'old', source_role_name: ' ' }])('没有有效来源关系不根据名称或单位推断自动复制：%j', copy_origin => {
    const role = { role_code: 'unit-staff', name: '值班员', owner_org_id: 'org-1', copy_origin }
    expect(roleDisplayName(role)).toBe('值班员')
    expect(roleCopySource(role)).toBe('')
  })

  it('人员角色用角色编号关联，保留同名人工角色与未加载目录的服务端原名', () => {
    const roles = [
      { role_code: 'manual', name: '值班员' },
      Object.freeze({ role_code: 'copy', name: '值班员', copy_origin: Object.freeze({ source_role_code: 'deleted', source_role_name: '原始值班员', copied_at: 123 }) })
    ]
    expect(userRoleDisplayName({ role_code: 'copy', role_name: '值班员' }, roles)).toBe('值班员（单位副本）')
    expect(userRoleDisplayName({ role_code: 'manual', role_name: '值班员' }, roles)).toBe('值班员')
    expect(userRoleDisplayName({ role_code: 'unknown', role_name: '历史角色名称' }, roles)).toBe('历史角色名称')
    expect(roleCopySource(roles[1])).toBe('创建来源：启用单位管理时，从当时的“原始值班员”复制')
    expect(roles[1].name).toBe('值班员')
  })
})
