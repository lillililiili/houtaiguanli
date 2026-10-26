import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive } from 'vue'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import UnitUsersView from '@/views/unit/UnitUsersView.vue'
import UnitRolesView from '@/views/unit/UnitRolesView.vue'
import UnitDelegationDialog from '@/views/system/organization/UnitDelegationDialog.vue'
import UnitPermissionEditor from '@/views/unit/UnitPermissionEditor.vue'
import { unitAdministrationApi as api } from '@/api/unitAdministration'

const identity = vi.hoisted(() => ({ user_id: 'manager-east', org_name: '东区单位' }))
const loadCurrentUser = vi.hoisted(() => vi.fn())
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ user: identity, loadCurrentUser }) }))
vi.mock('@/api/unitAdministration', () => ({ unitAdministrationApi: {
  catalog: vi.fn(), users: vi.fn(), roles: vi.fn(), role: vi.fn(), delegation: vi.fn(),
  preview: vi.fn(), apply: vi.fn(), createUser: vi.fn(), createRole: vi.fn(), updateRolePermissions: vi.fn(), deleteUser: vi.fn(), deleteRole: vi.fn()
} }))
vi.mock('element-plus', async original => ({
  ...await original(),
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  ElMessageBox: { confirm: vi.fn().mockResolvedValue(), prompt: vi.fn() }
}))
const menu = { permission_code: 'alarms', route_key: 'alarms', module_name: '异常告警', level: 'READ', menu_enabled: true }
const unit = { org_id: 'org-east', org_name: '东区单位', administrator_user_id: 'manager-east', permissions: [menu], actions: [{ permission_code: 'alarm:verify', level: 'OP' }], action_modules: [
  { module_code: 'alarms', module_name: '告警', actions: [{ permission_code: 'alarm:verify', name: '核实告警' }, { permission_code: 'disposal:direct', name: '直接反制' }] }
] }
const role = { role_code: 'UNIT-ROLE-1', name: '值班员', enabled: true, version: 3, user_count: 1, permissions: [menu], actions: [] }
let app, host, vm
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function validation(overrides = {}) {
  return { preview_token: 'preview-bound-version', expires_at: Date.now() + 60000, org_id: 'org-east', expected_version: 4, ...overrides }
}
async function settle() { for (let i = 0; i < 16; i++) { await Promise.resolve(); await nextTick() } }
async function mount(component, props = {}) {
  host = document.createElement('div'); document.body.append(host)
  app = createApp(component, props); app.use(ElementPlus); vm = app.mount(host); await settle()
}
function button(label, root = document.body) { return [...root.querySelectorAll('button')].find(item => item.textContent.trim() === label) }
beforeEach(() => {
  vi.clearAllMocks()
  loadCurrentUser.mockResolvedValue(identity)
  identity.management_context = { mode: 'UNIT', permission_codes: [], menu_keys: ['unit-users', 'unit-roles'] }
  identity.permission_codes = ['alarms.read']; identity.menu_keys = ['alarms']; identity.data_scope = 'OWN_ORG'
  api.catalog.mockResolvedValue(unit)
  api.roles.mockResolvedValue([role])
  api.role.mockResolvedValue(role)
  api.users.mockResolvedValue({ total: 2, items: [
    { user_id: 'manager-east', account: 'unit-admin', name: '单位管理员', role_name: '管理员业务角色', status: 'ACTIVE' },
    { user_id: 'staff-east', account: 'staff', name: '值班人员', role_name: '值班员', status: 'ACTIVE' }
  ] })
  api.delegation.mockResolvedValue({ ...unit, managed: true, version: 4, administrator_candidates: [{ user_id: 'manager-east', account: 'unit-admin', name: '单位管理员' }], catalog: { permissions: [menu], action_modules: unit.action_modules } })
  api.preview.mockResolvedValue(validation())
  api.apply.mockResolvedValue({})
})
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = '' })

describe('本单位人员页面', () => {
  it.each(['confirm', 'cancel', 'close'])('删除人员无需原因，%s按确认结果处理', async action => {
    const person = { user_id: `person-${crypto.randomUUID()}`, account: '待删除人员', name: '值班人员', version: 8, status: 'DISABLED' }
    api.users.mockResolvedValue({ total: 1, items: [person] })
    api.deleteUser.mockResolvedValue({})
    if (action === 'confirm') ElMessageBox.confirm.mockResolvedValueOnce('confirm')
    else ElMessageBox.confirm.mockRejectedValueOnce(action)
    await mount(UnitUsersView)
    const row = [...host.querySelectorAll('.el-table__body tr')].find(item => item.textContent.includes(person.account))
    button('删除', row).click(); await settle()
    expect(ElMessageBox.prompt).not.toHaveBeenCalled()
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining(person.account), '删除人员', expect.objectContaining({ confirmButtonText: '确认删除', cancelButtonText: '取消' }))
    if (action === 'confirm') expect(api.deleteUser).toHaveBeenCalledWith(person.user_id, 8, '用户在界面确认删除（系统记录，未填写原因）')
    else expect(api.deleteUser).not.toHaveBeenCalled()
  })
  it('复制角色在人员列和选择器显示单位副本，提交仍使用原角色编号', async () => {
    const copy = { ...role, role_code: `copy-${crypto.randomUUID()}`, copy_origin: { source_role_code: 'former-global', source_role_name: '值班员', copied_at: Date.now() - 1000 } }
    api.roles.mockResolvedValue([copy])
    api.users.mockResolvedValue({ total: 1, items: [{ user_id: 'staff-east', account: 'staff', name: '值班人员', role_code: copy.role_code, role_name: copy.name, status: 'ACTIVE' }] })
    api.createUser.mockResolvedValue({})
    await mount(UnitUsersView)
    expect(host.querySelector('.el-table__body').textContent).toContain('值班员（单位副本）')
    button('新增人员').click(); await settle()
    const state = vm.$.setupState
    Object.assign(state.dialog.form, { account: 'new-duty', name: '新值班员', phone: '', role_code: copy.role_code, temporary_password: 'Sample#7123' })
    await settle()
    expect(document.querySelector('.el-dialog .el-select input').value).toBe('值班员（单位副本）')
    button('保存').click(); await settle()
    expect(api.createUser).toHaveBeenCalledWith(expect.objectContaining({ role_code: copy.role_code }))
    expect(api.createUser.mock.calls[0][0]).not.toHaveProperty('copy_origin')
    expect(copy.name).toBe('值班员')
  })

  it('本人行只有平台维护提示，其他人员显示管理动作；请求不接受页面单位参数', async () => {
    await mount(UnitUsersView)
    const rows = [...host.querySelectorAll('.el-table__body tr')]
    const self = rows.find(row => row.textContent.includes('unit-admin'))
    const staff = rows.find(row => row.textContent.includes('staff'))
    expect(self.textContent).toContain('平台维护')
    expect(self.querySelectorAll('button')).toHaveLength(0)
    expect(button('修改', staff)).toBeTruthy()
    expect(button('重置密码', staff)).toBeTruthy()
    expect(api.users).toHaveBeenCalledWith({ keyword: '', roleCode: '', status: '', page: 1, size: 20 })
    expect(host.textContent).not.toContain('全部单位')
  })
  it('新增人员固定前台身份及本单位数据范围，不提供可编辑范围和单位', async () => {
    api.createUser.mockResolvedValue({})
    await mount(UnitUsersView)
    button('新增人员').click(); await settle()
    const state = vm.$.setupState
    Object.assign(state.dialog.form, { account: 'new-duty', name: '新值班员', phone: '', role_code: 'UNIT-ROLE-1', temporary_password: 'Sample#7123' })
    await settle()
    button('保存').click(); await settle()
    expect(api.createUser).toHaveBeenCalledWith(expect.objectContaining({ org_id: 'org-east', user_type: 'FRONTEND', data_scope: 'OWN_ORG', role_code: 'UNIT-ROLE-1' }))
  })
})

describe('本单位角色保护', () => {
  it('无人员角色确认后删除，不要求填写原因', async () => {
    const unused = { ...role, role_code: `role-${crypto.randomUUID()}`, user_count: 0 }
    api.roles.mockResolvedValue([unused]); api.role.mockResolvedValue(unused)
    api.deleteRole.mockResolvedValue({}); ElMessageBox.confirm.mockResolvedValueOnce('confirm')
    await mount(UnitRolesView)
    button('删除角色', host).click(); await settle()
    expect(ElMessageBox.prompt).not.toHaveBeenCalled()
    expect(api.deleteRole).toHaveBeenCalledWith(unused.role_code, unused.version, '用户在界面确认删除（系统记录，未填写原因）')
  })
  it.each(['会话', '目录'])('当前权限%s请求失败不把旧会话中的权限展示成有效授权，也不伪装成零权限', async failure => {
    if (failure === '会话') loadCurrentUser.mockRejectedValueOnce(new Error('当前会话已失效'))
    else api.catalog.mockRejectedValueOnce(new Error('权限目录读取失败'))
    identity.permission_codes = ['alarms.auth', 'disposal:direct']; identity.menu_keys = ['alarms']
    identity.management_context.permission_codes = ['devices.read', 'devices.op']
    identity.management_context.menu_keys.push('devices')
    await mount(UnitRolesView)
    ;[...host.querySelectorAll('[role=tab]')].find(tab => tab.textContent.trim() === '我的权限').click(); await settle()
    const panel = host.querySelector('[aria-label="我的实际权限"]')
    expect(panel.textContent).toContain('暂时无法核验当前权限')
    expect(panel.textContent).not.toContain('异常告警')
    expect(panel.textContent).not.toContain('尚未分配')
    expect(host.querySelector('[aria-label="我的后台权限"]')).toBeNull()
  })

  it('我的权限显示有效账号权限，不再展示自动副本角色卡和来源', async () => {
    const copiedRole = { ...role, platform_maintained: true, copy_origin: { source_role_code: `deleted-${crypto.randomUUID()}`, source_role_name: '平台综合查看角色', copied_at: Date.now() - 1000 } }
    api.roles.mockResolvedValue([copiedRole]); api.role.mockResolvedValue(copiedRole)
    await mount(UnitRolesView)
    ;[...host.querySelectorAll('[role=tab]')].find(tab => tab.textContent.trim() === '我的权限').click(); await settle()
    expect(host.querySelector('.unit-role-list')).toBeNull()
    expect(host.textContent).not.toContain('单位副本')
    expect(host.textContent).not.toContain('平台综合查看角色')
    expect(host.querySelector('[aria-label="我的实际权限"]').textContent).toContain('异常告警')
    expect(host.querySelector('[aria-label="我的实际权限"]').textContent).toContain('仅本单位，不含下级单位')
    expect(host.querySelector('[aria-label="我的实际权限"] .el-select')).toBeNull()
    expect(api.role).not.toHaveBeenCalled()
    expect(api.updateRolePermissions).not.toHaveBeenCalled()
    expect(copiedRole.name).toBe('值班员')
  })
  it('人员角色加载失败仍可查看本人权限，单位上限与后台权限不能冒充前台授权', async () => {
    api.roles.mockRejectedValueOnce(new Error('人员角色读取失败'))
    identity.permission_codes = ['alarm:verify']
    identity.menu_keys = []
    identity.management_context.permission_codes = ['devices.read', 'devices.op']
    identity.management_context.menu_keys.push('devices')
    await mount(UnitRolesView)
    ;[...host.querySelectorAll('[role=tab]')].find(tab => tab.textContent.trim() === '我的权限').click(); await settle()
    expect(host.querySelector('[aria-label="我的前台权限"]').textContent).not.toContain('异常告警')
    expect(host.querySelector('[aria-label="我的前台权限"]').textContent).toContain('核实告警')
    expect(host.querySelector('[aria-label="我的后台权限"]').textContent).toContain('设备管理：查看并操作')
    expect(api.role).not.toHaveBeenCalled()
  })

  it('默认只列可维护的人员角色，空态通过顶部入口创建，不把管理员角色当作人员角色', async () => {
    api.roles.mockResolvedValue([{ ...role, platform_maintained: true }])
    api.createRole.mockResolvedValue({ ...role, role_code: 'new-staff-role' })
    await mount(UnitRolesView)
    expect(host.querySelector('nav[aria-label="本单位角色列表"]').textContent).not.toContain('值班员')
    button('新增角色').click(); await settle()
    const input = document.querySelector('.el-dialog input')
    input.value = '新值班员'; input.dispatchEvent(new Event('input', { bubbles: true })); await settle()
    button('创建角色').click(); await settle()
    expect(api.createRole).toHaveBeenCalledWith(expect.objectContaining({ name: '新值班员' }))
    expect(api.updateRolePermissions).not.toHaveBeenCalled()
  })
  it('管理员使用的角色只读，隐藏保存和角色维护动作', async () => {
    api.roles.mockResolvedValue([{ ...role, platform_maintained: true }])
    api.role.mockResolvedValue({ ...role, platform_maintained: true })
    await mount(UnitRolesView)
    expect(host.textContent).toContain('暂无人员角色')
    ;[...host.querySelectorAll('[role=tab]')].find(tab => tab.textContent.trim() === '我的权限').click(); await settle()
    expect(host.textContent).toContain('继承本单位已开放的全部权限')
    expect(host.querySelector('.unit-role-detail .el-select')).toBeNull()
    expect(host.querySelector('.unit-role-detail input[type=checkbox]')).toBeNull()
    expect(button('保存权限', host)).toBeUndefined()
    expect(button('删除角色', host)).toBeUndefined()
    expect(button('编辑说明', host)).toBeUndefined()
    expect(host.querySelector('[aria-label="我的后台权限"]').textContent).toContain('固定管理：本单位人员、本单位角色。')
    expect(host.querySelector('[aria-label="我的后台权限"]').textContent).toContain('尚未开通其他后台运维功能')
    expect(api.updateRolePermissions).not.toHaveBeenCalled()
  })
  it('没有前台角色也能查看后台独立授权，不把业务动作显示成后台权限', async () => {
    api.roles.mockResolvedValue([])
    identity.permission_codes = ['devices.op', 'statistics.op']
    identity.management_context.permission_codes = ['monitoring.read', 'statistics.read', 'statistics.op']
    identity.management_context.menu_keys.push('monitoring', 'statistics')
    await mount(UnitRolesView)
    ;[...host.querySelectorAll('[role=tab]')].find(tab => tab.textContent.trim() === '我的权限').click(); await settle()
    const summary = host.querySelector('[aria-label="我的后台权限"]')
    expect(summary.textContent).toContain('设备实时监测：查看')
    expect(summary.textContent).toContain('报表管理：查看并操作')
    expect(summary.textContent).not.toContain('设备管理：')
    expect(summary.querySelector('input,select,button')).toBeNull()
    expect(api.createRole).not.toHaveBeenCalled()
  })
  it('权限编辑器按单位动作上限禁用未开放动作，READ不能代表直接反制', async () => {
    await mount(UnitPermissionEditor, { permissions: [menu], actions: [{ permission_code: 'disposal:direct', level: 'READ' }], modules: unit.action_modules, ceiling: [menu], actionCeiling: unit.actions })
    const direct = [...host.querySelectorAll('.action-row')].find(row => row.textContent.includes('直接反制'))
    expect(direct.querySelector('input').disabled).toBe(true)
    expect(direct.querySelector('input').checked).toBe(false)
    expect(direct.textContent).toContain('单位未开放')
    const verify = [...host.querySelectorAll('.action-row')].find(row => row.textContent.includes('核实告警'))
    expect(verify.querySelector('input').disabled).toBe(false)
  })
})

describe('平台单位授权一次保存', () => {
  const props = () => ({ visible: true, organization: { org_id: 'org-east', name: '东区单位' } })
  it('首次纳管要求为普通人员分配，手动新增角色默认零权限，绑定和角色一次保存', async () => {
    const person = { user_id: `member-${crypto.randomUUID()}`, account: 'unit-staff', name: '值班员', role_code: 'GLOBAL-OLD', role_name: '原全局角色' }
    api.delegation.mockResolvedValueOnce({ ...unit, managed: false, version: 4, members: [person], unit_roles: [], catalog: { permissions: [menu], action_modules: unit.action_modules } })
    await mount(UnitDelegationDialog, props())
    button('保存并立即生效').click(); await settle()
    expect(api.preview).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('请为值班员选择本单位角色。')
    button('新增单位角色').click(); await settle()
    const state = vm.$.setupState
    expect(state.rolePlan.new_roles[0].permissions.every(item => item.level === 'NONE' && !item.menu_enabled)).toBe(true)
    expect(state.rolePlan.new_roles[0].actions).toEqual([])
    state.rolePlan.new_roles[0].name = '本单位值班角色'
    state.rolePlan.assignments[person.user_id] = `new:${state.rolePlan.new_roles[0].client_key}`
    button('保存并立即生效').click(); await settle()
    const body = api.apply.mock.calls[0][1]
    expect(body.new_roles).toEqual([expect.objectContaining({ name: '本单位值班角色', permissions: [{ permission_code: 'alarms', level: 'NONE', menu_enabled: false }], actions: [] })])
    expect(body.member_assignments).toEqual([{ user_id: person.user_id, new_role_key: body.new_roles[0].client_key }])
    expect(body).not.toHaveProperty('retire_role_codes')
    expect(api.createRole).not.toHaveBeenCalled()
  })

  it('只有管理员时无需新建角色或重分配，界面明确继承单位授权', async () => {
    const person = { user_id: 'manager-east', account: 'unit-admin', name: '单位管理员', role_code: 'GLOBAL-OLD', role_name: '原全局角色' }
    api.delegation.mockResolvedValueOnce({ ...unit, managed: false, version: 4, members: [person], unit_roles: [], catalog: { permissions: [menu], action_modules: unit.action_modules } })
    await mount(UnitDelegationDialog, props())
    expect(document.body.textContent).toContain('直接继承平台为本单位开放的全部前台权限和后台功能')
    vm.$.setupState.activeTab = 'members'; await settle()
    const members = document.querySelector('[aria-label="人员角色分配表"]')
    expect(members.textContent).toContain('保留绑定，管理员权限由单位授权决定')
    expect(members.textContent).not.toContain('需改为本单位角色')
    button('保存并立即生效').click(); await settle()
    expect(api.apply).toHaveBeenCalledOnce()
    const body = api.apply.mock.calls[0][1]
    expect(body).not.toHaveProperty('new_roles')
    expect(body).not.toHaveProperty('member_assignments')
    expect(api.createRole).not.toHaveBeenCalled()
  })

  it('清理副本必须显式迁移每个人且展示权限差异，不一致时不提交', async () => {
    const copy = { ...role, copy_origin: { source_role_code: 'old', source_role_name: '旧角色' }, actions: [] }
    const target = { ...role, role_code: 'FORMAL-ROLE', name: '正式值班员', permissions: [{ ...menu, level: 'NONE', menu_enabled: false }] }
    const person = { user_id: 'manager-east', account: 'unit-admin', name: '单位管理员', role_code: copy.role_code, role_name: copy.name }
    api.delegation.mockResolvedValueOnce({ ...unit, managed: true, version: 4, members: [person], unit_roles: [copy, target], catalog: { permissions: [menu], action_modules: unit.action_modules } })
    await mount(UnitDelegationDialog, props())
    const state = vm.$.setupState
    state.rolePlan.retire_role_codes = [copy.role_code]
    state.rolePlan.assignments[person.user_id] = `existing:${target.role_code}`
    button('保存并立即生效').click(); await settle()
    expect(document.body.textContent).toContain('异常告警：查看 → 无权限')
    expect(api.preview).not.toHaveBeenCalled()
    state.config.unit_roles[1].permissions = [{ ...menu }]
    button('保存并立即生效').click(); await settle()
    expect(api.apply).toHaveBeenCalledWith('org-east', expect.objectContaining({ retire_role_codes: [copy.role_code], member_assignments: [{ user_id: person.user_id, role_code: target.role_code }] }))
  })

  it('角色分配和新角色权限与预校验快照绑定，等待中修改不能串入提交', async () => {
    const check = deferred(); api.preview.mockReturnValueOnce(check.promise)
    api.delegation.mockResolvedValueOnce({ ...unit, managed: true, version: 4, members: [], unit_roles: [], catalog: { permissions: [menu], action_modules: unit.action_modules } })
    await mount(UnitDelegationDialog, props())
    const state = vm.$.setupState
    state.rolePlan.new_roles = [{ client_key: 'manual-key', name: '值班员', description: '', permissions: [{ ...menu }], actions: [] }]
    button('保存并立即生效').click(); await settle()
    state.rolePlan.new_roles[0].name = '被改动名称'
    state.rolePlan.new_roles[0].permissions[0].level = 'OP'
    state.rolePlan.retire_role_codes.push('unrelated')
    check.resolve(validation()); await settle()
    expect(api.apply.mock.calls[0][1].new_roles[0]).toMatchObject({ name: '值班员', permissions: [{ level: 'READ' }] })
    expect(api.apply.mock.calls[0][1]).not.toHaveProperty('retire_role_codes')
  })

  it('后台功能独立配置，监测不提供操作级别，保存不增加前台权限', async () => {
    await mount(UnitDelegationDialog, props())
    ;[...document.querySelectorAll('[role=tab]')].find(tab => tab.textContent.trim() === '后台功能').click(); await settle()
    const table = document.querySelector('[aria-label="单位后台功能"]')
    const row = name => [...table.querySelectorAll('.el-table__body tr')].find(row => row.textContent.includes(name))
    row('设备实时监测').querySelector('.el-select').click(); await settle()
    const monitorMenu = [...document.querySelectorAll('.el-select-dropdown')].find(menu => menu.textContent.replace(/\s/g, '') === '不开放查看')
    expect(monitorMenu).toBeTruthy()
    document.body.click(); await settle()
    row('设备管理').querySelector('.el-select').click(); await settle()
    const option = [...document.querySelectorAll('.el-select-dropdown__item')].find(item => item.textContent.trim() === '查看并操作')
    option.click(); await settle()
    button('保存并立即生效').click(); await settle()
    const body = api.apply.mock.calls[0][1]
    expect(body.management_permissions).toContainEqual({ module_key: 'devices', level: 'OP' })
    expect(body.permissions).toEqual([{ permission_code: 'alarms', level: 'READ', menu_enabled: true }])
    expect(body.actions).toEqual([{ permission_code: 'alarm:verify', level: 'OP' }])
  })
  it('一次点击先校验再提交同一配置，成功后关闭，不另弹确认或显示预览', async () => {
    const onSaved = vi.fn(), onVisible = vi.fn()
    await mount(UnitDelegationDialog, { ...props(), onSaved, 'onUpdate:visible': onVisible })
    expect([...document.querySelectorAll('.el-dialog__footer button')].map(item => item.textContent.trim())).toEqual(['取消', '保存并立即生效'])
    button('保存并立即生效').click(); await settle()
    expect(api.preview).toHaveBeenCalledOnce()
    expect(api.apply).toHaveBeenCalledOnce()
    const [orgId, body] = api.preview.mock.calls[0]
    expect(api.apply).toHaveBeenCalledWith(orgId, { ...body, preview_token: 'preview-bound-version' })
    expect(body).toEqual({ administrator_user_id: 'manager-east', expected_version: 4, management_permissions: ['devices', 'monitoring', 'maintenance', 'commissioning', 'statistics'].map(module_key => ({ module_key, level: 'NONE' })), permissions: [{ permission_code: 'alarms', level: 'READ', menu_enabled: true }], actions: [{ permission_code: 'alarm:verify', level: 'OP' }] })
    expect(api.preview.mock.invocationCallOrder[0]).toBeLessThan(api.apply.mock.invocationCallOrder[0])
    expect(ElMessageBox.confirm).not.toHaveBeenCalled()
    expect(document.querySelector('[aria-label="授权变化预览"]')).toBeNull()
    expect(onSaved).toHaveBeenCalledOnce()
    expect(onVisible).toHaveBeenCalledWith(false)
  })
  it('校验与提交期间均禁止重复点击，取消和编辑也禁用', async () => {
    const check = deferred(), apply = deferred()
    api.preview.mockReturnValueOnce(check.promise)
    api.apply.mockReturnValueOnce(apply.promise)
    await mount(UnitDelegationDialog, props())
    const save = button('保存并立即生效')
    save.click(); save.click(); await settle()
    expect(api.preview).toHaveBeenCalledOnce()
    expect(api.apply).not.toHaveBeenCalled()
    expect(save.disabled).toBe(true)
    expect(button('取消').disabled).toBe(true)
    expect(document.querySelector('.delegation-content .el-select input').disabled).toBe(true)
    check.resolve(validation()); await settle()
    save.click(); await settle()
    expect(api.apply).toHaveBeenCalledOnce()
    expect(save.disabled).toBe(true)
    apply.resolve({}); await settle()
    expect(ElMessage.success).toHaveBeenCalledOnce()
  })
  it('请求使用点击时的独立快照，等待期间表单或版本变化不串入提交', async () => {
    const check = deferred()
    api.preview.mockReturnValueOnce(check.promise)
    await mount(UnitDelegationDialog, props())
    button('保存并立即生效').click(); await settle()
    const expected = JSON.parse(JSON.stringify(api.preview.mock.calls[0][1]))
    const state = vm.$.setupState
    state.draft.administrator_user_id = ''
    state.draft.permissions[0].level = 'NONE'
    state.draft.actions[0].level = 'NONE'
    state.config.version = 9
    check.resolve(validation()); await settle()
    expect(api.preview).toHaveBeenCalledWith('org-east', expected)
    expect(api.apply).toHaveBeenCalledWith('org-east', { ...expected, preview_token: 'preview-bound-version' })
  })
  it.each(['切换单位', '关闭弹窗', '组件卸载'])('校验期间%s不再提交，也不显示成功', async change => {
    const check = deferred()
    api.preview.mockReturnValueOnce(check.promise)
    const organization = reactive({ org_id: 'org-east', name: '东区单位' })
    await mount(UnitDelegationDialog, { visible: true, organization })
    button('保存并立即生效').click(); await settle()
    if (change === '切换单位') organization.org_id = 'org-west'
    else if (change === '关闭弹窗') app._instance.props.visible = false
    else { app.unmount(); app = null }
    await settle()
    check.resolve(validation()); await settle()
    expect(api.apply).not.toHaveBeenCalled()
    expect(ElMessage.success).not.toHaveBeenCalled()
  })
  it('校验失败不提交，保持弹窗并显示原因', async () => {
    api.preview.mockRejectedValueOnce(new Error('所选管理员已停用'))
    const onVisible = vi.fn()
    await mount(UnitDelegationDialog, { ...props(), 'onUpdate:visible': onVisible })
    button('保存并立即生效').click(); await settle()
    expect(api.apply).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('所选管理员已停用')
    expect(ElMessage.success).not.toHaveBeenCalled()
    expect(onVisible).not.toHaveBeenCalled()
  })
  it.each([
    ['过期', { expires_at: Date.now() - 1000 }], ['缺凭据', { preview_token: '' }],
    ['其他单位', { org_id: 'org-west' }], ['其他版本', { expected_version: 5 }]
  ])('校验结果%s时拒绝提交并要求重新加载', async (_label, result) => {
    api.preview.mockResolvedValueOnce(validation(result))
    await mount(UnitDelegationDialog, props())
    button('保存并立即生效').click(); await settle()
    expect(api.apply).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('请重新加载后重试')
    expect(button('保存并立即生效').disabled).toBe(true)
  })
  it.each([
    ['DELEGATION_PREVIEW_STALE', 409], ['VERSION_CONFLICT', 409], ['TIMEOUT', 0], ['NETWORK_ERROR', 0]
  ])('提交遇到%s后先重新读取，不重复提交或误报成功', async (code, status) => {
    api.apply.mockRejectedValueOnce(Object.assign(new Error('操作结果尚未确认，请先核对最新记录'), { code, status }))
    const onVisible = vi.fn()
    await mount(UnitDelegationDialog, { ...props(), 'onUpdate:visible': onVisible })
    button('保存并立即生效').click(); await settle()
    expect(button('保存并立即生效').disabled).toBe(true)
    button('保存并立即生效').click(); await settle()
    expect(api.apply).toHaveBeenCalledOnce()
    expect(ElMessage.success).not.toHaveBeenCalled()
    expect(onVisible).not.toHaveBeenCalled()
    if (status === 409) expect(document.body.textContent).toContain('请重新加载后重试')
    button('重新加载').click(); await settle()
    expect(api.delegation).toHaveBeenCalledTimes(2)
    expect(button('保存并立即生效').disabled).toBe(false)
    expect(api.apply).toHaveBeenCalledOnce()
  })
  it.each([
    ['ROLE_IN_USE', '角色仍被注册历史引用，不能删除'],
    ['DUPLICATE_ROLE', '同一单位内角色名称已存在']
  ])('业务冲突%s保留实际原因，不误报快照过期或强制重载', async (code, message) => {
    api.apply.mockRejectedValueOnce(Object.assign(new Error(message), { code, status: 409 }))
    await mount(UnitDelegationDialog, props())
    button('保存并立即生效').click(); await settle()
    expect(document.body.textContent).toContain(message)
    expect(document.body.textContent).not.toContain('单位人员、角色或授权已变化')
    expect(button('保存并立即生效').disabled).toBe(false)
    expect(api.delegation).toHaveBeenCalledOnce()
    expect(ElMessage.success).not.toHaveBeenCalled()
  })
  it('首次启用没有管理员时不请求，已纳管单位可清空后保存撤任', async () => {
    api.delegation.mockResolvedValueOnce({ ...unit, managed: false, version: 0, administrator_user_id: null, catalog: { permissions: [menu], action_modules: unit.action_modules } })
    await mount(UnitDelegationDialog, props())
    button('保存并立即生效').click(); await settle()
    expect(api.preview).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('首次启用请指定本单位管理员。')
    vm.$.setupState.config.managed = true
    vm.$.setupState.config.version = 4
    button('保存并立即生效').click(); await settle()
    expect(api.apply).toHaveBeenCalledWith('org-east', expect.objectContaining({ administrator_user_id: null }))
  })
})
