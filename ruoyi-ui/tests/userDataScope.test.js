import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus, { ElMessage } from 'element-plus'
import UsersView from '@/views/system/UsersView.vue'
import { systemApi } from '@/api/system'
import { dataScopeLabel, isAssignableDataScope } from '@/utils/dataScope'

// ZT-14：管理员在用户管理里设置账号的数据范围；新账号默认只看本单位。
const auth = vi.hoisted(() => ({
  user: { menu_keys: ['users'], permission_codes: ['users.read', 'users.op', 'users.auth'] },
  hasPermission(code) { return this.user.permission_codes.includes(code) }
}))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => auth }))
vi.mock('@/api/organizationDirectory', () => ({ directoryApi: { profile: vi.fn(), profiles: vi.fn(), contacts: vi.fn(), contact: vi.fn(), bindings: vi.fn(), options: vi.fn(), saveProfile: vi.fn() } }))
vi.mock('@/api/system', () => ({ systemApi: { users: vi.fn(), organizations: vi.fn(), roles: vi.fn(), createUser: vi.fn(), updateUser: vi.fn(), updateOrganization: vi.fn() } }))
vi.mock('element-plus', async original => ({ ...await original(), ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }))

const orgs = [{ org_id: 'org-a', name: '甲单位', parent_id: null }, { org_id: 'org-a1', name: '甲单位下属队', parent_id: 'org-a' }]
const row = (overrides = {}) => ({ user_id: 'u-1', account: 'duty-a', name: '值班员甲', phone: '', org_id: 'org-a', org_name: '甲单位', role_code: 'ROLE-DUTY', role_name: '值班员', status: 'ACTIVE', data_scope: 'OWN_ORG', version: 4, ...overrides })
let app, host
async function settle() { for (let i = 0; i < 16; i++) { await Promise.resolve(); await nextTick() } }
async function mount() { host = document.createElement('div'); document.body.append(host); app = createApp(UsersView); app.use(ElementPlus); const vm = app.mount(host); await settle(); return vm }
const buttons = label => [...document.querySelectorAll('button')].filter(node => node.textContent.trim() === label)
const dialog = () => [...document.querySelectorAll('.el-dialog')].find(node => node.offsetParent !== null || node.style.display !== 'none')
const radio = label => [...dialog().querySelectorAll('.el-radio')].find(node => node.textContent.trim() === label)
const scopeCells = () => [...document.querySelectorAll('.el-table__body tr')].map(tr => [...tr.querySelectorAll('td')].map(td => td.textContent.trim()))

beforeEach(() => {
  vi.clearAllMocks()
  systemApi.organizations.mockResolvedValue(orgs)
  systemApi.roles.mockResolvedValue([{ role_code: 'ROLE-DUTY', name: '值班员', enabled: true }, { role_code: 'ROLE-ADMIN', name: '超级管理员', enabled: true }])
  systemApi.users.mockResolvedValue({ items: [row(), row({ user_id: 'u-2', account: 'leader', data_scope: 'ALL' }), row({ user_id: 'u-3', account: 'legacy', data_scope: 'CUSTOM' }), row({ user_id: 'u-0', account: 'admin1', role_code: 'ROLE-ADMIN', role_name: '超级管理员', data_scope: 'ALL' })], total: 4 })
  systemApi.updateUser.mockResolvedValue(row())
  systemApi.createUser.mockResolvedValue(row())
})
afterEach(async () => {
  app?.unmount()
  // Element Plus schedules dialog unlock (200 ms) and table layout cleanup on unmount.
  // Keep the DOM alive until those callbacks finish instead of leaking them into teardown.
  await new Promise(resolve => setTimeout(resolve, 250))
  host?.remove(); document.body.innerHTML = ''
})

// 整页挂载用户管理（含表格和单位树）较重，机器繁忙时放宽单条超时。
describe('账号数据范围', { timeout: 30_000 }, () => {
  it('范围名称：可选三种，早期账号的范围只展示', () => {
    expect(['ALL', 'OWN_ORG', 'OWN_ORG_TREE'].every(isAssignableDataScope)).toBe(true)
    expect(isAssignableDataScope('CUSTOM')).toBe(false)
    expect(dataScopeLabel('OWN_ORG_TREE')).toBe('本单位及下级单位')
    expect(dataScopeLabel('CUSTOM')).toBe('指定单位和区域')
  })

  it('用户列表显示每个账号的数据范围', async () => {
    await mount()
    const rows = scopeCells()
    expect(rows[0]).toContain('本单位')
    expect(rows[1]).toContain('全部单位')
    expect(rows[2]).toContain('指定单位和区域')
  })

  it('前台用户改为后台用户后无需选角色，并发送类型与全部单位范围', async () => {
    await mount()
    buttons('修改')[0].click(); await settle()
    radio('后台用户').querySelector('input').click(); await settle()
    expect(dialog().textContent).toContain('无需配置业务角色')
    expect(dialog().textContent).toContain('修改用户类型会撤销该账号全部旧会话')
    expect(radio('全部单位').querySelector('input').disabled).toBe(true)
    buttons('保存并立即生效')[0].click(); await settle()
    expect(systemApi.updateUser).toHaveBeenCalledWith('u-1', expect.objectContaining({ user_type: 'BACKEND', data_scope: 'ALL' }))
    expect(systemApi.updateUser.mock.calls[0][1]).not.toHaveProperty('role_code')
  })

  it('后台用户改回前台时必须重新选择业务角色', async () => {
    systemApi.users.mockResolvedValue({ items: [row({ user_type: 'BACKEND', role_code: 'ROLE-BACKEND', data_scope: 'ALL' })], total: 1 })
    await mount()
    buttons('修改')[0].click(); await settle()
    radio('前台用户').querySelector('input').click(); await settle()
    expect(dialog().textContent).not.toContain('无需配置业务角色')
    buttons('保存并立即生效')[0].click(); await settle()
    expect(systemApi.updateUser).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('请填写以下必填项：角色。')
    expect(dialog().querySelector('.el-form-item.is-error').textContent).toContain('角色')
  })

  it('新增用户默认只看本单位', async () => {
    await mount()
    buttons('新增用户')[0].click(); await settle()
    expect(radio('本单位').classList.contains('is-checked')).toBe(true)
    expect(dialog().textContent).toContain('只能看到所属单位的数据')
  })

  it('改成本单位及下级单位：提示需重新登录，并只发送改动的范围', async () => {
    await mount()
    buttons('修改')[0].click(); await settle()
    expect(dialog().textContent).not.toContain('需要重新登录')
    radio('本单位及下级单位').querySelector('input').click(); await settle()
    expect(dialog().textContent).toContain('保存后该用户需要重新登录')
    buttons('保存并立即生效')[0].click(); await settle()
    expect(systemApi.updateUser).toHaveBeenCalledWith('u-1', expect.objectContaining({ data_scope: 'OWN_ORG_TREE', org_id: 'org-a', expected_version: 4 }))
    expect(ElMessage.success).toHaveBeenCalledWith('用户资料已保存，该用户需要重新登录后按新的数据范围查看。')
  })

  it('早期账号不动范围时原样保留，不发送 data_scope', async () => {
    await mount()
    buttons('修改')[2].click(); await settle()
    expect(dialog().textContent).toContain('早期审批设置')
    expect(radio('全部单位').closest('.el-radio-group').querySelector('.el-radio.is-checked')).toBeNull()
    buttons('保存并立即生效')[0].click(); await settle()
    expect(systemApi.updateUser).toHaveBeenCalledWith('u-3', expect.not.objectContaining({ data_scope: expect.anything() }))
    expect(ElMessage.success).toHaveBeenCalledWith('用户资料已保存。')
  })

  it('超级管理员固定全部单位，不能改', async () => {
    await mount()
    buttons('修改')[3].click(); await settle()
    expect(radio('全部单位').classList.contains('is-checked')).toBe(true)
    expect(radio('本单位').classList.contains('is-disabled')).toBe(true)
    expect(dialog().textContent).toContain('超级管理员固定能看到全部单位的数据')
  })

  it('已纳管单位只提供本单位角色，并固定本单位数据范围', async () => {
    systemApi.organizations.mockResolvedValue(orgs.map(org => ({ ...org, delegation_managed: org.org_id === 'org-a' })))
    systemApi.roles.mockResolvedValue([
      { role_code: 'GLOBAL-DUTY', name: '全局值班角色', enabled: true },
      { role_code: 'LOCAL-A', name: '甲单位值班角色', enabled: true, owner_org_id: 'org-a' },
      { role_code: 'LOCAL-CHILD', name: '下级单位专用角色', enabled: true, owner_org_id: 'org-a1' }
    ])
    systemApi.users.mockResolvedValue({ items: [row({ role_code: 'LOCAL-A', data_scope: 'OWN_ORG' })], total: 1 })
    const vm = await mount()
    buttons('修改')[0].click(); await settle()
    expect(radio('本单位').classList.contains('is-checked')).toBe(true)
    expect(radio('全部单位').querySelector('input').disabled).toBe(true)
    expect(radio('本单位及下级单位').querySelector('input').disabled).toBe(true)
    expect(dialog().textContent).toContain('固定查看本单位数据')
    expect(vm.$.setupState.activeRoles.map(item => item.role_code)).toEqual(['LOCAL-A'])
    expect(vm.$.setupState.roleLabel({ name: '值班员', owner_org_id: 'org-a' })).toBe('值班员（甲单位）')
    expect(vm.$.setupState.roleLabel({ name: '值班员', owner_org_id: 'org-a1' })).toBe('值班员（甲单位下属队）')
    buttons('保存并立即生效')[0].click(); await settle()
    expect(systemApi.updateUser).toHaveBeenCalledWith('u-1', expect.objectContaining({ role_code: 'LOCAL-A', org_id: 'org-a' }))
  })
})

describe('新增用户逐格校验', { timeout: 30_000 }, () => {
  const errorLabels = () => [...dialog().querySelectorAll('.el-form-item.is-error .el-form-item__label')].map(node => node.textContent.trim())
  const type = (label, value) => {
    const input = [...dialog().querySelectorAll('.el-form-item')].find(item => item.querySelector('.el-form-item__label')?.textContent.trim() === label).querySelector('input')
    input.value = value; input.dispatchEvent(new Event('input', { bubbles: true }))
  }

  it('空着提交：登录账号、姓名、所属单位、角色、临时密码全部标红并一次列出', async () => {
    await mount()
    buttons('新增用户')[0].click(); await settle()
    buttons('保存并立即生效')[0].click(); await settle()
    expect(systemApi.createUser).not.toHaveBeenCalled()
    expect(errorLabels()).toEqual(expect.arrayContaining(['登录账号', '姓名', '所属单位', '角色', '临时密码']))
    expect(ElMessage.warning).toHaveBeenCalledWith('请填写以下必填项：登录账号、姓名、所属单位、角色、临时密码。')
    type('姓名', '值班员丁'); await settle()
    expect(errorLabels()).not.toContain('姓名')
    expect(errorLabels()).toContain('登录账号')
  })

  it('后台用户只要求账号、姓名、单位和密码；只漏密码时只标密码', async () => {
    await mount()
    buttons('新增用户')[0].click(); await settle()
    radio('后台用户').querySelector('input').click(); await settle()
    type('登录账号', `ops-${Date.now()}`); type('姓名', '运维乙'); await settle()
    dialog().querySelector('.el-select .el-input__inner').click(); await settle()
    ;[...document.querySelectorAll('.el-select-dropdown__item')].find(item => item.textContent.trim() === '甲单位下属队').click(); await settle()
    buttons('保存并立即生效')[0].click(); await settle()
    expect(systemApi.createUser).not.toHaveBeenCalled()
    expect(errorLabels()).toEqual(['临时密码'])
    expect(ElMessage.warning).toHaveBeenCalledWith('请填写以下必填项：临时密码。')
  })

  it('临时密码格式不符时在密码格提示规则，不当作漏填', async () => {
    await mount()
    buttons('新增用户')[0].click(); await settle()
    radio('后台用户').querySelector('input').click(); await settle()
    type('登录账号', 'ops-b'); type('姓名', '运维丙'); type('临时密码', 'short'); await settle()
    dialog().querySelector('.el-select .el-input__inner').click(); await settle()
    ;[...document.querySelectorAll('.el-select-dropdown__item')].find(item => item.textContent.trim() === '甲单位').click(); await settle()
    buttons('保存并立即生效')[0].click(); await settle()
    expect(systemApi.createUser).not.toHaveBeenCalled()
    expect(errorLabels()).toEqual(['临时密码'])
    expect(ElMessage.warning).toHaveBeenCalledWith('临时密码须为 6–32 位。')
  })
})

