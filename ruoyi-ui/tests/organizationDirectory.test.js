import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive } from 'vue'
import ElementPlus from 'element-plus/lib/index.js'
import OrganizationDialog from '@/views/system/organization/OrganizationDialog.vue'
import OrganizationDirectoryPanel from '@/views/system/organization/OrganizationDirectoryPanel.vue'
import PlanAssociationPanel from '@/views/system/organization/PlanAssociationPanel.vue'
import { directoryApi } from '@/api/organizationDirectory'

vi.mock('@/api/organizationDirectory', () => ({ directoryApi: {
  profile: vi.fn(), contacts: vi.fn(), bindings: vi.fn(), options: vi.fn(), saveContact: vi.fn(), saveBinding: vi.fn(), subjects: vi.fn(), saveSubjects: vi.fn()
} }))
vi.mock('element-plus', async original => ({ ...await original(), ElMessage: { success: vi.fn() } }))
let app, host, organization, otherOrganization, contact, binding, plan
async function settle() { for (let index = 0; index < 18; index++) { await Promise.resolve(); await nextTick() } }
async function mount(component, props = {}) {
  host = document.createElement('div'); document.body.append(host)
  app = createApp(component, props); app.use(ElementPlus); app.mount(host); await settle()
}
const button = text => [...document.querySelectorAll('button')].find(node => node.textContent.trim() === text)
const field = label => [...document.querySelectorAll('.el-form-item')].find(node => node.querySelector('.el-form-item__label')?.textContent.trim() === label)
async function fill(label, value) { const input = field(label).querySelector('input, textarea'); input.value = value; input.dispatchEvent(new Event('input', { bubbles: true })); await settle() }
async function option(label) { [...document.querySelectorAll('.el-select-dropdown__item')].find(node => node.textContent.trim() === label).click(); await settle() }
const viewProps = () => ({ visible: true, row: organization, mode: 'view', canReadDirectory: true, canEditDirectory: true })
const panelProps = (kind = 'contact') => ({ kind, organization, canEdit: true })
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }

beforeEach(() => {
  vi.resetAllMocks()
  organization = { org_id: `org-${crypto.randomUUID()}`, name: '当前运营单位', organization_type: 'OPERATOR', version: 3, contact_count: 1, plan_count: 1 }
  otherOrganization = { ...organization, org_id: `org-${crypto.randomUUID()}`, name: '另一运营单位' }
  contact = { contact_id: `contact-${crypto.randomUUID()}`, org_id: organization.org_id, org_name: organization.name, name: '已有联系人', roles: ['PILOT'], phone: '13812345678', enabled: true, version: 8, notification_count: 2, pending_count: 1, history_count: 4 }
  binding = { binding_id: `binding-${crypto.randomUUID()}`, org_id: organization.org_id, org_name: organization.name, source_id: `source-${crypto.randomUUID()}`, source_name: '计划来源', external_org_code: 'EXTERNAL-UNIT', enabled: true, version: 5 }
  plan = { plan_id: `plan-${crypto.randomUUID()}`, plan_no: '待核对任务', source_id: binding.source_id, source_name: binding.source_name, source_binding_id: binding.binding_id, reporting_org_id: organization.org_id, reporting_org_name: organization.name, operator_org_id: organization.org_id, operator_org_name: organization.name, pilot_contact_id: contact.contact_id, pilot_name: contact.name, pilot_contact_hint: '138****5678', association_status: 'LINKED', version: 12 }
  directoryApi.profile.mockImplementation(id => Promise.resolve(id === otherOrganization.org_id ? otherOrganization : organization))
  directoryApi.contacts.mockResolvedValue({ items: [contact], total: 1 })
  directoryApi.bindings.mockResolvedValue({ items: [binding], total: 1 })
  directoryApi.subjects.mockResolvedValue(plan)
  directoryApi.saveContact.mockResolvedValue({ ...contact, version: 9 })
  directoryApi.saveBinding.mockResolvedValue({ ...binding, version: 6 })
  directoryApi.saveSubjects.mockResolvedValue({ ...plan, version: 13 })
  directoryApi.options.mockImplementation(({ kind }) => Promise.resolve({ items: ({
    organizations: [organization, otherOrganization].map(row => ({ id: row.org_id, label: row.name })),
    contacts: [{ id: contact.contact_id, label: contact.name }],
    sources: [{ id: binding.source_id, label: binding.source_name }],
    plans: [{ id: plan.plan_id, label: plan.plan_no }], users: []
  })[kind], total: kind === 'users' ? 0 : 1 }))
})
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = '' })

describe('查看单位不再提供关联维护入口', () => {
  it.each([
    ['完整维护权限', { canReadDirectory: true, canEditDirectory: true }],
    ['仅目录读取权限', { canReadDirectory: true, canEditDirectory: false }],
    ['仅基本维护权限', { canReadDirectory: false, canEditDirectory: false, canEditBasic: true }]
  ])('%s：只读资料不查询或修改联系人、来源映射和任务关联', async (_, rights) => {
    await mount(OrganizationDialog, { ...viewProps(), ...rights })
    expect(field('单位名称').querySelector('textarea').value).toBe(organization.name)
    expect(document.querySelector('[role="tab"]')).toBeNull()
    expect(document.body.textContent).not.toContain('计划主体关联')
    expect(document.body.textContent).not.toContain('联系人')
    const inputs = [...document.querySelectorAll('.el-form input, .el-form textarea')]
    expect(inputs.length).toBeGreaterThan(0)
    expect(inputs.every(input => input.disabled)).toBe(true)
    expect(button('保存')).toBeUndefined()
    expect(button('新增联系人')).toBeUndefined()
    expect(button('新增来源映射')).toBeUndefined()
    expect(button('保存任务关联')).toBeUndefined()
    for (const api of ['contacts', 'bindings', 'subjects', 'saveContact', 'saveBinding', 'saveSubjects']) expect(directoryApi[api]).not.toHaveBeenCalled()
    expect(directoryApi.options.mock.calls.every(([query]) => query.kind === 'organizations')).toBe(true)
    if (!rights.canReadDirectory) {
      expect(directoryApi.profile).not.toHaveBeenCalled()
      expect(document.body.textContent).toContain('完整资料需要单位资料读取权限')
    }
    button('关闭').click(); await settle()
    expect(directoryApi.saveContact).not.toHaveBeenCalled()
    expect(directoryApi.saveBinding).not.toHaveBeenCalled()
    expect(directoryApi.saveSubjects).not.toHaveBeenCalled()
  })
})
describe('联系人和来源映射维护', () => {
  it('新增联系人固定当前单位，保留用途；成功后重读列表', async () => {
    await mount(OrganizationDirectoryPanel, panelProps())
    button('新增联系人').click(); await settle()
    await fill('联系人姓名', '新增维护人员')
    const maintenanceRole = [...document.querySelectorAll('.el-checkbox')].find(node => node.textContent.includes('运维联系人'))
    maintenanceRole.click(); await settle()
    await fill('联系电话', '13912345678')
    expect(field('所属单位').textContent).toContain(organization.name)
    expect(field('所属单位').querySelector('.el-select')).toBeNull()
    button('保存').click(); await settle()
    expect(directoryApi.saveContact).toHaveBeenCalledWith(undefined, expect.objectContaining({ org_id: organization.org_id, name: '新增维护人员', roles: ['MAINTENANCE'], phone: '13912345678', enabled: true }))
    expect(directoryApi.contacts).toHaveBeenCalledTimes(2)
    expect(button('保存')).toBeUndefined()
  })
  it.each(['contact', 'binding'])('%s 编辑停用携带原版本，不删除记录', async kind => {
    await mount(OrganizationDirectoryPanel, panelProps(kind))
    button('编辑').click(); await settle()
    document.querySelector('.directory-record-dialog .el-switch').click(); await settle()
    button('保存').click(); await settle()
    const save = kind === 'contact' ? directoryApi.saveContact : directoryApi.saveBinding
    const row = kind === 'contact' ? contact : binding
    expect(save).toHaveBeenCalledWith(row.contact_id || row.binding_id, expect.objectContaining({ org_id: organization.org_id, enabled: false, expected_version: row.version }))
  })
  it('新增来源映射写入选择的技术来源和外部单位编码', async () => {
    await mount(OrganizationDirectoryPanel, panelProps('binding'))
    button('新增来源映射').click(); await settle()
    await option(binding.source_name)
    await fill('来源系统中的单位编码', 'OTHER-EXTERNAL-UNIT')
    button('保存').click(); await settle()
    expect(directoryApi.saveBinding).toHaveBeenCalledWith(undefined, { org_id: organization.org_id, source_id: binding.source_id, external_org_code: 'OTHER-EXTERNAL-UNIT', enabled: true })
    expect(directoryApi.bindings).toHaveBeenCalledTimes(2)
  })
  it.each(['contact', 'binding'])('%s 版本冲突必须刷新并重新打开最新版本后才可保存', async kind => {
    const save = kind === 'contact' ? directoryApi.saveContact : directoryApi.saveBinding
    const list = kind === 'contact' ? directoryApi.contacts : directoryApi.bindings
    const row = kind === 'contact' ? contact : binding
    save.mockRejectedValueOnce({ status: 409, code: 'VERSION_CONFLICT', message: '资料版本已更新' })
    await mount(OrganizationDirectoryPanel, panelProps(kind))
    button('编辑').click(); await settle()
    button('保存').click(); await settle()
    expect(button('保存')).toBeUndefined()
    expect(document.body.textContent).toContain('本次未覆盖现有资料')
    list.mockResolvedValue({ items: [{ ...row, version: row.version + 2 }], total: 1 })
    button('重新加载并核对').click(); await settle()
    button('编辑').click(); await settle()
    button('保存').click(); await settle()
    expect(save).toHaveBeenLastCalledWith(row.contact_id || row.binding_id, expect.objectContaining({ expected_version: row.version + 2 }))
  })
  it('业务校验失败保留输入可修正，网络结果未知必须重读防重复提交', async () => {
    directoryApi.saveContact.mockRejectedValueOnce({ status: 400, message: '联系电话格式不正确' }).mockRejectedValueOnce({ status: 0, code: 'NETWORK_ERROR' })
    await mount(OrganizationDirectoryPanel, panelProps())
    button('编辑').click(); await settle()
    button('保存').click(); await settle()
    expect(document.body.textContent).toContain('联系电话格式不正确')
    expect(button('保存')).toBeDefined()
    button('保存').click(); await settle()
    expect(document.body.textContent).toContain('保存结果尚未确认')
    expect(button('保存')).toBeUndefined()
    expect(directoryApi.saveContact).toHaveBeenCalledTimes(2)
  })
  it('列表读取失败显示重试，重试成功显示已有记录', async () => {
    directoryApi.bindings.mockRejectedValueOnce(new Error('读取来源映射失败'))
    await mount(OrganizationDirectoryPanel, panelProps('binding'))
    expect(document.body.textContent).toContain('读取来源映射失败')
    button('刷新来源映射').click(); await settle()
    expect(document.body.textContent).toContain(binding.external_org_code)
    expect(document.body.textContent).not.toContain('读取来源映射失败')
  })
  it('分页继续携带单位范围，不把第一页当成完整清单', async () => {
    directoryApi.contacts.mockImplementation(({ page }) => Promise.resolve({ items: [{ ...contact, name: `联系人第${page}页` }], total: 45 }))
    await mount(OrganizationDirectoryPanel, panelProps())
    document.querySelector('.el-pagination .btn-next').click(); await settle()
    expect(directoryApi.contacts).toHaveBeenLastCalledWith({ org_id: organization.org_id, keyword: '', page: 2, size: 20 })
    expect(document.body.textContent).toContain('联系人第2页')
  })
  it('保存中的旧单位被切换后，迟到的保存结果不能关闭新单位表单', async () => {
    const slow = deferred()
    directoryApi.saveContact.mockReturnValueOnce(slow.promise)
    directoryApi.contacts.mockImplementation(({ org_id }) => Promise.resolve({ items: [{ ...contact, org_id, name: org_id === organization.org_id ? contact.name : '新单位联系人' }], total: 1 }))
    const state = reactive(panelProps())
    await mount({ components: { OrganizationDirectoryPanel }, setup: () => ({ state }), template: '<OrganizationDirectoryPanel v-bind="state" />' })
    button('编辑').click(); await settle()
    button('保存').click(); await settle()
    state.organization = otherOrganization; await settle()
    button('编辑').click(); await settle()
    slow.resolve({ ...contact, version: 9 }); await settle()
    expect(button('保存')).toBeDefined()
    expect(field('联系人姓名').querySelector('input').value).toBe('新单位联系人')
    expect(directoryApi.saveContact).toHaveBeenCalledTimes(1)
  })
  it('快速切换查询时旧结果不能覆盖新筛选', async () => {
    const slow = deferred()
    directoryApi.contacts.mockImplementation(({ keyword }) => keyword === '旧' ? slow.promise : Promise.resolve({ items: [{ ...contact, name: keyword || contact.name }], total: 1 }))
    await mount(OrganizationDirectoryPanel, panelProps())
    const input = document.querySelector('.directory-toolbar input')
    input.value = '旧'; input.dispatchEvent(new Event('input', { bubbles: true })); await settle()
    input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true })); await settle()
    input.value = '新'; input.dispatchEvent(new Event('input', { bubbles: true })); await settle()
    input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true })); await settle()
    slow.resolve({ items: [{ ...contact, name: '旧' }], total: 1 }); await settle()
    expect(document.querySelector('.el-table__body').textContent).toContain('新')
    expect(document.querySelector('.el-table__body').textContent).not.toContain('旧')
  })
})

describe('计划主体关联', () => {
  it('已有关联显示真实信息，保存使用原版本和明确依据', async () => {
    await mount(PlanAssociationPanel, { canEdit: true })
    await option(plan.plan_no)
    expect(directoryApi.subjects).toHaveBeenCalledWith(plan.plan_id)
    expect(document.body.textContent).toContain(plan.pilot_contact_hint)
    button('保存任务关联').click(); await settle()
    expect(directoryApi.saveSubjects).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('请填写本次关联或调整的依据')
    await fill('关联依据', '经报送资料核对')
    button('保存任务关联').click(); await settle()
    expect(directoryApi.saveSubjects).toHaveBeenCalledWith(plan.plan_id, { source_binding_id: binding.binding_id, operator_org_id: organization.org_id, pilot_contact_id: contact.contact_id, reason: '经报送资料核对', expected_version: 12 })
    expect(directoryApi.subjects).toHaveBeenCalledTimes(2)
  })
  it('计划版本冲突须重新读取后才能重试保存', async () => {
    directoryApi.saveSubjects.mockRejectedValueOnce({ status: 409, message: '计划版本已更新' })
    await mount(PlanAssociationPanel, { canEdit: true })
    await option(plan.plan_no)
    await fill('关联依据', '核对资料')
    button('保存任务关联').click(); await settle()
    expect(button('保存任务关联').disabled).toBe(true)
    directoryApi.subjects.mockResolvedValue({ ...plan, version: 14 })
    button('重新读取任务并核对').click(); await settle()
    await fill('关联依据', '重新核对资料')
    button('保存任务关联').click(); await settle()
    expect(directoryApi.saveSubjects).toHaveBeenLastCalledWith(plan.plan_id, expect.objectContaining({ expected_version: 14, reason: '重新核对资料' }))
  })
  it('任务加载失败保留重试入口，不显示空的可保存表单', async () => {
    directoryApi.subjects.mockRejectedValueOnce(new Error('任务暂时无法读取'))
    await mount(PlanAssociationPanel, { canEdit: true })
    await option(plan.plan_no)
    expect(document.body.textContent).toContain('任务暂时无法读取')
    expect(button('保存任务关联')).toBeUndefined()
    button('重新读取任务并核对').click(); await settle()
    expect(document.body.textContent).toContain(plan.pilot_contact_hint)
  })
})
