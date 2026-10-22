import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus from 'element-plus'
import RolesView from '@/views/system/RolesView.vue'
import { systemApi } from '@/api/system'
import { unitAdministrationApi } from '@/api/unitAdministration'

vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: () => true }) }))
vi.mock('@/api/system', () => ({ systemApi: {
  roles: vi.fn(), role: vi.fn(), organizations: vi.fn(), permissions: vi.fn(), permissionActions: vi.fn(),
  updateRolePermissions: vi.fn(), createRole: vi.fn(), updateRole: vi.fn(), deleteRole: vi.fn()
} }))
vi.mock('@/api/unitAdministration', () => ({ unitAdministrationApi: { delegation: vi.fn() } }))

let app, host, rows, orgId, missingOrgId, allowCreate
const longOrgName = '华东北部低空安全保障联合运行维护中心机场区域综合保障分中心'
const menu = { permission_code: 'alarms', route_key: 'alarms', level: 'READ', menu_enabled: true }
async function settle() { for (let i = 0; i < 12; i++) { await Promise.resolve(); await nextTick() } }
async function mount() {
  host = document.createElement('div'); document.body.append(host)
  app = createApp(RolesView); app.use(ElementPlus); app.mount(host); await settle()
}
const cards = () => [...host.querySelectorAll('.role-item')]
const hasLabel = (root, text) => [...root.querySelectorAll('span')].some(node => node.textContent.trim() === text)
async function search(value) {
  const input = host.querySelector('.role-sidebar__header input')
  input.value = value; input.dispatchEvent(new Event('input', { bubbles: true })); await settle()
}
async function tab(label) { [...host.querySelectorAll('.role-context-tabs [role="tab"]')].find(item => item.textContent.trim() === label).click(); await settle() }
async function selectOrg(label = longOrgName) {
  host.querySelector('.role-context-unit .el-select').click(); await settle()
  ;[...document.querySelectorAll('.el-select-dropdown__item')].find(item => item.textContent.trim() === label).click(); await settle()
}
const button = label => [...document.querySelectorAll('button')].find(item => item.textContent.trim() === label)

beforeEach(() => {
  vi.clearAllMocks()
  allowCreate = false
  orgId = `org-${crypto.randomUUID()}`
  missingOrgId = `org-${crypto.randomUUID()}`
  rows = [
    { role_code: `global-${crypto.randomUUID()}`, name: '值班员', description: '昼间巡视', owner_org_id: null },
    { role_code: `unit-${crypto.randomUUID()}`, name: '值班员', description: '夜间值守', owner_org_id: orgId },
    { role_code: `unit-${crypto.randomUUID()}`, name: '机动员', description: '临时保障', owner_org_id: missingOrgId }
  ].map(row => ({ ...row, builtin: false, user_count: 2, version: 3, permissions: [menu], actions: [] }))
  systemApi.roles.mockResolvedValue(rows)
  systemApi.role.mockImplementation(async code => structuredClone(rows.find(row => row.role_code === code)))
  systemApi.organizations.mockResolvedValue([{ org_id: orgId, name: longOrgName, delegation_managed: true }, { org_id: missingOrgId, name: '', delegation_managed: true }])
  systemApi.permissions.mockResolvedValue([menu])
  systemApi.permissionActions.mockResolvedValue([])
  unitAdministrationApi.delegation.mockImplementation(async id => ({ org_id: id, permissions: [{ ...menu, level: 'AUTH' }], actions: [] }))
})
afterEach(() => {
  // 搜索、选择和归属展示都是读取，不得保存或重建角色。
  for (const mutation of ['updateRolePermissions', 'createRole', 'updateRole', 'deleteRole']) {
    if (mutation !== 'createRole' || !allowCreate) expect(systemApi[mutation]).not.toHaveBeenCalled()
  }
  app?.unmount(); host?.remove(); document.body.innerHTML = ''
})

describe('平台角色归属展示与搜索', () => {
  it('自动复制的单位角色显示名称后缀与来源快照，来源角色不存在仍可搜索且不改绑定', async () => {
    const originalName = '北区联合保障夜间巡查与飞行任务综合查看角色'
    rows[1].name = '夜间值班员'
    rows[1].copy_origin = { source_role_code: `deleted-${crypto.randomUUID()}`, source_role_name: originalName, copied_at: Date.now() - 60000 }
    const before = structuredClone(rows[1])
    await mount()
    expect(cards()[0].textContent).not.toContain('单位副本')
    await tab('单位角色'); await selectOrg()
    expect(cards()[0].textContent).toContain('夜间值班员（单位副本）')
    expect(cards()[0].textContent).toContain(`创建来源：启用单位管理时，从当时的“${originalName}”复制`)
    expect(host.querySelector('.role-summary h2').textContent).toBe('夜间值班员（单位副本）')
    expect(host.querySelector('.role-copy-note').textContent).toContain('来源名称仅记录创建时的信息；本角色的权限独立维护。')
    await search('联合保障夜间巡查')
    expect(cards()).toHaveLength(1)
    cards()[0].click(); await settle()
    expect(systemApi.role).toHaveBeenLastCalledWith(before.role_code)
    expect(rows[1]).toEqual(before)
    expect(host.querySelector('[role="status"]').textContent).toContain('已生效权限')
  })

  it('同名全局与单位角色有独立标签和详情，点击始终读取原角色编号', async () => {
    await mount()
    expect(cards()).toHaveLength(1)
    expect(cards()[0].textContent).toContain('值班员')
    expect(hasLabel(cards()[0], '全局角色')).toBe(true)
    expect(hasLabel(host.querySelector('.role-summary'), '全局角色')).toBe(true)
    await tab('单位角色')
    expect(cards()).toHaveLength(0)
    expect(button('新增单位角色').disabled).toBe(true)
    await selectOrg()
    expect(cards()).toHaveLength(1)
    expect(cards()[0].textContent).toContain('值班员')
    expect(hasLabel(cards()[0], '单位角色')).toBe(true)
    cards()[0].click(); await settle()
    expect(systemApi.role).toHaveBeenLastCalledWith(rows[1].role_code)
    expect(unitAdministrationApi.delegation).toHaveBeenLastCalledWith(orgId)
    expect(hasLabel(host.querySelector('.role-summary'), '单位角色')).toBe(true)
    expect(host.querySelector('.role-summary').textContent).toContain(longOrgName)
    expect(cards()[0].getAttribute('aria-current')).toBe('true')
    await tab('全局角色')
    expect(systemApi.role).toHaveBeenLastCalledWith(rows[0].role_code)
    expect(hasLabel(host.querySelector('.role-summary'), '全局角色')).toBe(true)
    expect(host.querySelector('.role-summary').textContent).not.toContain(longOrgName)
    expect(host.querySelector('[role="status"]').textContent).toContain('已生效权限')
  })

  it('单位名称缺失时保留单位分类并显示归属编号，不误标为全局', async () => {
    await mount()
    await tab('单位角色'); await selectOrg(missingOrgId)
    const card = cards()[0]
    expect(hasLabel(card, '单位角色')).toBe(true)
    expect(hasLabel(card, '全局角色')).toBe(false)
    expect(card.textContent).toContain('所属单位')
    expect(card.textContent).toContain(missingOrgId)
    card.click(); await settle()
    const summary = host.querySelector('.role-summary')
    expect(hasLabel(summary, '单位角色')).toBe(true)
    expect(summary.textContent).toContain(missingOrgId)
    expect(systemApi.role).toHaveBeenLastCalledWith(rows[2].role_code)
  })

  it('长单位名称在列表与详情保留全文，并可按名称局部搜索选择', async () => {
    await mount()
    await tab('单位角色'); await selectOrg()
    const ownership = cards()[0].querySelector('.role-item__organization')
    expect(ownership.textContent).toContain('所属单位')
    expect(ownership.textContent).toContain(longOrgName)
    await search('机场区域综合保障')
    expect(cards()).toHaveLength(1)
    expect(cards()[0].textContent).toContain(longOrgName)
    cards()[0].click(); await settle()
    expect(systemApi.role).toHaveBeenLastCalledWith(rows[1].role_code)
    const detailOwnership = host.querySelector('.role-summary__organization')
    expect(detailOwnership.textContent).toContain('所属单位')
    expect(detailOwnership.textContent).toContain(longOrgName)
    await search('不存在的单位名称')
    expect(cards()).toHaveLength(0)
    expect(host.querySelector('.role-list').textContent).toContain('未找到匹配角色')
  })

  it('全局和单位类型可以搜索，原角色名称与说明搜索仍有效', async () => {
    await mount()
    await search('全局角色')
    expect(cards()).toHaveLength(1)
    expect(hasLabel(cards()[0], '全局角色')).toBe(true)
    await tab('单位角色'); await selectOrg()
    await search('单位角色')
    expect(cards()).toHaveLength(1)
    expect(cards().every(card => hasLabel(card, '单位角色'))).toBe(true)
    await search('值班员')
    expect(cards()).toHaveLength(1)
    await search('夜间值守')
    expect(cards()).toHaveLength(1)
    expect(cards()[0].textContent).toContain(longOrgName)
    await search('')
    expect(cards()).toHaveLength(1)
  })

  it.each(['global', 'unit'])('在%s上下文新增角色仅创建对应归属，不改已有角色', async context => {
    allowCreate = true
    systemApi.createRole.mockImplementation(async body => {
      const row = { ...rows[0], ...body, role_code: `new-${crypto.randomUUID()}` }
      rows.push(row); return row
    })
    await mount()
    if (context === 'unit') { await tab('单位角色'); await selectOrg() }
    button(context === 'unit' ? '新增单位角色' : '新增角色').click(); await settle()
    const input = document.querySelector('.el-dialog .role-fields input')
    input.value = '新值班角色'; input.dispatchEvent(new Event('input', { bubbles: true })); await settle()
    button('创建并立即生效').click(); await settle()
    expect(systemApi.createRole).toHaveBeenCalledOnce()
    const body = systemApi.createRole.mock.calls[0][0]
    if (context === 'unit') expect(body.owner_org_id).toBe(orgId)
    else expect(body).not.toHaveProperty('owner_org_id')
    expect(body.name).toBe('新值班角色')
  })
})
