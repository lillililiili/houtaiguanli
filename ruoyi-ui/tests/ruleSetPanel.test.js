import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import RuleSetPanel from '@/views/system/rules/RuleSetPanel.vue'
import { ruleSetApi } from '@/api/ruleSets'

const permissions = vi.hoisted(() => ({ codes: new Set() }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: code => permissions.codes.has(code) }) }))
vi.mock('@/api/ruleSets', () => ({ ruleSetApi: { list: vi.fn(), versions: vi.fn(), activate: vi.fn() } }))
vi.mock('element-plus', async original => ({ ...await original(), ElMessage: { success: vi.fn(), error: vi.fn() }, ElMessageBox: { prompt: vi.fn() } }))

let app, host
const legality = { rule_set_id: 'rs1', rule_set_code: 'LEGALITY-DEMO', name: '合法性研判演示规则集', active_version_id: null, version: 0 }
const version = (no, extra = {}) => ({ rule_set_version_id: `v${no}`, rule_set_code: 'LEGALITY-DEMO', version_no: no, status_code: 'PUBLISHED', param_status: 'DEMO',
  description: `第${no}版说明`, published_at: 1_790_000_000_000, is_active: false, is_shadow: false, activation_allowed: true, ...extra })
async function settle() { for (let i = 0; i < 20; i++) { await Promise.resolve(); await nextTick() } }
async function mount() { host = document.createElement('div'); document.body.append(host); app = createApp(RuleSetPanel); app.use(ElementPlus); app.mount(host); await settle() }
const buttons = text => [...document.querySelectorAll('button')].filter(el => el.textContent.trim() === text)

describe('研判规则集启用', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    permissions.codes = new Set(['rule:read', 'rule:manage'])
    ruleSetApi.list.mockResolvedValue({ items: [legality], total: 1 })
    ruleSetApi.versions.mockResolvedValue({ items: [version(2), version(1)], total: 2 })
    ruleSetApi.activate.mockResolvedValue({ ...legality, active_version_id: 'v1', version: 1 })
    ElMessageBox.prompt.mockResolvedValue({ value: ' 新系统准备 ' })
  })
  afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = '' })

  it('没有生效版本时明确提示，系统不会按这套规则研判', async () => {
    await mount()
    expect(ruleSetApi.versions).toHaveBeenCalledWith('LEGALITY-DEMO')
    expect(host.textContent).toContain('合法性研判演示规则集')
    expect(host.textContent).toContain('尚未启用，系统不会按这套规则研判')
    expect(host.textContent).toContain('演示参数，未经业务方确认')
  })

  it('启用时填写原因并带上规则集版本号，完成后重新读取', async () => {
    await mount()
    buttons('启用此版本')[1].click(); await settle()
    expect(ElMessageBox.prompt).toHaveBeenCalledWith(expect.stringContaining('第 1 版'), '启用第 1 版', expect.any(Object))
    expect(ruleSetApi.activate).toHaveBeenCalledWith('LEGALITY-DEMO', { rule_set_version_id: 'v1', note: '新系统准备', expected_version: 0 })
    expect(ElMessage.success).toHaveBeenCalled()
    expect(ruleSetApi.list).toHaveBeenCalledTimes(2)
  })

  it('取消填写原因不发请求', async () => {
    ElMessageBox.prompt.mockRejectedValue('cancel')
    await mount()
    buttons('启用此版本')[0].click(); await settle()
    expect(ruleSetApi.activate).not.toHaveBeenCalled()
  })

  it('正式环境的演示参数和生效版本不给启用按钮，只说明原因', async () => {
    ruleSetApi.versions.mockResolvedValue({ items: [
      version(2, { activation_allowed: false, activation_block_reason: '演示参数尚未经业务方确认，正式环境不能启用' }),
      version(1, { param_status: 'CONFIRMED', is_active: true, activation_allowed: false, activation_block_reason: '该版本已是生效版本' })
    ], total: 2 })
    await mount()
    expect(buttons('启用此版本')).toHaveLength(0)
    expect(host.textContent).toContain('演示参数尚未经业务方确认，正式环境不能启用')
    expect(host.textContent).toContain('当前生效：第 1 版')
    expect(host.textContent).toContain('已确认参数')
  })

  it('只有查看权限时不给启用按钮', async () => {
    permissions.codes = new Set(['rule:read'])
    await mount()
    expect(buttons('启用此版本')).toHaveLength(0)
    expect(host.textContent).toContain('启用需要规则管理权限')
  })

  it('启用冲突时提示刷新后核对', async () => {
    ruleSetApi.activate.mockRejectedValue(Object.assign(new Error('规则集已被其他操作更新'), { code: 'VERSION_CONFLICT', status: 409 }))
    await mount()
    buttons('启用此版本')[0].click(); await settle()
    expect(ElMessage.error).toHaveBeenCalledWith(expect.stringContaining('已刷新'))
    expect(ruleSetApi.list).toHaveBeenCalledTimes(2)
  })
})
