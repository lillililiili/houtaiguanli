import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive } from 'vue'
import ElementPlus from 'element-plus'
import OrganizationDialog from '@/views/system/organization/OrganizationDialog.vue'
import UsersView from '@/views/system/UsersView.vue'
import { directoryApi } from '@/api/organizationDirectory'
import { systemApi } from '@/api/system'

const auth = vi.hoisted(() => ({ user: { menu_keys: ['organizations'], permission_codes: ['organizations.read'] }, hasPermission(code) { return this.user.permission_codes.includes(code) } }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => auth }))
vi.mock('@/api/organizationDirectory', () => ({ directoryApi: { profile: vi.fn(), profiles: vi.fn(), contacts: vi.fn(), contact: vi.fn(), bindings: vi.fn(), options: vi.fn(), saveProfile: vi.fn() } }))
vi.mock('@/api/system', () => ({ systemApi: { users: vi.fn(), organizations: vi.fn(), roles: vi.fn(), updateOrganization: vi.fn() } }))
vi.mock('element-plus', async original => ({ ...await original(), ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }))
let app, host
const profile = { org_id: 'org-1', name: '已维护单位', parent_id: 'parent', parent_name: '上级单位', org_code: 'ORG-1', organization_type: 'OPERATOR', address: '原单位地址', credit_code: 'CODE-1', remarks: '原备注', responsibilities: '已维护业务说明', version: 7 }
async function settle() { for (let i = 0; i < 16; i++) { await Promise.resolve(); await nextTick() } }
async function mount(component, props = {}) { host = document.createElement('div'); document.body.append(host); app = createApp(component, props); app.use(ElementPlus); app.mount(host); await settle() }
const button = label => [...document.querySelectorAll('button')].find(node => node.textContent.trim() === label)
const formLabels = () => [...document.querySelectorAll('.el-form-item__label')].map(node => node.textContent.trim())
const fieldValues = () => [...document.querySelectorAll('textarea, .el-input__inner, .el-textarea__inner')].map(node => node.value)
const props = () => ({ visible: true, row: profile, mode: 'view', canReadDirectory: true, canEditDirectory: true })
beforeEach(() => {
  vi.clearAllMocks()
  auth.user = { menu_keys: ['organizations'], permission_codes: ['organizations.read'] }
  directoryApi.profile.mockResolvedValue(profile)
  directoryApi.profiles.mockResolvedValue({ items: [profile], total: 1 })
  directoryApi.options.mockResolvedValue({ items: [], total: 0 })
  directoryApi.contacts.mockResolvedValue({ items: [], total: 0 })
  directoryApi.bindings.mockResolvedValue({ items: [], total: 0 })
  directoryApi.saveProfile.mockResolvedValue({ ...profile, name: '修改后的单位', version: 8 })
})
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = '' })
describe('用户管理内的单位资料', () => {
  it('查看单位用只读表单，不含页签、编辑入口、单位编码和账号维护提示', async () => {
    await mount(OrganizationDialog, props())
    expect(formLabels()).toEqual(expect.arrayContaining(['单位名称', '单位类型', '上级单位', '统一社会信用代码或统一标识', '单位地址', '备注']))
    expect(formLabels()).not.toContain('单位编码')
    expect(fieldValues()).toEqual(expect.arrayContaining(['已维护单位', '原单位地址', '原备注', '已维护业务说明']))
    expect(document.body.textContent).not.toContain('单位资料与账号分别维护')
    expect(document.querySelector('.el-tabs')).toBeFalsy()
    expect(document.querySelector('textarea[disabled], .el-textarea.is-disabled, .el-input.is-disabled')).toBeTruthy()
    expect(button('保存')).toBeUndefined()
    expect(button('取消')).toBeUndefined()
    expect(button('编辑单位')).toBeUndefined()
    expect(button('关闭')).toBeDefined()
  })
  it('查看不发送写请求；编辑保存携带扩展资料与版本', async () => {
    await mount(OrganizationDialog, props())
    expect(fieldValues()).toContain('原单位地址')
    expect(directoryApi.saveProfile).not.toHaveBeenCalled()
    app?.unmount(); host?.remove(); document.body.innerHTML = ''
    await mount(OrganizationDialog, { ...props(), mode: 'edit' })
    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(1)
    expect(formLabels()).not.toContain('单位编码')
    expect(document.body.textContent).not.toContain('单位资料与账号分别维护')
    const name = [...document.querySelectorAll('textarea')].find(node => node.value === profile.name)
    name.value = '修改后的单位'; name.dispatchEvent(new Event('input', { bubbles: true })); await settle()
    button('保存').click(); await settle()
    expect(directoryApi.saveProfile).toHaveBeenCalledWith('org-1', expect.objectContaining({ name: '修改后的单位', address: profile.address, remarks: profile.remarks, responsibilities: profile.responsibilities, credit_code: profile.credit_code, expected_version: 7 }))
  })
  it('从编辑单位取消直接关闭，不进入查看单位', async () => {
    const state = reactive({ ...props(), mode: 'edit', visible: true })
    await mount({
      components: { OrganizationDialog },
      setup: () => ({ state, onVisible: value => { state.visible = value } }),
      template: '<OrganizationDialog v-bind="state" @update:visible="onVisible" />'
    })
    expect(button('保存')).toBeDefined()
    expect(button('取消')).toBeDefined()
    button('取消').click(); await settle()
    expect(state.visible).toBe(false)
    expect(button('编辑单位')).toBeUndefined()
    expect(button('关闭')).toBeUndefined()
  })
  it('仅基本维护权限只提交名称与层级，不覆盖扩展资料', async () => {
    systemApi.updateOrganization.mockResolvedValue({ ...profile, version: 8 })
    await mount(OrganizationDialog, { ...props(), mode: 'edit', canEditDirectory: false, canEditBasic: true })
    expect([...document.querySelectorAll('.el-form-item__label')].map(node => node.textContent)).not.toContain('单位地址')
    button('保存').click(); await settle()
    expect(systemApi.updateOrganization).toHaveBeenCalledWith('org-1', { name: profile.name, parent_id: profile.parent_id, expected_version: 7 })
    expect(directoryApi.saveProfile).not.toHaveBeenCalled()
  })
  it('只读权限保留详情，隐藏所有编辑入口', async () => {
    await mount(OrganizationDialog, { ...props(), canEditDirectory: false })
    expect(fieldValues()).toContain('原单位地址')
    expect(button('编辑单位')).toBeUndefined()
    expect(button('保存')).toBeUndefined()
    expect(document.querySelector('.el-tabs')).toBeFalsy()
  })
  it('资料加载失败给出重试，不能用空表单覆盖原记录', async () => {
    directoryApi.profile.mockRejectedValue(new Error('读取单位失败'))
    await mount(OrganizationDialog, { ...props(), mode: 'edit' })
    expect(document.body.textContent).toContain('读取单位失败')
    expect(button('保存')).toBeUndefined()
    expect(button('编辑单位')).toBeUndefined()
  })
  it('快速切换单位时忽略旧详情返回', async () => {
    let firstResolve
    directoryApi.profile.mockImplementation(id => id === 'org-1' ? new Promise(resolve => { firstResolve = resolve }) : Promise.resolve({ ...profile, org_id: 'org-2', name: '第二个单位' }))
    const state = reactive(props())
    await mount({ components: { OrganizationDialog }, setup: () => ({ state }), template: '<OrganizationDialog v-bind="state" />' })
    state.row = { org_id: 'org-2' }; await settle()
    firstResolve(profile); await settle()
    expect(fieldValues()).toContain('第二个单位')
    expect(fieldValues()).not.toContain('已维护单位')
  })
  it('只有单位目录权限时仍有合并入口，不请求用户或角色列表', async () => {
    await mount(UsersView)
    expect(document.body.textContent).toContain('已维护单位')
    expect(systemApi.users).not.toHaveBeenCalled()
    expect(systemApi.roles).not.toHaveBeenCalled()
    expect(systemApi.organizations).not.toHaveBeenCalled()
    expect(directoryApi.profiles).toHaveBeenCalledWith({ page: 1, size: 100 })
  })
  it('新增下级单位带入父单位并复用完整单位接口', async () => {
    await mount(OrganizationDialog, { ...props(), row: null, mode: 'create', parent: profile })
    const name = document.querySelector('textarea')
    name.value = '下级单位'; name.dispatchEvent(new Event('input', { bubbles: true })); await settle()
    button('保存').click(); await settle()
    expect(directoryApi.saveProfile).toHaveBeenCalledWith(undefined, expect.objectContaining({ name: '下级单位', parent_id: 'org-1' }))
  })
})
